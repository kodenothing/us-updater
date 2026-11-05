#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { initializeApp, cert } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");

const FIREBASE_PROJECT_ID = "us-project-f6fc5";
const ROLE_CLAIM = "authenticated";

function readArg(name) {
  const prefix = `--${name}=`;
  const value = process.argv.find((arg) => arg.startsWith(prefix));
  return value ? value.slice(prefix.length).trim() : null;
}

function fail(message) {
  console.error(`\nERROR: ${message}\n`);
  process.exit(1);
}

async function main() {
  const applyChanges = process.argv.includes("--apply");
  const coupleCode = readArg("couple-code") || process.env.US_COUPLE_CODE || "";
  const credentialsPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;

  if (!/^(?:[A-HJKMNP-Z2-9]{8}|[A-HJKMNP-Z2-9]{12})$/.test(coupleCode)) {
    fail("Set US_COUPLE_CODE or pass --couple-code=YOURCODE (8 or 12 characters, uppercase).");
  }

  if (!credentialsPath) {
    fail("Set GOOGLE_APPLICATION_CREDENTIALS to a Firebase Admin service-account JSON file stored OUTSIDE this repository.");
  }

  const absoluteCredentialsPath = path.resolve(credentialsPath);
  if (!fs.existsSync(absoluteCredentialsPath)) {
    fail(`Service-account file not found: ${absoluteCredentialsPath}`);
  }

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(fs.readFileSync(absoluteCredentialsPath, "utf8"));
  } catch {
    fail("The service-account file is not valid JSON.");
  }

  if (serviceAccount.project_id !== FIREBASE_PROJECT_ID) {
    fail(`Wrong Firebase project. Expected ${FIREBASE_PROJECT_ID}, received ${serviceAccount.project_id || "no project_id"}.`);
  }

  if (!serviceAccount.client_email || !serviceAccount.private_key) {
    fail("The JSON file does not look like a Firebase service-account key.");
  }

  initializeApp({
    credential: cert(serviceAccount),
    projectId: FIREBASE_PROJECT_ID
  });

  const db = getFirestore();
  const auth = getAuth();
  const coupleRef = db.collection("couples").doc(coupleCode);
  const coupleSnapshot = await coupleRef.get();

  if (!coupleSnapshot.exists) {
    fail("That couple document does not exist in the configured Firebase project.");
  }

  const couple = coupleSnapshot.data() || {};
  const members = couple.members;

  if (!Array.isArray(members) || members.length !== 2 ||
      members.some((uid) => typeof uid !== "string" || !uid.trim()) ||
      new Set(members).size !== 2 || couple.inviteOnly === true) {
    fail("The selected shared space is not a completed two-member couple. No claims were changed.");
  }

  console.log(`Firebase project: ${FIREBASE_PROJECT_ID}`);
  console.log(`Shared space: ${coupleCode}`);
  console.log(`Verified members: ${members.length}`);
  console.log(`Mode: ${applyChanges ? "APPLY CLAIMS" : "DRY RUN (no changes)"}\n`);

  const users = [];
  for (const uid of members) {
    const user = await auth.getUser(uid);
    if (user.disabled) {
      fail(`Member ${uid} is disabled. No claims were changed.`);
    }

    const existingClaims = user.customClaims || {};
    const nextClaims = { ...existingClaims, role: ROLE_CLAIM };
    users.push({ uid, existingClaims, nextClaims });
    console.log(`- ${uid}: role claim ${existingClaims.role === ROLE_CLAIM ? "already present" : "will be added"}`);
  }

  if (!applyChanges) {
    console.log("\nDry run complete. Nothing was changed.");
    console.log("Review the two UIDs above, then run the same command with --apply.");
    return;
  }

  for (const user of users) {
    await auth.setCustomUserClaims(user.uid, user.nextClaims);
    const verified = await auth.getUser(user.uid);
    if (verified.customClaims?.role !== ROLE_CLAIM) {
      throw new Error(`Claim verification failed for member ${user.uid}.`);
    }
    console.log(`Updated and verified: ${user.uid}`);
  }

  console.log("\nBoth existing couple members now have role=authenticated.");
  console.log("Each phone must refresh its Firebase ID token before using Supabase.");
  console.log("Do not sign out of either anonymous Firebase account just to refresh the token.");
}

main().catch((error) => {
  console.error("\nProvisioning failed. Check the project, service-account permissions, and couple code.");
  console.error(error && error.message ? error.message : error);
  process.exitCode = 1;
});
