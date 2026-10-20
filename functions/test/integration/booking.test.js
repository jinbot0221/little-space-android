const { test, before } = require("node:test");
const a = require("node:assert/strict");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
if (
  !process.env.FIRESTORE_EMULATOR_HOST ||
  !process.env.FIREBASE_AUTH_EMULATOR_HOST
)
  throw Error("Run with Firebase emulators:exec");
initializeApp({ projectId: "demo-teumgot" });
const db = getFirestore();
let u, v, start;
const key = () => require("node:crypto").randomUUID();
async function user() {
  const res = await fetch(
    "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: `${key()}@example.com`,
        password: "testpass123",
        returnSecureToken: true,
      }),
    },
  );
  const x = await res.json();
  a.ok(x.idToken, JSON.stringify(x));
  await db
    .doc(`users/${x.localId}`)
    .set({
      nickname: "테스트",
      termsAccepted: true,
      termsVersion: "1.0",
      privacyVersion: "1.0",
    });
  return { uid: x.localId, token: x.idToken };
}
async function call(name, data, user = u) {
  const r = await fetch(
    `http://127.0.0.1:5001/demo-teumgot/asia-northeast3/${name}`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(user ? { Authorization: `Bearer ${user.token}` } : {}),
      },
      body: JSON.stringify({ data }),
    },
  );
  const x = await r.json();
  if (x.error) {
    const e = new Error(x.error.message);
    e.code = x.error.details?.code;
    throw e;
  }
  return x.result;
}
const body = (s = start) => ({
  spaceId: "integration-room",
  startAt: s,
  endAt: s + 3600000,
  people: 4,
  requestKey: key(),
});
before(async () => {
  u = await user();
  v = await user();
  start =
    Math.floor((Date.now() + 3 * 86400000) / 86400000) * 86400000 + 5 * 3600000;
  await db
    .doc("spaces/integration-room")
    .set({
      name: "테스트 공간",
      capacity: 6,
      active: true,
      hostId: "host-test",
      address: "진주",
      openMinute: 540,
      closeMinute: 1320,
      slotPrice: 5000,
    });
});
test("guest browsing works but guest hold is denied", async () => {
  const date = new Date(start + 9 * 3600000).toISOString().slice(0, 10);
  a.ok(
    (await call("getAvailability", { spaceId: "integration-room", date }, null))
      .serverNow,
  );
  await a.rejects(call("createHold", body(), null), {
    code: "UNAUTHENTICATED",
  });
});
test("two concurrent customers, exactly one hold; adjacent allowed; idempotency strict", async () => {
  const b = body();
  const results = await Promise.allSettled([
    call("createHold", b, u),
    call("createHold", { ...b, requestKey: key() }, v),
  ]);
  a.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  a.equal(
    results.find((r) => r.status === "rejected").reason.code,
    "SLOT_TAKEN",
  );
  const index = results.findIndex((r) => r.status === "fulfilled");
  const owner = index === 0 ? u : v;
  const h = results[index].value;
  if (index === 0) {
    a.equal((await call("createHold", b, u)).id, h.id);
    await a.rejects(call("createHold", { ...b, people: 3 }, u), {
      code: "REQUEST_KEY_REUSED",
    });
  }
  const adjacent = await call("createHold", body(start + 3600000), owner);
  a.ok(adjacent.id);
  await call("releaseHold", { holdId: adjacent.id, requestKey: key() }, owner);
  const confirm = {
    holdId: h.id,
    paymentMode: "success",
    policyAgreed: true,
    requestKey: key(),
  };
  const rr = await Promise.all([
    call("confirmBooking", confirm, owner),
    call("confirmBooking", confirm, owner),
    call("confirmBooking", confirm, owner),
  ]);
  a.equal(new Set(rr.map((x) => x.bookingId)).size, 1);
  const docs = await db
    .collection("bookings")
    .where("holdId", "==", h.id)
    .get();
  a.equal(docs.size, 1);
  const foreign = index === 0 ? v : u;
  await a.rejects(
    call(
      "cancelBooking",
      { bookingId: h.id, expectedVersion: 1, requestKey: key() },
      foreign,
    ),
    { code: "FORBIDDEN" },
  );
  await call(
    "cancelBooking",
    { bookingId: h.id, expectedVersion: 1, requestKey: key() },
    owner,
  );
  const slot = await call("createHold", body(), foreign);
  a.ok(slot.id);
  await call("releaseHold", { holdId: slot.id, requestKey: key() }, foreign);
  const notifications = await db
    .collection(`users/${owner.uid}/notifications`)
    .get();
  a.equal(notifications.size, 2);
});
test("expired hold cannot confirm and does not block replacement", async () => {
  const h = await call("createHold", body(start + 2 * 3600000));
  await db.doc(`holds/${h.id}`).update({ expiresAt: Date.now() - 1 });
  const date = new Date(h.startAt + 9 * 3600000).toISOString().slice(0, 10);
  const inv = db.doc(`inventory/integration-room_${date}`);
  const slots = (await inv.get()).data().slots;
  for (const k of h.keys) slots[k].expiresAt = Date.now() - 1;
  await inv.set({ slots });
  await a.rejects(
    call("confirmBooking", {
      holdId: h.id,
      paymentMode: "success",
      policyAgreed: true,
      requestKey: key(),
    }),
    { code: "HOLD_EXPIRED" },
  );
  a.ok((await call("createHold", body(h.startAt), v)).id);
});
test("failed test payment releases inventory without a booking", async () => {
  const h = await call("createHold", body(start + 4 * 3600000));
  const r = await call("confirmBooking", {
    holdId: h.id,
    paymentMode: "failure",
    policyAgreed: true,
    requestKey: key(),
  });
  a.equal(r.status, "payment_failed");
  a.equal((await db.doc(`bookings/${h.id}`).get()).exists, false);
  a.ok((await call("createHold", body(h.startAt), v)).id);
});
test("Firestore rules deny direct transaction writes and foreign reads", async () => {
  const url =
    "http://127.0.0.1:8080/v1/projects/demo-teumgot/databases/(default)/documents/";
  const headers = {
    Authorization: `Bearer ${v.token}`,
    "Content-Type": "application/json",
  };
  const write = await fetch(url + "bookings/forged", {
    method: "PATCH",
    headers,
    body: JSON.stringify({ fields: { userId: { stringValue: v.uid } } }),
  });
  a.equal(write.status, 403);
  const p = await db
    .doc("bookings/private-test")
    .set({ userId: u.uid, hostId: "host-test" });
  const read = await fetch(url + "bookings/private-test", { headers });
  a.equal(read.status, 403);
  const role = await fetch(url + `users/${v.uid}`, {
    method: "PATCH",
    headers,
    body: JSON.stringify({ fields: { host: { booleanValue: true } } }),
  });
  a.equal(role.status, 403);
});
