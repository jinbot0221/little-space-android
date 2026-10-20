"use strict";
const SLOT = 30 * 60 * 1000;
const HOLD_TTL = 5 * 60 * 1000;
class DomainError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}
function fail(code) {
  throw new DomainError(code);
}
function interval(startAt, endAt, people, space, now) {
  if (
    !Number.isSafeInteger(startAt) ||
    !Number.isSafeInteger(endAt) ||
    startAt % SLOT ||
    endAt % SLOT ||
    startAt <= now ||
    endAt - startAt < 2 * SLOT ||
    endAt - startAt > 16 * SLOT
  )
    fail("INVALID_INTERVAL");
  const date = (t) => new Date(t + 9 * 3600000).toISOString().slice(0, 10);
  if (
    date(startAt) !== date(endAt - 1) ||
    !Number.isInteger(people) ||
    people < 1 ||
    people > space.capacity ||
    !space.active
  )
    fail("INVALID_SELECTION");
  const keys = [];
  let amount = 0;
  for (let t = startAt; t < endAt; t += SLOT) {
    const minute = (t / 60000 + 540) % 1440;
    if (minute < space.openMinute || minute + 30 > space.closeMinute)
      fail("CLOSED");
    const price = space.slotPrices?.[minute] ?? space.slotPrice;
    if (!Number.isSafeInteger(price) || price < 0) fail("INVALID_PRICE");
    amount += price;
    keys.push(String(t));
  }
  return { keys, amount };
}
function occupied(slot, now) {
  return (
    !!slot &&
    (slot.kind === "booking" ||
      slot.kind === "block" ||
      (slot.kind === "hold" && slot.expiresAt > now))
  );
}
function canCancel(startAt, now) {
  return startAt - now >= 24 * 3600000;
}
module.exports = {
  SLOT,
  HOLD_TTL,
  DomainError,
  fail,
  interval,
  occupied,
  canCancel,
};
