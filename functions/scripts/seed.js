const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
if (
  !process.env.FIRESTORE_EMULATOR_HOST ||
  !process.env.FIREBASE_AUTH_EMULATOR_HOST
)
  throw new Error("Seed is restricted to the Firestore emulator");
initializeApp({ projectId: "demo-teumgot" });
const spaces = [
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
(async () => {
  const db = getFirestore();
  for (const [uid, email, host] of [
    ["demo-customer", "customer@teumgot.test", false],
    ["demo-host", "host@teumgot.test", true],
  ]) {
    const auth = getAuth();
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
    await auth.setCustomUserClaims(uid, { host });
    await db
      .doc(`users/${uid}`)
      .set({
        nickname: host ? "테스트 운영자" : "테스트 고객",
        termsAccepted: true,
        termsVersion: "1.0",
        privacyVersion: "1.0",
      });
  }
  for (const [
    id,
    name,
    description,
    type,
    capacity,
    slotPrice,
    photo,
    facilities,
  ] of spaces)
    await db
      .doc(`spaces/${id}`)
      .set({
        id,
        name,
        description,
        type,
        capacity,
        slotPrice,
        facilities,
        hostId: "demo-host",
        active: true,
        region: "진주",
        address: "경상남도 진주시 진주대로 1035",
        openMinute: 540,
        closeMinute: 1320,
        rules:
          "실내 금연 · 음식물 반입은 간단한 음료만 가능해요. 이용 후 정리해 주세요.",
        imageUrl: `https://images.unsplash.com/${photo}?auto=format&fit=crop&w=1000&q=80`,
      });
  console.log("Seeded 3 fictional spaces");
})();
