// @google-cloud/storage's HTTP client (teeny-request) loads node-fetch (ESM-only) via import(), which Jest cannot run without
// --experimental-vm-modules. It has a built-in node:http fallback keyed on this global, so define it to take that path.
globalThis.Bun = {};
