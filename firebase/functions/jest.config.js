/** @type {import('jest').Config} */
module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  roots: ["<rootDir>/test"],
  testTimeout: 30000,
  // All suites share one emulator project; run in band so a clearFirestore() in one cannot wipe another's data.
  maxWorkers: 1,
};
