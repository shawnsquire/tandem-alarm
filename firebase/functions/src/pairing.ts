import { randomInt } from "crypto";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { CallableRequest, HttpsError, onCall } from "firebase-functions/v2/https";

// No 0/O/1/I, which are easy to misread.
const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 6;
const CODE_TTL_MS = 10 * 60_000;
const MAX_MEMBERS = 2;
const GRPC_ALREADY_EXISTS = 6;
const CODE_ATTEMPTS = 5;

const invalidCode = () => new HttpsError("not-found", "invalid or expired code");

export function requireUid(request: Pick<CallableRequest, "auth">): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "sign-in required");
  return uid;
}

function generateCode(): string {
  let code = "";
  for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)];
  return code;
}

/** Ensures the caller has a household (creating one if needed) and returns its id. */
async function ensureHousehold(uid: string): Promise<string> {
  const db = getFirestore();
  const userRef = db.doc(`users/${uid}`);
  // Transaction keyed on users/{uid}, so a double tap cannot create two households.
  return db.runTransaction(async (tx) => {
    const user = await tx.get(userRef);
    if (user.exists) {
      const householdId: string = user.get("householdId");
      const household = await tx.get(db.doc(`households/${householdId}`));
      if (household.exists) {
        if ((household.get("members") as string[]).length >= MAX_MEMBERS) {
          throw new HttpsError("failed-precondition", "household is full");
        }
        return householdId;
      }
    }
    const householdRef = db.collection("households").doc();
    tx.create(householdRef, { members: [uid], createdAt: FieldValue.serverTimestamp() });
    tx.set(userRef, { householdId: householdRef.id });
    return householdRef.id;
  });
}

export async function createPairCodeFor(uid: string): Promise<{ code: string; expiresAt: number }> {
  const householdId = await ensureHousehold(uid);
  const expiresAt = Date.now() + CODE_TTL_MS;
  for (let attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
    const code = generateCode();
    try {
      await getFirestore()
        .doc(`pairCodes/${code}`)
        .create({ householdId, createdBy: uid, expiresAt: Timestamp.fromMillis(expiresAt) });
      return { code, expiresAt };
    } catch (e) {
      if ((e as { code?: unknown }).code !== GRPC_ALREADY_EXISTS) throw e;
    }
  }
  throw new HttpsError("internal", "could not allocate a pair code");
}

export async function joinHouseholdFor(uid: string, data: { code?: unknown }): Promise<{ householdId: string }> {
  const raw = data?.code;
  if (typeof raw !== "string" || raw.trim() === "") {
    throw new HttpsError("invalid-argument", "code is required");
  }
  const code = raw.trim().toUpperCase();
  // Reject anything that could not be a real code before it reaches a document path.
  if (!new RegExp(`^[${CODE_ALPHABET}]{${CODE_LENGTH}}$`).test(code)) throw invalidCode();
  const db = getFirestore();
  const codeRef = db.doc(`pairCodes/${code}`);
  const userRef = db.doc(`users/${uid}`);

  return db.runTransaction(async (tx) => {
    const codeDoc = await tx.get(codeRef);
    const expiresAt = codeDoc.get("expiresAt") as Timestamp | undefined;
    if (!codeDoc.exists || !expiresAt || expiresAt.toMillis() <= Date.now()) throw invalidCode();

    const householdRef = db.doc(`households/${codeDoc.get("householdId")}`);
    const [household, user] = await Promise.all([tx.get(householdRef), tx.get(userRef)]);
    if (!household.exists) throw invalidCode(); // household was unpaired after the code was issued
    if (user.exists) throw new HttpsError("failed-precondition", "already in a household");
    const members = household.get("members") as string[];
    if (members.length >= MAX_MEMBERS) throw new HttpsError("failed-precondition", "household is full");

    tx.update(householdRef, { members: [...members, uid] });
    tx.set(userRef, { householdId: householdRef.id });
    tx.delete(codeRef);
    return { householdId: householdRef.id };
  });
}

export async function unpairFor(uid: string): Promise<void> {
  const db = getFirestore();
  const userRef = db.doc(`users/${uid}`);
  const householdRef = await db.runTransaction(async (tx) => {
    const user = await tx.get(userRef);
    if (!user.exists) return null; // idempotent
    const ref = db.doc(`households/${user.get("householdId")}`);
    const household = await tx.get(ref);
    const members = new Set<string>([uid, ...((household.get("members") as string[] | undefined) ?? [])]);

    // Delete the household doc first: the rules read it for membership, so
    // this denies new devices/commands writes at once, and it serializes
    // against joinHousehold (reads the household) and ensureHousehold (reads users/{uid}).
    tx.delete(ref);
    for (const m of members) tx.delete(db.doc(`users/${m}`));
    return ref;
  });
  // The subcollections are unreachable by now; clear them out.
  if (householdRef) await db.recursiveDelete(householdRef);
}

export const createPairCode = onCall((request) => createPairCodeFor(requireUid(request)));
export const joinHousehold = onCall((request) => joinHouseholdFor(requireUid(request), request.data));
export const unpair = onCall(async (request) => {
  await unpairFor(requireUid(request));
});
