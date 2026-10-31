import { readFileSync } from "fs";
import path from "path";
import {
  assertFails,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";

let testEnv: RulesTestEnvironment;

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

describe("deny-all firestore rules", () => {
  it("rejects an authenticated read", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.doc("households/h1").get());
  });

  it("rejects an unauthenticated read", async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(db.doc("households/h1").get());
  });
});
