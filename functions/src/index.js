"use strict";
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { createHash } = require("node:crypto");
const D = require("./domain");
initializeApp();
const db = getFirestore();
const hash = (s) => createHash("sha256").update(s).digest("hex");
const canonical = (v) => JSON.stringify(v, Object.keys(v).sort());
const id = (v) =>
  typeof v === "string" && /^[\w-]{1,128}$/.test(v) ? v : D.fail("INVALID_ID");
const uidOf = (r) => r.auth?.uid || D.fail("UNAUTHENTICATED");
const wrap = (fn) =>
  onCall({ region: "asia-northeast3", maxInstances: 10 }, async (r) => {
    try {
      return await fn(r);
    } catch (e) {
      if (!(e instanceof D.DomainError)) {
        console.error("operation failed", e);
        throw new HttpsError(
          "internal",
          "요청을 처리하지 못했습니다. 같은 요청으로 재시도해 주세요.",
        );
      }
      const code =
        e.code === "UNAUTHENTICATED"
          ? "unauthenticated"
          : e.code === "FORBIDDEN"
            ? "permission-denied"
            : e.code === "NOT_FOUND"
              ? "not-found"
              : [
                    "SLOT_TAKEN",
                    "HOLD_EXPIRED",
                    "STATE_CONFLICT",
                    "REQUEST_KEY_REUSED",
                    "CANCEL_DEADLINE",
                  ].includes(e.code)
                ? "failed-precondition"
                : "invalid-argument";
      throw new HttpsError(code, e.code, {
        code: e.code,
        requestId: hash(String(Date.now())).slice(0, 12),
      });
    }
  });
// Every mutation commits its request fingerprint and result atomically with inventory.
async function mutate(r, op, body) {
  const uid = uidOf(r),
    data = r.data || {},
    requestKey = id(data.requestKey);
  const ref = db.doc(`requests/${hash(uid + ":" + op + ":" + requestKey)}`);
  const fingerprint = hash(canonical(data));
  return db.runTransaction(async (tx) => {
    const previous = await tx.get(ref);
    if (previous.exists) {
      if (previous.data().fingerprint !== fingerprint)
        D.fail("REQUEST_KEY_REUSED");
      return previous.data().result;
    }
    const now = Date.now();
    const result = await body(tx, uid, data, now);
    tx.create(ref, { uid, op, fingerprint, result, createdAt: now });
    return result;
  });
}
const inventoryRef = (spaceId, startAt) =>
  db.doc(
    `inventory/${spaceId}_${new Date(startAt + 9 * 3600000).toISOString().slice(0, 10)}`,
  );
function event(tx, booking, kind, now) {
  const eventId = `${booking.id}_${booking.version}_${kind}`;
  for (const uid of new Set([booking.userId, booking.hostId])) {
    const notificationId = hash(eventId + uid);
    const title =
      kind === "confirmed"
        ? "공간 예약이 확정됐어요"
        : "공간 예약이 취소됐어요";
    tx.create(db.doc(`users/${uid}/notifications/${notificationId}`), {
      eventId,
      title,
      body: `${booking.spaceName} · 예약내역에서 확인해 주세요.`,
      bookingId: booking.id,
      createdAt: now,
      read: false,
      expiresAt: now + 30 * 86400000,
    });
  }
  tx.create(db.doc(`outbox/${eventId}`), {
    bookingId: booking.id,
    kind,
    version: booking.version,
    createdAt: now,
    status: "pending",
  });
}
exports.getAvailability = wrap(async (r) => {
  const { spaceId, date } = r.data || {};
  id(spaceId);
  if (typeof date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(date))
    D.fail("INVALID_DATE");
  const space = await db.doc(`spaces/${spaceId}`).get();
  if (!space.exists || !space.data().active) D.fail("NOT_FOUND");
  const inv = await db.doc(`inventory/${spaceId}_${date}`).get();
  const now = Date.now();
  const busy = Object.entries(inv.data()?.slots || {})
    .filter(([, v]) => D.occupied(v, now))
    .map(([k]) => Number(k));
  return { busy, serverNow: now };
});
exports.createHold = wrap((r) =>
  mutate(r, "hold", async (tx, uid, d, now) => {
    const spaceId = id(d.spaceId),
      spaceRef = db.doc(`spaces/${spaceId}`);
    const [spaceDoc, profile] = await Promise.all([
      tx.get(spaceRef),
      tx.get(db.doc(`users/${uid}`)),
    ]);
    if (!profile.data()?.termsAccepted) D.fail("TERMS_REQUIRED");
    if (!spaceDoc.exists) D.fail("NOT_FOUND");
    const space = spaceDoc.data();
    const { keys, amount } = D.interval(
      d.startAt,
      d.endAt,
      d.people,
      space,
      now,
    );
    const invRef = inventoryRef(spaceId, d.startAt),
      inv = await tx.get(invRef),
      slots = inv.data()?.slots || {};
    if (keys.some((k) => D.occupied(slots[k], now))) D.fail("SLOT_TAKEN");
    const holdId = hash(uid + ":" + d.requestKey),
      expiresAt = now + D.HOLD_TTL;
    for (const k of keys) slots[k] = { kind: "hold", id: holdId, expiresAt };
    const hold = {
      id: holdId,
      userId: uid,
      spaceId,
      spaceName: space.name,
      hostId: space.hostId,
      address: space.address,
      startAt: d.startAt,
      endAt: d.endAt,
      people: d.people,
      amount,
      slotPrices: keys.map(
        (k) =>
          space.slotPrices?.[(Number(k) / 60000 + 540) % 1440] ??
          space.slotPrice,
      ),
      keys,
      expiresAt,
      status: "active",
      createdAt: now,
      updatedAt: now,
      version: 1,
    };
    tx.set(invRef, { slots });
    tx.create(db.doc(`holds/${holdId}`), hold);
    return { ...hold, serverNow: now };
  }),
);
exports.releaseHold = wrap((r) =>
  mutate(r, "release", async (tx, uid, d, now) => {
    const ref = db.doc(`holds/${id(d.holdId)}`),
      doc = await tx.get(ref);
    if (!doc.exists) D.fail("NOT_FOUND");
    const h = doc.data();
    if (h.userId !== uid) D.fail("FORBIDDEN");
    const invRef = inventoryRef(h.spaceId, h.startAt),
      inv = await tx.get(invRef),
      slots = inv.data()?.slots || {};
    if (h.status === "active") {
      for (const k of h.keys)
        if (slots[k]?.id === h.id && slots[k]?.kind === "hold") delete slots[k];
      tx.set(invRef, { slots });
      tx.update(ref, { status: "released", updatedAt: now });
    }
    return { released: true };
  }),
);
exports.confirmBooking = wrap(async (r) => {
  const result = await mutate(r, "confirm", async (tx, uid, d, now) => {
    if (
      d.policyAgreed !== true ||
      !["success", "failure", "delay"].includes(d.paymentMode)
    )
      D.fail("CONSENT_REQUIRED");
    const ref = db.doc(`holds/${id(d.holdId)}`),
      doc = await tx.get(ref);
    if (!doc.exists) D.fail("NOT_FOUND");
    const h = doc.data();
    if (h.userId !== uid) D.fail("FORBIDDEN");
    if (h.status !== "active" || h.expiresAt <= now) D.fail("HOLD_EXPIRED");
    const invRef = inventoryRef(h.spaceId, h.startAt),
      inv = await tx.get(invRef),
      slots = inv.data()?.slots || {};
    if (h.keys.some((k) => slots[k]?.id !== h.id || slots[k]?.kind !== "hold"))
      D.fail("SLOT_TAKEN");
    if (d.paymentMode === "failure") {
      for (const k of h.keys) delete slots[k];
      tx.set(invRef, { slots });
      tx.update(ref, { status: "released", updatedAt: now });
      return { status: "payment_failed" };
    }
    const bookingId = h.id,
      booking = {
        ...h,
        id: bookingId,
        holdId: h.id,
        status: "confirmed",
        version: 1,
        createdAt: now,
        updatedAt: now,
        paymentMode: "test",
        requestKey: d.requestKey,
      };
    delete booking.keys;
    delete booking.expiresAt;
    for (const k of h.keys) slots[k] = { kind: "booking", id: bookingId };
    tx.set(invRef, { slots });
    tx.update(ref, { status: "confirmed", updatedAt: now });
    tx.create(db.doc(`bookings/${bookingId}`), booking);
    event(tx, booking, "confirmed", now);
    return { status: "confirmed", bookingId, serverNow: now };
  });
  if (r.data.paymentMode === "delay")
    await new Promise((resolve) => setTimeout(resolve, 3000));
  return result;
});
exports.cancelBooking = wrap((r) =>
  mutate(r, "cancel", async (tx, uid, d, now) => {
    const ref = db.doc(`bookings/${id(d.bookingId)}`),
      doc = await tx.get(ref);
    if (!doc.exists) D.fail("NOT_FOUND");
    const b = doc.data();
    if (b.userId !== uid) D.fail("FORBIDDEN");
    if (b.status === "cancelled")
      return { status: "cancelled", bookingId: b.id };
    if (b.version !== d.expectedVersion || b.status !== "confirmed")
      D.fail("STATE_CONFLICT");
    if (!D.canCancel(b.startAt, now)) D.fail("CANCEL_DEADLINE");
    const invRef = inventoryRef(b.spaceId, b.startAt),
      inv = await tx.get(invRef),
      slots = inv.data()?.slots || {};
    for (let t = b.startAt; t < b.endAt; t += D.SLOT)
      if (slots[t]?.id === b.id && slots[t]?.kind === "booking")
        delete slots[t];
    const next = {
      ...b,
      status: "cancelled",
      version: b.version + 1,
      updatedAt: now,
      refundAmount: b.amount,
    };
    tx.set(invRef, { slots });
    tx.update(ref, next);
    event(tx, next, "cancelled", now);
    return { status: "cancelled", bookingId: b.id };
  }),
);
exports.getBookings = wrap(async (r) => {
  const uid = uidOf(r),
    now = Date.now();
  const docs = await db
    .collection("bookings")
    .where("userId", "==", uid)
    .limit(100)
    .get();
  return {
    serverNow: now,
    bookings: docs.docs
      .map((x) => {
        const b = x.data();
        return {
          ...b,
          status:
            b.status === "confirmed" && b.endAt <= now ? "completed" : b.status,
          canCancel: b.status === "confirmed" && D.canCancel(b.startAt, now),
        };
      })
      .sort((a, b) => b.createdAt - a.createdAt),
  };
});
exports.submitReview = wrap((r) =>
  mutate(r, "review", async (tx, uid, d, now) => {
    const bookingId = id(d.bookingId),
      ref = db.doc(`bookings/${bookingId}`),
      doc = await tx.get(ref),
      reviewRef = db.doc(`reviews/${bookingId}`),
      existing = await tx.get(reviewRef);
    if (!doc.exists) D.fail("NOT_FOUND");
    const b = doc.data();
    if (b.userId !== uid) D.fail("FORBIDDEN");
    if (b.status === "cancelled" || b.endAt > now || existing.exists)
      D.fail("STATE_CONFLICT");
    if (
      !Number.isInteger(d.rating) ||
      d.rating < 1 ||
      d.rating > 5 ||
      typeof d.content !== "string" ||
      d.content.trim().length < 10 ||
      d.content.trim().length > 500
    )
      D.fail("INVALID_REVIEW");
    tx.create(reviewRef, {
      bookingId,
      userId: uid,
      spaceId: b.spaceId,
      rating: d.rating,
      content: d.content.trim(),
      createdAt: now,
    });
    return { saved: true };
  }),
);
exports.setBlock = wrap((r) =>
  mutate(r, "block", async (tx, uid, d, now) => {
    const spaceId = id(d.spaceId),
      spaceDoc = await tx.get(db.doc(`spaces/${spaceId}`));
    if (!spaceDoc.exists) D.fail("NOT_FOUND");
    const space = spaceDoc.data();
    if (space.hostId !== uid || r.auth.token.host !== true) D.fail("FORBIDDEN");
    const { keys } = D.interval(d.startAt, d.endAt, 1, space, now),
      ref = inventoryRef(spaceId, d.startAt),
      doc = await tx.get(ref),
      slots = doc.data()?.slots || {};
    if (d.blocked) {
      if (keys.some((k) => D.occupied(slots[k], now))) D.fail("SLOT_TAKEN");
      for (const k of keys) slots[k] = { kind: "block", id: uid };
    } else {
      for (const k of keys)
        if (slots[k]?.kind === "block" && slots[k]?.id === uid) delete slots[k];
    }
    tx.set(ref, { slots });
    return { saved: true };
  }),
);

exports.getHostBookings = wrap(async (r) => {
  const uid = uidOf(r);
  if (r.auth.token.host !== true) D.fail("FORBIDDEN");
  const now = Date.now();
  const docs = await db
    .collection("bookings")
    .where("hostId", "==", uid)
    .limit(100)
    .get();
  return {
    bookings: docs.docs.map((x) => {
      const b = x.data();
      return {
        ...b,
        status:
          b.status === "confirmed" && b.endAt <= now ? "completed" : b.status,
      };
    }),
  };
});
