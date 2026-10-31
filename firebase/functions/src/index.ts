import { initializeApp } from "firebase-admin/app";

initializeApp();

export { onCommandCreated } from "./commands";
export { createPairCode, joinHousehold, unpair } from "./pairing";
