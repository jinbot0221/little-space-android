// Run only in a trusted admin environment; never bundle credentials with the app.
const { initializeApp, applicationDefault } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getFirestore } = require('firebase-admin/firestore');
const args = process.argv.slice(2);
const value = key => args[args.indexOf(key) + 1];
if (!args.includes('--live') || !args.includes('--project') || !args.includes('--uid')) {
  throw Error('Usage: node firebase/scripts/approve-host.js --live --project PROJECT --uid UID');
}
const project = value('--project'), uid = value('--uid');
if (!project || !uid || project.startsWith('demo-') || process.env.FIRESTORE_EMULATOR_HOST || process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  throw Error('Provide a live project and UID in a trusted admin environment');
}
initializeApp({ credential: applicationDefault(), projectId: project });
(async () => {
  const user = await getAuth().getUser(uid);
  const profile = await getFirestore().doc(`users/${uid}`).get();
  if (user.disabled || profile.get('accountType') !== 'host' || profile.get('termsAccepted') !== true) {
    throw Error('Only an enabled, registered host applicant can be approved');
  }
  await getAuth().setCustomUserClaims(uid, { ...user.customClaims, host: true });
  console.log('Host approved. The member can refresh approval status in the app.');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
