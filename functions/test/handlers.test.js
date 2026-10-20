// Handler contract tests use a serial in-memory transaction adapter. The real
// Firestore emulator suite is separate; these tests do not verify Firestore rules.
const { test } = require("node:test");
const a = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");
const clone = (x) => (x === undefined ? undefined : structuredClone(x));
function fixture() {
  const store = new Map();
  let queue = Promise.resolve();
  const ref = (p) => ({ path: p, get: async () => snapshot(p) });
  const snapshot = (p) => ({
    exists: store.has(p),
    data: () => clone(store.get(p)),
  });
  const db = {
    doc: ref,
    collection: (p) => ({
      where: (field, op, value) => ({
        limit: () => ({
          get: async () => ({
            docs: [...store]
              .filter(([k, v]) => k.startsWith(p + "/") && v[field] === value)
              .map(([k]) => snapshot(k)),
          }),
        }),
      }),
    }),
    runTransaction: (fn) => {
      const run = queue.then(async () => {
        const writes = [];
        let didWrite = false;
        const tx = {
          get: async (r) => {
            a.equal(
              didWrite,
              false,
              "Firestore requires all reads before writes",
            );
            return snapshot(r.path);
          },
          create: (r, d) => {
            didWrite = true;
            writes.push(() => {
              a.equal(
                store.has(r.path),
                false,
                "create requires absent document",
              );
              store.set(r.path, clone(d));
            });
          },
          set: (r, d) => {
            didWrite = true;
            writes.push(() => store.set(r.path, clone(d)));
          },
          update: (r, d) => {
            didWrite = true;
            writes.push(() => {
              a.ok(store.has(r.path));
              store.set(r.path, { ...store.get(r.path), ...clone(d) });
            });
          },
        };
        const result = await fn(tx);
        writes.forEach((f) => f());
        return result;
      });
      queue = run.catch(() => {});
      return run;
    },
  };
  class HttpsError extends Error {
    constructor(code, message, details) {
      super(message);
      this.details = details;
      this.code = code;
    }
  }
  const exports = {};
  const filename = path.join(__dirname, "../src/index.js");
  vm.runInNewContext(fs.readFileSync(filename, "utf8"), {
    exports,
    console,
    setTimeout,
    require: (n) =>
      n === "firebase-functions/v2/https"
        ? { onCall: (_, fn) => fn, HttpsError }
        : n === "firebase-admin/app"
          ? { initializeApp: () => {} }
          : n === "firebase-admin/firestore"
            ? { getFirestore: () => db }
            : n === "./domain"
              ? require("../src/domain")
              : require(n),
  });
  const start =
    Math.floor((Date.now() + 3 * 86400000) / 86400000) * 86400000 + 5 * 3600000;
  store.set("spaces/room", {
    name: "포커스",
    capacity: 6,
    slotPrice: 5000,
    active: true,
    hostId: "host",
    address: "진주",
    openMinute: 540,
    closeMinute: 1320,
  });
  for (const uid of ["u", "v"])
    store.set(`users/${uid}`, { termsAccepted: true });
  let n = 0;
  const call = (name, data, uid = "u", host = false) =>
    exports[name]({ data, auth: uid ? { uid, token: { host } } : undefined });
  const body = (at = start) => ({
    spaceId: "room",
    startAt: at,
    endAt: at + 3600000,
    people: 4,
    requestKey: "key-" + ++n,
  });
  return { store, call, body, start };
}
test("real hold handler serializes competitors, permits adjacency and fingerprints requests", async () => {
  const f = fixture(),
    body = f.body();
  const results = await Promise.allSettled([
    f.call("createHold", body),
    f.call("createHold", { ...body, requestKey: "other" }, "v"),
  ]);
  a.equal(results[0].status, "fulfilled");
  a.equal(results[1].reason.details.code, "SLOT_TAKEN");
  const h = results[0].value;
  a.equal((await f.call("createHold", body)).id, h.id);
  await a.rejects(
    f.call("createHold", { ...body, people: 2 }),
    (e) => e.details.code === "REQUEST_KEY_REUSED",
  );
  a.ok((await f.call("createHold", f.body(f.start + 3600000), "v")).id);
});
test("duplicate confirmation produces one booking and one inbox event per recipient", async () => {
  const f = fixture(),
    h = await f.call("createHold", f.body());
  const req = {
    holdId: h.id,
    paymentMode: "success",
    policyAgreed: true,
    requestKey: "confirm",
  };
  const result = await Promise.all([
    f.call("confirmBooking", req),
    f.call("confirmBooking", req),
    f.call("confirmBooking", req),
  ]);
  a.equal(new Set(result.map((x) => x.bookingId)).size, 1);
  a.equal(
    [...f.store.keys()].filter((k) => k.startsWith("bookings/")).length,
    1,
  );
  a.equal(
    [...f.store.keys()].filter((k) => k.startsWith("users/u/notifications/"))
      .length,
    1,
  );
  await a.rejects(
    f.call(
      "cancelBooking",
      { bookingId: h.id, requestKey: "cancel-v", expectedVersion: 1 },
      "v",
    ),
    (e) => e.details.code === "FORBIDDEN",
  );
  await f.call("cancelBooking", {
    bookingId: h.id,
    requestKey: "cancel",
    expectedVersion: 1,
  });
  a.equal(f.store.get(`bookings/${h.id}`).status, "cancelled");
  a.ok((await f.call("createHold", f.body(), "v")).id);
});
test("expired hold cannot confirm; cleanup is unnecessary for a replacement", async () => {
  const f = fixture(),
    h = await f.call("createHold", f.body());
  f.store.get(`holds/${h.id}`).expiresAt = Date.now() - 1;
  for (const [k, v] of f.store)
    if (k.startsWith("inventory/"))
      Object.values(v.slots).forEach((s) => (s.expiresAt = Date.now() - 1));
  await a.rejects(
    f.call("confirmBooking", {
      holdId: h.id,
      paymentMode: "success",
      policyAgreed: true,
      requestKey: "confirm",
    }),
    (e) => e.details.code === "HOLD_EXPIRED",
  );
  a.ok((await f.call("createHold", f.body(), "v")).id);
});
test("failure releases slots; delayed success commits a recoverable result", async () => {
  const f = fixture(),
    h = await f.call("createHold", f.body());
  const failed = await f.call("confirmBooking", {
    holdId: h.id,
    paymentMode: "failure",
    policyAgreed: true,
    requestKey: "failure",
  });
  a.equal(failed.status, "payment_failed");
  a.equal(f.store.has(`bookings/${h.id}`), false);
  const next = await f.call("createHold", f.body());
  const req = {
    holdId: next.id,
    paymentMode: "delay",
    policyAgreed: true,
    requestKey: "delayed",
  };
  const result = await f.call("confirmBooking", req);
  a.equal(result.status, "confirmed");
  a.equal(
    (await f.call("confirmBooking", { ...req, paymentMode: "delay" }))
      .bookingId,
    result.bookingId,
  );
});
test("server ignores client role and enforces ownership, consent and capacity", async () => {
  const f = fixture();
  await a.rejects(
    f.call("createHold", f.body(), null),
    (e) => e.details.code === "UNAUTHENTICATED",
  );
  await a.rejects(
    f.call("createHold", { ...f.body(), people: 7 }),
    (e) => e.details.code === "INVALID_SELECTION",
  );
  await a.rejects(
    f.call("setBlock", { ...f.body(), blocked: true }, "u", true),
    (e) => e.details.code === "FORBIDDEN",
  );
  await a.rejects(
    f.call("getHostBookings", {}, "u"),
    (e) => e.details.code === "FORBIDDEN",
  );
  f.store.get("users/u").termsAccepted = false;
  await a.rejects(
    f.call("createHold", f.body()),
    (e) => e.details.code === "TERMS_REQUIRED",
  );
});
