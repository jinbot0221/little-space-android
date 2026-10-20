const { test } = require("node:test");
const a = require("node:assert/strict");
const D = require("../src/domain");
const start = Date.parse("2030-01-02T05:00:00Z");
const space = {
  capacity: 6,
  active: true,
  openMinute: 540,
  closeMinute: 1320,
  slotPrice: 5000,
};
test("two hours sum four server-priced slots", () =>
  a.equal(D.interval(start, start + 4 * D.SLOT, 4, space, 0).amount, 20000));
test("half-open adjacent ranges share no inventory keys", () => {
  const x = D.interval(start, start + 4 * D.SLOT, 4, space, 0),
    y = D.interval(start + 4 * D.SLOT, start + 6 * D.SLOT, 4, space, 0);
  a.ok(x.keys.every((k) => !y.keys.includes(k)));
});
test("expiry is strict at exact server deadline", () => {
  a.equal(D.occupied({ kind: "hold", expiresAt: 100 }, 100), false);
  a.equal(D.occupied({ kind: "hold", expiresAt: 101 }, 100), true);
});
test("cancellation includes exactly 24 hours", () => {
  a.equal(D.canCancel(86400000, 0), true);
  a.equal(D.canCancel(86400000, 1), false);
});
test("invalid duration, past, capacity, alignment and overnight rejected", () => {
  for (const [s, e, p] of [
    [start, start + D.SLOT, 4],
    [start, start + 17 * D.SLOT, 4],
    [start, start + 2 * D.SLOT, 7],
    [start + 1, start + 2 * D.SLOT, 4],
    [Date.parse("2030-01-02T14:00:00Z"), Date.parse("2030-01-02T16:00:00Z"), 4],
  ])
    a.throws(() => D.interval(s, e, p, space, 0));
  a.throws(() => D.interval(start, start + 2 * D.SLOT, 4, space, start));
});
test("operating blocks cannot expire like a hold", () =>
  a.equal(D.occupied({ kind: "block" }, 100), true));
