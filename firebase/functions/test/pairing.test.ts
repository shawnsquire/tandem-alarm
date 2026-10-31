import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { createPairCodeFor, joinHouseholdFor, requireUid, unpairFor } from "../src/pairing";

// firebase-admin's auth module pulls in jwks-rsa -> jose, which is ESM-only and cannot be require()d under
// Jest on Node < 24.9. These tests never verify tokens, so stub it out.
jest.mock("jwks-rsa", () => ({}));

if (getApps().length === 0) initializeApp({ projectId: "demo-tandem" });

const A = "uidA";
const B = "uidB";
const C = "uidC";
const db = () => getFirestore();

beforeEach(async () => {
  const host = process.env.FIRESTORE_EMULATOR_HOST;
  await fetch(`http://${host}/emulator/v1/projects/demo-tandem/databases/(default)/documents`, {
    method: "DELETE",
  });
});

const rejects = (p: Promise<unknown>, code: string, message: string) =>
  expect(p).rejects.toMatchObject({ code, message });

const INVALID = ["not-found", "invalid or expired code"] as const;

async function seedCode(code: string, householdId: string, expiresAtMs: number) {
  await db()
    .doc(`pairCodes/${code}`)
    .set({ householdId, createdBy: A, expiresAt: Timestamp.fromMillis(expiresAtMs) });
}

async function pairedHousehold(): Promise<string> {
  const { code } = await createPairCodeFor(A);
  return (await joinHouseholdFor(B, { code })).householdId;
}

describe("requireUid", () => {
  it("rejects a request with no auth", () => {
    expect(() => requireUid({} as never)).toThrow(expect.objectContaining({ code: "unauthenticated" }));
  });
  it("returns the uid when authenticated", () => {
    expect(requireUid({ auth: { uid: A } } as never)).toBe(A);
  });
});

describe("createPairCode", () => {
  it("creates household, user index and a well-formed code", async () => {
    const before = Date.now();
    const { code, expiresAt } = await createPairCodeFor(A);
    expect(code).toMatch(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/);
    expect(typeof expiresAt).toBe("number");
    expect(Math.abs(expiresAt - (before + 10 * 60_000))).toBeLessThan(5_000);

    const user = (await db().doc(`users/${A}`).get()).data()!;
    const hh = (await db().doc(`households/${user.householdId}`).get()).data()!;
    expect(hh.members).toEqual([A]);
    expect(hh.createdAt).toBeInstanceOf(Timestamp);
    const codeDoc = (await db().doc(`pairCodes/${code}`).get()).data()!;
    expect(codeDoc.householdId).toBe(user.householdId);
    expect(codeDoc.createdBy).toBe(A);
    expect(codeDoc.expiresAt.toMillis()).toBe(expiresAt);
  });

  it("reuses the same household on a second call", async () => {
    await createPairCodeFor(A);
    const h1 = (await db().doc(`users/${A}`).get()).data()!.householdId;
    await createPairCodeFor(A);
    const h2 = (await db().doc(`users/${A}`).get()).data()!.householdId;
    expect(h2).toBe(h1);
    expect((await db().collection("households").get()).size).toBe(1);
  });

  it("rejects when the household is already full", async () => {
    await pairedHousehold();
    await rejects(createPairCodeFor(A), "failed-precondition", "household is full");
  });
});

describe("joinHousehold", () => {
  it("pairs B with A's household and consumes the code", async () => {
    const { code } = await createPairCodeFor(A);
    const { householdId } = await joinHouseholdFor(B, { code });
    expect((await db().doc(`users/${A}`).get()).data()!.householdId).toBe(householdId);
    expect((await db().doc(`users/${B}`).get()).data()).toEqual({ householdId });
    expect((await db().doc(`households/${householdId}`).get()).data()!.members).toEqual([A, B]);
    expect((await db().doc(`pairCodes/${code}`).get()).exists).toBe(false);
  });

  it("accepts a lowercase, padded code", async () => {
    const { code } = await createPairCodeFor(A);
    await expect(joinHouseholdFor(B, { code: ` ${code.toLowerCase()} ` })).resolves.toHaveProperty("householdId");
  });

  it("rejects an expired code", async () => {
    const { code } = await createPairCodeFor(A);
    const hid = (await db().doc(`users/${A}`).get()).data()!.householdId;
    await db().doc(`pairCodes/${code}`).delete();
    await seedCode("EXPIRD", hid, Date.now() - 1000);
    await rejects(joinHouseholdFor(B, { code: "EXPIRD" }), ...INVALID);
  });

  it("rejects a reused code", async () => {
    const { code } = await createPairCodeFor(A);
    await joinHouseholdFor(B, { code });
    await rejects(joinHouseholdFor(C, { code }), ...INVALID);
  });

  it("rejects a valid code for a full household", async () => {
    const hid = await pairedHousehold();
    await seedCode("FULLHH", hid, Date.now() + 60_000);
    await rejects(joinHouseholdFor(C, { code: "FULLHH" }), "failed-precondition", "household is full");
  });

  it("rejects a caller who is already in a household", async () => {
    const { code } = await createPairCodeFor(A);
    await createPairCodeFor(B);
    await rejects(joinHouseholdFor(B, { code }), "failed-precondition", "already in a household");
  });

  it("rejects joining your own code", async () => {
    const { code } = await createPairCodeFor(A);
    await rejects(joinHouseholdFor(A, { code }), "failed-precondition", "already in a household");
  });

  it("treats a code for a deleted household as invalid", async () => {
    await seedCode("GONEHH", "no-such-household", Date.now() + 60_000);
    await rejects(joinHouseholdFor(B, { code: "GONEHH" }), ...INVALID);
  });

  it("rejects a garbage code", async () => {
    await rejects(joinHouseholdFor(B, { code: "ZZZZZZ" }), ...INVALID);
  });

  it.each([undefined, null, 123, "", "   "])("rejects a bad code argument %p", async (code) => {
    await expect(joinHouseholdFor(B, { code } as never)).rejects.toMatchObject({ code: "invalid-argument" });
  });
  it("rejects missing data", async () => {
    await expect(joinHouseholdFor(B, undefined as never)).rejects.toMatchObject({ code: "invalid-argument" });
  });
});

describe("unpair", () => {
  it("removes the household, its subcollections and both user docs", async () => {
    const hid = await pairedHousehold();
    const hh = db().doc(`households/${hid}`);
    await hh.collection("devices").doc(A).set({ displayName: "a" });
    await hh.collection("devices").doc(B).set({ displayName: "b" });
    await hh.collection("commands").doc("x").set({ type: "ring" });

    await unpairFor(A);

    for (const ref of [
      hh,
      hh.collection("devices").doc(A),
      hh.collection("devices").doc(B),
      hh.collection("commands").doc("x"),
      db().doc(`users/${A}`),
      db().doc(`users/${B}`),
    ]) {
      expect((await ref.get()).exists).toBe(false);
    }
  });

  it("does not throw for a user with no household", async () => {
    await expect(unpairFor(C)).resolves.toBeUndefined();
  });
});
