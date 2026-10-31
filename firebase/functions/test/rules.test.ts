import { readFileSync } from "fs";
import path from "path";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  setDoc,
  Timestamp,
  updateDoc,
  where,
} from "firebase/firestore";

const HID = "h1";
const ALICE = "alice";
const BOB = "bob";
const CAROL = "carol"; // authenticated, but not a member of h1

let testEnv: RulesTestEnvironment;

const dbFor = (uid: string) => testEnv.authenticatedContext(uid).firestore();
const anonDb = () => testEnv.unauthenticatedContext().firestore();

const inFuture = () => Timestamp.fromMillis(Date.now() + 60_000);
const inPast = () => Timestamp.fromMillis(Date.now() - 60_000);

/** A valid pending command from alice to bob; override fields per test. */
function command(overrides: Record<string, unknown> = {}) {
  return {
    from: ALICE,
    to: BOB,
    type: "SKIP_NEXT",
    alarmId: "a1",
    createdAt: Timestamp.now(),
    expiresAt: inFuture(),
    status: "pending",
    ...overrides,
  };
}

async function seedCommand(id: string, overrides: Record<string, unknown> = {}) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), `households/${HID}/commands/${id}`), command(overrides));
  });
}

const cmdPath = (id: string) => `households/${HID}/commands/${id}`;
const devicePath = (uid: string) => `households/${HID}/devices/${uid}`;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-tandem",
    firestore: {
      rules: readFileSync(path.resolve(__dirname, "../../firestore.rules"), "utf8"),
    },
  });
});

afterAll(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, `users/${ALICE}`), { householdId: HID });
    await setDoc(doc(db, `users/${BOB}`), { householdId: HID });
    await setDoc(doc(db, `households/${HID}`), {
      members: [ALICE, BOB],
      createdAt: Timestamp.now(),
    });
    for (const uid of [ALICE, BOB]) {
      await setDoc(doc(db, devicePath(uid)), {
        displayName: uid,
        fcmToken: "t",
        updatedAt: Timestamp.now(),
        alarms: [],
        ringing: null,
      });
    }
    await setDoc(doc(db, "pairCodes/ABC123"), {
      householdId: HID,
      createdBy: ALICE,
      expiresAt: inFuture(),
    });
  });
});

describe("unauthenticated access", () => {
  it("denies reading a user doc", async () => {
    await assertFails(getDoc(doc(anonDb(), `users/${ALICE}`)));
  });
  it("denies reading a household", async () => {
    await assertFails(getDoc(doc(anonDb(), `households/${HID}`)));
  });
  it("denies reading a device doc", async () => {
    await assertFails(getDoc(doc(anonDb(), devicePath(ALICE))));
  });
  it("denies reading a command", async () => {
    await seedCommand("c1");
    await assertFails(getDoc(doc(anonDb(), cmdPath("c1"))));
  });
});

describe("users/{uid}", () => {
  it("lets the owner read their own doc", async () => {
    await assertSucceeds(getDoc(doc(dbFor(ALICE), `users/${ALICE}`)));
  });
  it("denies reading another user's doc", async () => {
    await assertFails(getDoc(doc(dbFor(ALICE), `users/${BOB}`)));
  });
  it("denies the owner writing their own doc", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), `users/${ALICE}`), { householdId: "other" }));
  });
  it("denies creating a user doc", async () => {
    await assertFails(setDoc(doc(dbFor(CAROL), `users/${CAROL}`), { householdId: HID }));
  });
});

describe("households/{hid}", () => {
  it("lets a member read the household", async () => {
    await assertSucceeds(getDoc(doc(dbFor(ALICE), `households/${HID}`)));
  });
  it("denies a non-member reading the household", async () => {
    await assertFails(getDoc(doc(dbFor(CAROL), `households/${HID}`)));
  });
  it("denies a member updating the household", async () => {
    await assertFails(updateDoc(doc(dbFor(ALICE), `households/${HID}`), { members: [ALICE, CAROL] }));
  });
  it("denies creating a household", async () => {
    await assertFails(
      setDoc(doc(dbFor(CAROL), "households/h2"), { members: [CAROL], createdAt: Timestamp.now() }),
    );
  });
  it("denies a member deleting the household", async () => {
    await assertFails(deleteDoc(doc(dbFor(ALICE), `households/${HID}`)));
  });
});

describe("after the household is deleted", () => {
  beforeEach(async () => {
    await seedCommand("c1");
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await deleteDoc(doc(ctx.firestore(), `households/${HID}`));
    });
  });

  it("denies a former member reading the household", async () => {
    await assertFails(getDoc(doc(dbFor(ALICE), `households/${HID}`)));
  });
  it("denies a former member reading a device doc", async () => {
    await assertFails(getDoc(doc(dbFor(ALICE), devicePath(BOB))));
  });
  it("denies a former member reading a command", async () => {
    await assertFails(getDoc(doc(dbFor(BOB), cmdPath("c1"))));
  });
  it("denies a former member writing a command", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c2")), command()));
  });
  it("denies a former member writing their own device doc", async () => {
    await assertFails(updateDoc(doc(dbFor(ALICE), devicePath(ALICE)), { fcmToken: "new" }));
  });
  it("denies the recipient resolving a pending command", async () => {
    await assertFails(updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "applied" }));
  });
});

describe("households/{hid}/devices/{uid}", () => {
  it("lets a member read their own device doc", async () => {
    await assertSucceeds(getDoc(doc(dbFor(ALICE), devicePath(ALICE))));
  });
  it("lets a member read the partner's device doc", async () => {
    await assertSucceeds(getDoc(doc(dbFor(ALICE), devicePath(BOB))));
  });
  it("denies a non-member reading a device doc", async () => {
    await assertFails(getDoc(doc(dbFor(CAROL), devicePath(ALICE))));
  });
  it("lets a member update their own device doc", async () => {
    await assertSucceeds(updateDoc(doc(dbFor(ALICE), devicePath(ALICE)), { fcmToken: "new" }));
  });
  it("lets a member create their own device doc", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await deleteDoc(doc(ctx.firestore(), devicePath(ALICE)));
    });
    await assertSucceeds(
      setDoc(doc(dbFor(ALICE), devicePath(ALICE)), {
        displayName: "Alice",
        fcmToken: "t",
        updatedAt: Timestamp.now(),
        alarms: [],
        ringing: null,
      }),
    );
  });
  it("denies writing the partner's device doc", async () => {
    await assertFails(updateDoc(doc(dbFor(ALICE), devicePath(BOB)), { fcmToken: "evil" }));
  });
  it("denies a non-member creating a device doc under their own uid", async () => {
    await assertFails(setDoc(doc(dbFor(CAROL), devicePath(CAROL)), { displayName: "Carol" }));
  });
});

describe("households/{hid}/commands: read", () => {
  it("lets a member read a command", async () => {
    await seedCommand("c1");
    await assertSucceeds(getDoc(doc(dbFor(BOB), cmdPath("c1"))));
    await assertSucceeds(getDoc(doc(dbFor(ALICE), cmdPath("c1"))));
  });
  it("denies a non-member reading a command", async () => {
    await seedCommand("c1");
    await assertFails(getDoc(doc(dbFor(CAROL), cmdPath("c1"))));
  });
  it("lets a member list commands where to == me and status == pending", async () => {
    await seedCommand("c1");
    const q = query(
      collection(dbFor(BOB), `households/${HID}/commands`),
      where("to", "==", BOB),
      where("status", "==", "pending"),
    );
    const snap = await assertSucceeds(getDocs(q));
    expect(snap.size).toBe(1);
  });
  it("denies a non-member running the same list query", async () => {
    await seedCommand("c1");
    const q = query(
      collection(dbFor(CAROL), `households/${HID}/commands`),
      where("to", "==", CAROL),
      where("status", "==", "pending"),
    );
    await assertFails(getDocs(q));
  });
});

describe("households/{hid}/commands: create", () => {
  it("lets a member send a valid command to the other member", async () => {
    await assertSucceeds(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command()));
  });
  it.each(["SKIP_NEXT", "UNSKIP", "DISABLE", "ENABLE", "STOP_RINGING"])(
    "accepts type %s",
    async (type) => {
      await assertSucceeds(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ type })));
    },
  );
  it("denies a spoofed from", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ from: BOB, to: BOB })));
  });
  it("denies a command to self", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ to: ALICE })));
  });
  it("denies a command to a non-member", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ to: CAROL })));
  });
  it("denies a non-member creating a command even when from == auth.uid", async () => {
    await assertFails(
      setDoc(doc(dbFor(CAROL), cmdPath("c1")), command({ from: CAROL, to: ALICE })),
    );
  });
  it("denies a status other than pending", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ status: "applied" })));
  });
  it("denies an unknown type", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ type: "SELF_DESTRUCT" })));
  });
  it("denies an already-expired expiresAt", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ expiresAt: inPast() })));
  });
  it("accepts occurrenceAt as a timestamp with no ringId", async () => {
    await assertSucceeds(
      setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ occurrenceAt: inFuture() })),
    );
  });
  it("accepts ringId as a string with no occurrenceAt", async () => {
    await assertSucceeds(
      setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ type: "STOP_RINGING", ringId: "r1" })),
    );
  });
  it("accepts a null occurrenceAt", async () => {
    await assertSucceeds(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ occurrenceAt: null })));
  });
  it("accepts a null ringId", async () => {
    await assertSucceeds(
      setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ type: "STOP_RINGING", ringId: null })),
    );
  });
  it("denies a missing createdAt", async () => {
    const { createdAt: _omit, ...noCreated } = command();
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), noCreated));
  });
  it("denies a numeric createdAt", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ createdAt: Date.now() })));
  });
  it("denies a missing alarmId", async () => {
    const { alarmId: _omit, ...noAlarm } = command();
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), noAlarm));
  });
  it("denies a numeric alarmId", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ alarmId: 42 })));
  });
  it("denies a numeric occurrenceAt", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ occurrenceAt: Date.now() })));
  });
  it("denies a numeric ringId", async () => {
    await assertFails(
      setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ type: "STOP_RINGING", ringId: 7 })),
    );
  });
  it("denies a missing expiresAt", async () => {
    const { expiresAt: _omit, ...noExpiry } = command();
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), noExpiry));
  });
  it("accepts an expiry of 24 hours", async () => {
    await assertSucceeds(
      setDoc(
        doc(dbFor(ALICE), cmdPath("c1")),
        command({ expiresAt: Timestamp.fromMillis(Date.now() + 24 * 3_600_000) }),
      ),
    );
  });
  it("denies an expiry a year out", async () => {
    await assertFails(
      setDoc(
        doc(dbFor(ALICE), cmdPath("c1")),
        command({ expiresAt: Timestamp.fromMillis(Date.now() + 365 * 24 * 3_600_000) }),
      ),
    );
  });
  it("denies an unknown extra key", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ foo: "x" })));
  });
  it("denies a reason on create", async () => {
    await assertFails(setDoc(doc(dbFor(ALICE), cmdPath("c1")), command({ reason: "x" })));
  });
});

describe("households/{hid}/commands: update", () => {
  beforeEach(async () => {
    await seedCommand("c1");
  });

  it("lets the recipient move pending to applied", async () => {
    await assertSucceeds(updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "applied" }));
  });
  it("lets the recipient move pending to rejected with a reason", async () => {
    await assertSucceeds(
      updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "rejected", reason: "stale" }),
    );
  });
  it("denies a non-string reason", async () => {
    await assertFails(
      updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "rejected", reason: 5 }),
    );
  });
  it("denies the sender updating the status", async () => {
    await assertFails(updateDoc(doc(dbFor(ALICE), cmdPath("c1")), { status: "applied" }));
  });
  it("denies a non-member updating the status", async () => {
    await assertFails(updateDoc(doc(dbFor(CAROL), cmdPath("c1")), { status: "applied" }));
  });
  it("denies the recipient changing type", async () => {
    await assertFails(
      updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "applied", type: "DISABLE" }),
    );
  });
  it("denies the recipient changing expiresAt", async () => {
    await assertFails(
      updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "applied", expiresAt: inFuture() }),
    );
  });
  it("denies a status other than applied or rejected", async () => {
    await assertFails(updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "pending" }));
    await assertFails(updateDoc(doc(dbFor(BOB), cmdPath("c1")), { status: "bogus" }));
  });
  it("denies moving applied to rejected", async () => {
    await seedCommand("c2", { status: "applied" });
    await assertFails(updateDoc(doc(dbFor(BOB), cmdPath("c2")), { status: "rejected" }));
  });
  it("denies moving rejected to applied", async () => {
    await seedCommand("c3", { status: "rejected" });
    await assertFails(updateDoc(doc(dbFor(BOB), cmdPath("c3")), { status: "applied" }));
  });
});

describe("households/{hid}/commands: delete", () => {
  it("denies the recipient deleting a command", async () => {
    await seedCommand("c1");
    await assertFails(deleteDoc(doc(dbFor(BOB), cmdPath("c1"))));
  });
  it("denies the sender deleting a command", async () => {
    await seedCommand("c1");
    await assertFails(deleteDoc(doc(dbFor(ALICE), cmdPath("c1"))));
  });
});

describe("pairCodes", () => {
  it("denies a client reading a pair code", async () => {
    await assertFails(getDoc(doc(dbFor(ALICE), "pairCodes/ABC123")));
  });
  it("denies a client writing a pair code", async () => {
    await assertFails(
      setDoc(doc(dbFor(ALICE), "pairCodes/ZZZ999"), {
        householdId: HID,
        createdBy: ALICE,
        expiresAt: inFuture(),
      }),
    );
  });
});
