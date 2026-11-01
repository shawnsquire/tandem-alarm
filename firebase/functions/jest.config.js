/** @type {import('jest').Config} */
module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  roots: ["<rootDir>/test"],
  testTimeout: 30000,
  // All suites share one emulator project; run in band so a clearFirestore() in one cannot wipe another's data.
  maxWorkers: 1,
  // @google-cloud/storage's HTTP client imports ESM-only node-fetch; the stub steers it to node:http.
  // gaxios v8 (under google-auth-library) takes a node-fetch-free path on the same global.
  setupFiles: ["<rootDir>/test/stubs/teeny-request-fetch.js"],
  // jwks-rsa (via firebase-admin auth) requires jose, which is ESM-only and cannot be loaded by Jest on Node < 24.9. Tests never verify tokens.
  moduleNameMapper: { "^jwks-rsa$": "<rootDir>/test/stubs/jwks-rsa.js" },
};
