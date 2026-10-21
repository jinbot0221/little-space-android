const { test, before, beforeEach, after } = require("node:test");
const a = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const {
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
  writeBatch,
  runTransaction,
  Timestamp,
  serverTimestamp,
  collection,
  query,
  where,
  limit,
} = require("firebase/firestore");
let env, day;
const at = (ms) => Timestamp.fromMillis(ms),
  D = 86400000,
  SLOT = 1800000;
const ref = (db, p) => doc(db, p);
const db = (uid = "u", claims = {}) =>
  env.authenticatedContext(uid, claims).firestore();
async function admin(fn) {
  return env.withSecurityRulesDisabled((c) => fn(c.firestore()));
}
const read = async (db, p) => (await getDoc(ref(db, p))).data();
function change(inv, values, action, id, start, count) {
  return {
    ...inv,
    slots: values,
    version: inv.version + 1,
    updatedAt: serverTimestamp(),
    lastAction: action,
    lastId: id,
    lastStartSlot: start,
    lastCount: count,
  };
}
function inbox(batch, client, b) {
  for (const user of new Set([b.userId, b.hostId]))
    batch.set(ref(client, `users/${user}/notifications/${b.id}_${b.version}`), {
      bookingId: b.id,
      version: b.version,
      status: b.status,
      createdAt: serverTimestamp(),
      read: false,
      title:
        b.status === "confirmed"
          ? "공간 예약이 확정됐어요"
          : "공간 예약이 취소됐어요",
      body: b.spaceName + " · 예약내역에서 확인해 주세요.",
    });
}
async function hold(
  client,
  id = "h1",
  start = 18,
  count = 4,
  patch = {},
  inventoryPatch,
) {
  return runTransaction(client, async (tx) => {
    const ir = ref(client, "sparkInventory/room_day");
    const inv = (await tx.get(ir)).data();
    const old = await tx.get(ref(client, `sparkHolds/${id}`));
    if (old.exists()) return old.data();
    const h = {
      id,
      userId: "u",
      spaceId: "room",
      inventoryId: "room_day",
      date: "fixture",
      startSlot: start,
      slotCount: count,
      startAt: at(day + start * SLOT),
      endAt: at(day + (start + count) * SLOT),
      people: 4,
      amount: 5000 * count,
      slotPrice: 5000,
      spaceName: "테스트 공간",
      hostId: "host",
      address: "진주",
      requestKey: id,
      status: "active",
      version: 1,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      ...patch,
    };
    const slots = { ...inv.slots };
    for (let n = start; n < start + count; n++)
      slots[n] = { kind: "hold", id, stamp: serverTimestamp() };
    tx.set(ref(client, `sparkHolds/${id}`), h);
    tx.set(
      ir,
      inventoryPatch
        ? inventoryPatch(change(inv, slots, "hold", id, start, count))
        : change(inv, slots, "hold", id, start, count),
    );
    return h;
  });
}
async function confirm(
  client,
  id = "h1",
  key = "confirm1",
  patch = {},
  options = {},
) {
  try {
    return await runTransaction(client, async (tx) => {
      const br = ref(client, `sparkBookings/${id}`),
        old = await tx.get(br);
      if (old.exists()) return old.data();
      const h = (await tx.get(ref(client, `sparkHolds/${id}`))).data();
      const ir = ref(client, `sparkInventory/${h.inventoryId}`),
        inv = (await tx.get(ir)).data();
      const b = {
        ...h,
        holdId: id,
        requestKey: key,
        testMode: "success",
        paymentMode: "test",
        status: "confirmed",
        version: 1,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
        ...patch,
      };
      const slots = { ...inv.slots };
      for (let n = h.startSlot; n < h.startSlot + h.slotCount; n++)
        slots[n] = { kind: "booking", id };
      tx.set(br, b);
      tx.update(ref(client, `sparkHolds/${id}`), {
        status: "confirmed",
        version: h.version + 1,
        updatedAt: serverTimestamp(),
      });
      if (!options.noInventory)
        tx.set(ir, change(inv, slots, "confirm", id, h.startSlot, h.slotCount));
      if (!options.noInbox) inbox(tx, client, b);
      return b;
    });
  } catch (e) {
    const snapshot = await getDoc(ref(client, `sparkBookings/${id}`));
    if (
      snapshot.exists() &&
      snapshot.data().requestKey === key &&
      Object.entries(patch).every(([k, v]) => snapshot.data()[k] === v)
    )
      return snapshot.data();
    throw e;
  }
}
async function release(client, id = "h1", patch = {}) {
  const h = await read(client, `sparkHolds/${id}`),
    inv = await read(client, `sparkInventory/${h.inventoryId}`),
    slots = { ...inv.slots };
  for (let n = h.startSlot; n < h.startSlot + h.slotCount; n++)
    if (slots[n].kind === "hold" && slots[n].id === id)
      slots[n] = { kind: "free" };
  const batch = writeBatch(client);
  batch.update(ref(client, `sparkHolds/${id}`), {
    status: "released",
    version: h.version + 1,
    updatedAt: serverTimestamp(),
    releaseRequestKey: "release1",
    releaseReason: "user",
    ...patch,
  });
  batch.set(
    ref(client, `sparkInventory/${h.inventoryId}`),
    change(inv, slots, "release", id, h.startSlot, h.slotCount),
  );
  return batch.commit();
}
async function cancel(client, id = "h1", patch = {}, noInventory = false) {
  const b = await read(client, `sparkBookings/${id}`),
    inv = await read(client, `sparkInventory/${b.inventoryId}`),
    slots = { ...inv.slots };
  for (let n = b.startSlot; n < b.startSlot + b.slotCount; n++)
    slots[n] = { kind: "free" };
  const next = {
    ...b,
    status: "cancelled",
    version: b.version + 1,
    updatedAt: serverTimestamp(),
    refundAmount: b.amount,
    cancelRequestKey: "cancel1",
    ...patch,
  };
  const batch = writeBatch(client);
  batch.set(ref(client, `sparkBookings/${id}`), next);
  if (!noInventory)
    batch.set(
      ref(client, `sparkInventory/${b.inventoryId}`),
      change(inv, slots, "cancel", id, b.startSlot, b.slotCount),
    );
  inbox(batch, client, next);
  return batch.commit();
}
async function block(client, id = "op1", blocked = true) {
  const inv = await read(client, "sparkInventory/room_day"),
    slots = { ...inv.slots };
  for (let n = 18; n < 22; n++)
    slots[n] = blocked ? { kind: "block", id: "host" } : { kind: "free" };
  const batch = writeBatch(client);
  batch.set(ref(client, `sparkOperations/${id}`), {
    id,
    userId: "host",
    requestKey: id,
    spaceId: "room",
    inventoryId: "room_day",
    date: "fixture",
    startSlot: 18,
    slotCount: 4,
    startAt: at(day + 18 * SLOT),
    endAt: at(day + 22 * SLOT),
    blocked,
    createdAt: serverTimestamp(),
  });
  batch.set(
    ref(client, "sparkInventory/room_day"),
    change(inv, slots, blocked ? "block" : "unblock", id, 18, 4),
  );
  return batch.commit();
}
before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-teumgot",
    firestore: {
      host: "127.0.0.1",
      port: 8080,
      rules: fs.readFileSync(
        path.join(__dirname, "../../firestore.rules"),
        "utf8",
      ),
    },
  });
});
beforeEach(async () => {
  await env.clearFirestore();
  day = Math.floor((Date.now() + 9 * 3600000) / D) * D - 9 * 3600000 + 3 * D;
  await admin(async (client) => {
    await setDoc(ref(client, "spaces/room"), {
      id: "room",
      active: true,
      schemaVersion: 2,
      pricingMode: "flat",
      slotPrice: 5000,
      name: "테스트 공간",
      hostId: "host",
      address: "진주",
      capacity: 6,
      openMinute: 540,
      closeMinute: 1320,
    });
    await setDoc(ref(client, "sparkInventory/room_day"), {
      id: "room_day",
      spaceId: "room",
      date: "fixture",
      dayStart: at(day),
      slots: Object.fromEntries(
        Array.from({ length: 48 }, (_, i) => [String(i), { kind: "free" }]),
      ),
      schemaVersion: 2,
      version: 0,
      updatedAt: at(Date.now()),
      lastAction: "seed",
      lastId: "seed",
      lastStartSlot: 0,
      lastCount: 0,
    });
    for (const uid of ["u", "v", "host"])
      await setDoc(ref(client, `users/${uid}`), {
        nickname: "테스트",
        termsAccepted: true,
        termsVersion: "1.0",
        privacyVersion: "1.0",
      });
  });
});
after(async () => env?.cleanup());
test("guest can browse but cannot create holds", async () => {
  const guest = env.unauthenticatedContext().firestore();
  await assertSucceeds(getDoc(ref(guest, "sparkInventory/room_day")));
  await assertFails(hold(guest));
});
test("maximum 8h reservation validates 16 slots within rule access/expression limits", async () => {
  const c = db();
  await assertSucceeds(hold(c, "h1", 18, 16));
  await assertSucceeds(confirm(c));
  const b = await read(c, "sparkBookings/h1");
  a.equal(b.amount, 80000);
  await assertSucceeds(cancel(c));
});
test("two concurrent users, exactly one conflicting hold succeeds", async () => {
  const rr = await Promise.allSettled([
    hold(db(), "h1"),
    hold(db("v"), "h2", 18, 4, { userId: "v" }),
  ]);
  a.equal(rr.filter((r) => r.status === "fulfilled").length, 1);
  a.equal(rr.filter((r) => r.status === "rejected").length, 1);
});
test("adjacent half-open reservations are permitted", async () => {
  await assertSucceeds(hold(db(), "h1", 18, 4));
  await assertSucceeds(hold(db("v"), "h2", 22, 2, { userId: "v" }));
});
test("same request key replays once and changing payload cannot overwrite the hold", async () => {
  const c = db();
  await hold(c);
  await hold(c);
  await assertFails(updateDoc(ref(c, "sparkHolds/h1"), { amount: 1 }));
  const q = await getDocs(
    query(
      collection(c, "sparkBookings"),
      where("userId", "==", "u"),
      limit(30),
    ),
  );
  a.equal(q.size, 0);
});
for (const [name, patch] of [
  ["forged price", { amount: 1 }],
  ["forged owner", { userId: "v" }],
  ["over capacity", { people: 7 }],
  ["forged title", { spaceName: "forged" }],
  ["forged host", { hostId: "u" }],
  ["misaligned time", { startAt: at(0) }],
  ["client timestamp", { createdAt: at(0) }],
])
  test("hold rejects " + name, async () =>
    assertFails(hold(db(), "h1", 18, 4, patch)),
  );
test("invalid 30-minute or >8h intervals are rejected", async () => {
  await assertFails(hold(db(), "h1", 18, 1));
  await assertFails(hold(db(), "h2", 18, 17));
});
test("slot mutation outside the declared interval is rejected", async () => {
  await assertFails(
    hold(db(), "h1", 18, 4, {}, (d) => ({
      ...d,
      slots: {
        ...d.slots,
        30: { kind: "hold", id: "h1", stamp: serverTimestamp() },
      },
    })),
  );
});
test("unused 16-slot tail cannot be mutated", async () => {
  await assertFails(
    hold(db(), "h1", 18, 2, {}, (d) => ({
      ...d,
      slots: {
        ...d.slots,
        21: { kind: "hold", id: "h1", stamp: serverTimestamp() },
      },
    })),
  );
});
test("hold alone and inventory alone cannot be written", async () => {
  const c = db();
  await assertFails(
    updateDoc(ref(c, "sparkInventory/room_day"), {
      version: 1,
      updatedAt: serverTimestamp(),
      lastAction: "hold",
      lastId: "h1",
      lastStartSlot: 18,
      lastCount: 4,
    }),
  );
});
test("expired holds cannot confirm and replacement does not need cleanup", async () => {
  const c = db();
  await hold(c);
  await admin(async (a) => {
    const h = await read(a, "sparkHolds/h1"),
      inv = await read(a, "sparkInventory/room_day"),
      old = at(Date.now() - 300001);
    h.createdAt = old;
    for (let n = 18; n < 22; n++) inv.slots[n].stamp = old;
    await setDoc(ref(a, "sparkHolds/h1"), h);
    await setDoc(ref(a, "sparkInventory/room_day"), inv);
  });
  await assertFails(confirm(c));
  await assertSucceeds(hold(db("v"), "h2", 18, 4, { userId: "v" }));
  await assertSucceeds(release(c));
  a.equal((await read(db("v"), "sparkHolds/h2")).status, "active");
});
test("confirm requires inventory, inbox and price snapshot integrity", async () => {
  const c = db();
  await hold(c);
  await assertFails(confirm(c, "h1", "c1", {}, { noInventory: true }));
  await assertFails(confirm(c, "h1", "c1", {}, { noInbox: true }));
  await assertFails(confirm(c, "h1", "c1", { amount: 1 }));
  await assertSucceeds(confirm(c));
});
test("repeated confirmation commits one booking and inbox event", async () => {
  const c = db();
  await hold(c);
  await Promise.all([confirm(c), confirm(c), confirm(c)]);
  a.equal(
    (
      await getDocs(
        query(
          collection(c, "sparkBookings"),
          where("userId", "==", "u"),
          limit(30),
        ),
      )
    ).size,
    1,
  );
  a.equal((await getDocs(collection(c, "users/u/notifications"))).size, 1);
});
test("foreign customer cannot read, release or cancel a booking", async () => {
  const c = db(),
    v = db("v");
  await hold(c);
  await assertFails(getDoc(ref(v, "sparkHolds/h1")));
  await assertFails(release(v));
  await confirm(c);
  await assertFails(getDoc(ref(v, "sparkBookings/h1")));
  await assertFails(cancel(v));
});
test("cancellation must return slots and exact refund atomically", async () => {
  const c = db();
  await hold(c);
  await confirm(c);
  await assertFails(cancel(c, "h1", {}, true));
  await assertFails(cancel(c, "h1", { refundAmount: 1 }));
  await assertSucceeds(cancel(c));
  a.equal((await read(c, "sparkInventory/room_day")).slots["18"].kind, "free");
  a.equal((await getDocs(collection(c, "users/u/notifications"))).size, 2);
});
test("server cancellation deadline rejects within 24h", async () => {
  const c = db();
  await hold(c);
  await confirm(c);
  await admin((a) =>
    updateDoc(ref(a, "sparkBookings/h1"), {
      startAt: at(Date.now() + D - 60000),
    }),
  );
  await assertFails(cancel(c));
});
test("owner host can block/unblock but customers cannot", async () => {
  await assertFails(block(db()));
  await assertFails(block(db("other", { host: true })));
  await assertSucceeds(block(db("host", { host: true })));
  await assertFails(hold(db()));
  await assertSucceeds(block(db("host", { host: true }), "op2", false));
  await assertSucceeds(hold(db()));
});
test("host cannot block or remove booked inventory", async () => {
  const c = db();
  await hold(c);
  await confirm(c);
  await assertFails(block(db("host", { host: true })));
  await assertFails(block(db("host", { host: true }), "op2", false));
});
test("clock cannot be forged and roles cannot be self elevated", async () => {
  const c = db();
  await assertFails(
    setDoc(ref(c, "users/u/clock/current"), { at: at(Date.now() + D) }),
  );
  await assertSucceeds(
    setDoc(ref(c, "users/u/clock/current"), { at: serverTimestamp() }),
  );
  await assertFails(updateDoc(ref(c, "users/u"), { host: true }));
});
test("only completed owned booking permits one 10-500 char review", async () => {
  const c = db();
  await hold(c);
  await confirm(c);
  const r = {
    bookingId: "h1",
    userId: "u",
    spaceId: "room",
    rating: 5,
    content: "정말 집중하기 좋은 공간이에요.",
    requestKey: "review1",
    createdAt: serverTimestamp(),
  };
  await assertFails(setDoc(ref(c, "sparkReviews/h1"), r));
  await admin((a) =>
    updateDoc(ref(a, "sparkBookings/h1"), { endAt: at(Date.now() - 1000) }),
  );
  await assertFails(
    setDoc(ref(c, "sparkReviews/h1"), { ...r, content: "          " }),
  );
  await assertSucceeds(setDoc(ref(c, "sparkReviews/h1"), r));
  await assertFails(
    setDoc(ref(c, "sparkReviews/h1"), {
      ...r,
      content: "두 번째 후기는 저장하지 못해요.",
    }),
  );
});
test("customer cannot forge host inbox or update notification content", async () => {
  const c = db();
  await hold(c);
  await confirm(c);
  await assertFails(
    setDoc(ref(c, "users/host/notifications/fake"), {
      bookingId: "h1",
      title: "spam",
    }),
  );
  await assertFails(
    updateDoc(ref(c, "users/u/notifications/h1_1"), { title: "spam" }),
  );
  await assertSucceeds(
    updateDoc(ref(c, "users/u/notifications/h1_1"), { read: true }),
  );
});

test("catalog refresh preserves existing inventory and committed allocations", async () => {
  const { initializeApp } = require("firebase-admin/app");
  const { getFirestore } = require("firebase-admin/firestore");
  const { ensureCatalog } = require("../scripts/catalog");
  const adb = getFirestore(
    initializeApp({ projectId: "demo-teumgot" }, "catalog-maintenance"),
  );
  await ensureCatalog(adb, "host", 2);
  const docs = await adb
    .collection("sparkInventory")
    .where("spaceId", "==", "focus-a")
    .get();
  a.equal(docs.size, 2);
  const selected = docs.docs[0].ref;
  const state = (await selected.get()).data();
  state.slots["18"] = { kind: "booking", id: "existing-transaction" };
  state.version = 7;
  await selected.set(state);
  await ensureCatalog(adb, "host", 2);
  a.deepEqual((await selected.get()).data(), state);
});

test("missing operating day is readable as absent, not permission denied", async () => {
  const guest = env.unauthenticatedContext().firestore();
  const missing = await assertSucceeds(
    getDoc(ref(guest, "sparkInventory/room_missing")),
  );
  a.equal(missing.exists(), false);
});
test("customer cannot extend a hold by resetting its server creation time", async () => {
  const c = db();
  await hold(c);
  await assertFails(
    updateDoc(ref(c, "sparkHolds/h1"), {
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
      version: 2,
    }),
  );
});
