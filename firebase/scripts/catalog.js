const { Timestamp } = require("firebase-admin/firestore");
const fixtures = [
  [
    "focus-a",
    "포커스 룸 A",
    "몰입이 필요한 순간, 오롯이 나만의 공간",
    "스터디룸",
    6,
    5000,
    "photo-1497366754035-f200968a6e72",
    ["Wi-Fi", "화이트보드", "콘센트"],
  ],
  [
    "round-table",
    "라운드 테이블",
    "좋은 아이디어가 시작되는 곳",
    "회의실",
    10,
    7500,
    "photo-1497366811353-6870744d04b2",
    ["Wi-Fi", "프로젝터", "화이트보드"],
  ],
  [
    "slow-studio",
    "슬로우 스튜디오",
    "햇살과 함께 만드는 새로운 장면",
    "촬영 스튜디오",
    4,
    10000,
    "photo-1497366412874-3415097a27e7",
    ["Wi-Fi", "자연광", "촬영 장비"],
  ],
];
async function ensureCatalog(db, hostId, days = 30) {
  const today =
    Math.floor((Date.now() + 9 * 3600000) / 86400000) * 86400000 - 9 * 3600000;
  for (const [
    id,
    name,
    description,
    type,
    capacity,
    slotPrice,
    photo,
    facilities,
  ] of fixtures) {
    const ref = db.doc(`spaces/${id}`);
    await db.runTransaction(async (tx) => {
      const old = await tx.get(ref);
      if (!old.exists)
        tx.create(ref, {
          id,
          name,
          description,
          type,
          capacity,
          slotPrice,
          facilities,
          hostId,
          active: true,
          region: "진주",
          address: "경상남도 진주시 진주대로 1035",
          openMinute: 540,
          closeMinute: 1320,
          rules:
            "실내 금연 · 간단한 음료만 반입 가능해요. 이용 후 정리해 주세요.",
          imageUrl: `https://images.unsplash.com/${photo}?auto=format&fit=crop&w=1000&q=80`,
          schemaVersion: 2,
          pricingMode: "flat",
        });
      else if (old.data().schemaVersion !== 2)
        throw Error("Legacy space requires explicit migration: " + id);
    });
    for (let i = 0; i < days; i++) {
      const ms = today + i * 86400000,
        date = new Date(ms + 9 * 3600000).toISOString().slice(0, 10),
        invId = id + "_" + date,
        inv = db.doc(`sparkInventory/${invId}`);
      await db.runTransaction(async (tx) => {
        if ((await tx.get(inv)).exists) return;
        tx.create(inv, {
          id: invId,
          spaceId: id,
          date,
          dayStart: Timestamp.fromMillis(ms),
          slots: Object.fromEntries(
            Array.from({ length: 48 }, (_, n) => [String(n), { kind: "free" }]),
          ),
          schemaVersion: 2,
          version: 0,
          updatedAt: Timestamp.now(),
          lastAction: "seed",
          lastId: "seed",
          lastStartSlot: 0,
          lastCount: 0,
        });
      });
    }
  }
}
module.exports = { ensureCatalog };
