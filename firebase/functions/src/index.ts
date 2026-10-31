import { initializeApp } from "firebase-admin/app";

initializeApp();

export { createPairCode, joinHousehold, unpair } from "./pairing";
