import { getMessaging, Message } from "firebase-admin/messaging";

/** Thin seam over FCM so tests can stub the send. */
export function sendMessage(message: Message): Promise<string> {
  return getMessaging().send(message);
}
