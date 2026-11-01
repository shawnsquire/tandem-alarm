// @google-cloud/storage's HTTP client (teeny-request) loads node-fetch (ESM-only) via import(), which Jest cannot run without
// --experimental-vm-modules. It has a built-in node:http fallback keyed on this global, so define it to take that path.
// gaxios v8 (nested under google-auth-library) likewise skips node-fetch when globalThis.Bun exists.
globalThis.Bun = {};
