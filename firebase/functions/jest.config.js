/** @type {import('jest').Config} */
module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  roots: ["<rootDir>/test"],
  testTimeout: 30000,
  // All suites share one emulator project; run in band so a clearFirestore() in one cannot wipe another's data.
  maxWorkers: 1,
  // jwks-rsa (via firebase-admin auth) requires jose, which is ESM-only and cannot be loaded by Jest on Node < 24.9. Tests never verify tokens.
  moduleNameMapper: { "^jwks-rsa$": "<rootDir>/test/stubs/jwks-rsa.js" },
};
