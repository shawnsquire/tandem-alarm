import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { sendMessage } from "./messaging";

type CommandData = FirebaseFirestore.DocumentData;

const millis = (ts: Timestamp) => String(ts.toMillis());

/** Builds the FCM data payload field by field; absent optional fields are omitted. */
function commandPayload(householdId: string, commandId: string, c: CommandData): Record<string, string> {
  const data: Record<string, string> = {
    householdId,
    commandId,
    // "from" is a reserved FCM data key, so the payload calls it fromUid.
    fromUid: c.from,
    to: c.to,
    type: c.type,
    alarmId: c.alarmId,
  };
  if (c.occurrenceAt != null) data.occurrenceAt = millis(c.occurrenceAt);
  if (c.ringId != null) data.ringId = c.ringId;
  data.createdAt = millis(c.createdAt);
  data.expiresAt = millis(c.expiresAt);
  data.status = c.status;
  if (c.reason != null) data.reason = c.reason;
  return data;
}

export async function notifyCommandTarget(
  householdId: string,
  commandId: string,
  command: CommandData,
): Promise<void> {
  const device = await getFirestore().doc(`households/${householdId}/devices/${command.to}`).get();
  const token: string | undefined = device.get("fcmToken");
  if (!token) {
    logger.info("No FCM token for command target", { householdId, commandId, to: command.to });
    return;
  }
  await sendMessage({
    token,
    data: commandPayload(householdId, commandId, command),
    // Don't let FCM deliver a command after it has expired (default TTL is 4 weeks).
    android: { priority: "high", ttl: Math.max(0, (command.expiresAt as Timestamp).toMillis() - Date.now()) },
  });
}

export const onCommandCreated = onDocumentCreated("households/{hid}/commands/{cid}", (event) => {
  const command = event.data?.data();
  if (!command) return;
  return notifyCommandTarget(event.params.hid, event.params.cid, command);
});
