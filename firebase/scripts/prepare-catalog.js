// Trusted admin maintenance tool; this is not an app server or Cloud Function.
const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const { ensureCatalog } = require("./catalog");
const args = process.argv.slice(2);
const value = (k) => {
  const i = args.indexOf(k);
  return i < 0 ? undefined : args[i + 1];
};
const project = value("--project"),
  host = value("--host-uid"),
  days = Number(value("--days") || 30);
if (
  !args.includes("--live") ||
  !args.includes("--project") ||
  !args.includes("--host-uid") ||
  !project ||
  !host ||
  !Number.isInteger(days) ||
  days < 1 ||
  days > 30
)
  throw Error(
    "Usage: node scripts/prepare-catalog.js --live --project PROJECT --host-uid UID [--days 30]",
  );
if (process.env.FIRESTORE_EMULATOR_HOST || project.startsWith("demo-"))
  throw Error("Use npm run seed for emulators");
initializeApp({ credential: applicationDefault(), projectId: project });
getAuth()
  .getUser(host)
  .then(() => ensureCatalog(getFirestore(), host, days))
  .then(() =>
    console.log(
      "Missing demo catalog days created. Existing spaces/inventory unchanged.",
    ),
  )
  .catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  });
