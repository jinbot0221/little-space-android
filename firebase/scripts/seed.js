const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const { ensureCatalog } = require("./catalog");
if (
  !process.env.FIRESTORE_EMULATOR_HOST ||
  !process.env.FIREBASE_AUTH_EMULATOR_HOST
)
  throw Error("Seed is restricted to both local emulators");
initializeApp({ projectId: "demo-teumgot" });
(async () => {
  const db = getFirestore(),
    auth = getAuth();
  for (const [uid, email, host] of [
    ["demo-customer", "customer@teumgot.test", false],
    ["demo-host", "host@teumgot.test", true],
  ]) {
    try {
      await auth.getUser(uid);
    } catch (e) {
      if (e.code !== "auth/user-not-found") throw e;
      await auth.createUser({
        uid,
        email,
        password: "Teumgot123!",
        displayName: host ? "테스트 운영자" : "테스트 고객",
      });
    }
    await auth.setCustomUserClaims(uid, {});
    await db
      .doc(`users/${uid}`)
      .set({
        nickname: host ? "테스트 운영자" : "테스트 고객",
        accountType: host ? "host" : "customer",
        approved: host,
        termsAccepted: true,
        termsVersion: "1.0",
        privacyVersion: "1.0",
      });
  }
  await ensureCatalog(db, "demo-host");
  console.log(
    "Spark fixtures ready: 3 spaces, 30 KST days, 2 local accounts; existing inventory preserved",
  );
})().catch((e) => {
  console.error(e.message);
  process.exitCode = 1;
});
