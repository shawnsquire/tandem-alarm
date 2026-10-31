import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { notifyCommandTarget, onCommandCreated } from "../src/commands";
import { sendMessage } from "../src/messaging";

jest.mock("../src/messaging");

if (getApps().length === 0) initializeApp({ projectId: "demo-tandem" });

const HID = "h1";
const CID = "c1";
const TOKEN = "token-B";
const db = () => getFirestore();
const send = sendMessage as jest.MockedFunction<typeof sendMessage>;

const CREATED_MS = 1_700_000_000_000;
const EXPIRES_MS = CREATED_MS + 60_000;
const OCCURRENCE_MS = CREATED_MS + 3_600_000;

beforeEach(async () => {
  jest.resetAllMocks();
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  await fetch(`http://${host}/emulator/v1/projects/demo-tandem/databases/(default)/documents`, {
    method: "DELETE",
  });
});

async function seedDevice(data: Record<string, unknown>) {
  await db().doc(`households/${HID}/devices/uidB`).set({ displayName: "B", ...data });
}

/** Writes the command, then reads it back so Timestamps are the real thing. */
async function seedCommand(fields: Record<string, unknown>) {
  const ref = db().doc(`households/${HID}/commands/${CID}`);
  await ref.set({
    from: "uidA",
    to: "uidB",
    alarmId: "alarm1",
    createdAt: Timestamp.fromMillis(CREATED_MS),
    expiresAt: Timestamp.fromMillis(EXPIRES_MS),
    status: "pending",
    ...fields,
  });
  return (await ref.get()).data()!;
}

describe("notifyCommandTarget", () => {
  it("sends a high-priority data-only message for SKIP_NEXT", async () => {
    await seedDevice({ fcmToken: TOKEN });
    const data = await seedCommand({
      type: "SKIP_NEXT",
      occurrenceAt: Timestamp.fromMillis(OCCURRENCE_MS),
    });

    const now = jest.spyOn(Date, "now").mockReturnValue(CREATED_MS);
    try {
      await notifyCommandTarget(HID, CID, data);
    } finally {
      now.mockRestore();
    }

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({
      token: TOKEN,
      data: {
        householdId: HID,
        commandId: CID,
        fromUid: "uidA",
        to: "uidB",
        type: "SKIP_NEXT",
        alarmId: "alarm1",
        occurrenceAt: String(OCCURRENCE_MS),
        createdAt: String(CREATED_MS),
        expiresAt: String(EXPIRES_MS),
        status: "pending",
      },
      android: { priority: "high", ttl: EXPIRES_MS - CREATED_MS },
    });
  });

  it("clamps the FCM ttl to zero once the command has expired", async () => {
    await seedDevice({ fcmToken: TOKEN });
    const data = await seedCommand({ type: "ENABLE" });

    const now = jest.spyOn(Date, "now").mockReturnValue(EXPIRES_MS + 5_000);
    try {
      await notifyCommandTarget(HID, CID, data);
    } finally {
      now.mockRestore();
    }

    expect(send.mock.calls[0][0]).toMatchObject({ android: { ttl: 0 } });
  });

  it("includes ringId and omits an absent occurrenceAt for STOP_RINGING", async () => {
    await seedDevice({ fcmToken: TOKEN });
    const data = await seedCommand({ type: "STOP_RINGING", ringId: "ring1" });

    await notifyCommandTarget(HID, CID, data);

    const { data: payload } = send.mock.calls[0][0] as { data: Record<string, string> };
    expect(payload.ringId).toBe("ring1");
    expect(payload).not.toHaveProperty("occurrenceAt");
    expect(payload).not.toHaveProperty("reason");
  });

  it("omits null occurrenceAt and ringId from the payload", async () => {
    await seedDevice({ fcmToken: TOKEN });
    const data = await seedCommand({ type: "ENABLE", occurrenceAt: null, ringId: null });

    await notifyCommandTarget(HID, CID, data);

    const { data: payload } = send.mock.calls[0][0] as { data: Record<string, string> };
    expect(payload).not.toHaveProperty("occurrenceAt");
    expect(payload).not.toHaveProperty("ringId");
  });

  it("caps the FCM ttl at 24 hours", async () => {
    await seedDevice({ fcmToken: TOKEN });
    const data = await seedCommand({
      type: "ENABLE",
      expiresAt: Timestamp.fromMillis(CREATED_MS + 7 * 24 * 3_600_000),
    });

    const now = jest.spyOn(Date, "now").mockReturnValue(CREATED_MS);
    try {
      await notifyCommandTarget(HID, CID, data);
    } finally {
      now.mockRestore();
    }

    expect(send.mock.calls[0][0]).toMatchObject({ android: { ttl: 24 * 3_600_000 } });
  });

  it("sends only string values and no reserved FCM keys", async () => {
    await seedDevice({ fcmToken: TOKEN });
    const data = await seedCommand({
      type: "SKIP_NEXT",
      occurrenceAt: Timestamp.fromMillis(OCCURRENCE_MS),
      ringId: "ring1",
      reason: "r",
      extra: "ignored",
    });

    await notifyCommandTarget(HID, CID, data);

    const { data: payload } = send.mock.calls[0][0] as { data: Record<string, string> };
    for (const [key, value] of Object.entries(payload)) {
      expect(typeof value).toBe("string");
      expect(key).not.toBe("from");
      expect(key).not.toBe("message_type");
      expect(key.startsWith("google")).toBe(false);
      expect(key.startsWith("gcm.")).toBe(false);
    }
    expect(payload).not.toHaveProperty("extra");
    expect(payload).not.toHaveProperty("reason");
  });

  it("does not send when the device has no fcmToken", async () => {
    await seedDevice({});
    const data = await seedCommand({ type: "ENABLE" });

    await notifyCommandTarget(HID, CID, data);

    expect(send).not.toHaveBeenCalled();
  });

  it("does not send when the device doc does not exist", async () => {
    const data = await seedCommand({ type: "ENABLE" });

    await notifyCommandTarget(HID, CID, data);

    expect(send).not.toHaveBeenCalled();
  });
});

describe("onCommandCreated", () => {
  it("ignores an event with no snapshot", async () => {
    await seedDevice({ fcmToken: TOKEN });
    const event = { data: undefined, params: { hid: HID, cid: CID } };

    await expect(Promise.resolve(onCommandCreated.run(event as never))).resolves.toBeUndefined();

    expect(send).not.toHaveBeenCalled();
  });
});
