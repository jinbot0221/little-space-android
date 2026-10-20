# Firebase 서버 설계

기획서의 REST API 의도를 Firebase Callable Functions로 옮겼습니다. Firebase Auth ID 토큰을 SDK가 전달하고 서버가 `request.auth.uid`를 사용합니다. 클라이언트가 보낸 userId·role은 권한 판단에 쓰지 않습니다. 통화는 정수 원, 시각은 UTC epoch milliseconds, 화면과 운영일 구분은 Asia/Seoul입니다.

## 컬렉션

| 경로 | 용도 / 쓰기 주체 |
| --- | --- |
| `spaces/{spaceId}` | 공개 공간 정보·시설·운영 시각·정원·슬롯 단가 / Admin |
| `inventory/{spaceId}_{YYYY-MM-DD}` | KST 운영일별 30분 슬롯 map / Functions |
| `holds/{holdId}` | 5분 선점·요금 스냅샷·상태 / Functions |
| `bookings/{bookingId}` | 예약·계약 스냅샷·버전·테스트 환불 / Functions |
| `requests/{sha256(uid:operation:key)}` | 요청 fingerprint·결과 원자적 저장 / Functions |
| `reviews/{bookingId}` | 완료 예약당 1회 후기 / Functions |
| `users/{uid}` | 닉네임·필수 동의 버전 / 본인, 필드 제한 |
| `users/{uid}/favorites/{spaceId}` | 개인 찜 / 본인 |
| `users/{uid}/notifications/{id}` | 서버 생성 알림, 본인은 읽음만 수정 |
| `outbox/{eventId}` | 예약 상태 변경 이벤트 / Functions, 발송 워커 미구현 |

요청 키는 작업·계정에 묶고 본문을 SHA-256 fingerprint로 비교합니다. 같은 키에 다른 본문은 `REQUEST_KEY_REUSED`입니다. 결과와 재고 변경을 같은 트랜잭션에 기록합니다. 서버 함수는 현재 평평한 필드의 요청만 받습니다.

각 공간·운영일의 재고 문서를 읽고 갱신하므로 같은 날의 경합은 Firestore 트랜잭션 재시도로 직렬화됩니다. 예약은 [시작, 종료)이므로 인접한 시간은 허용합니다. 만료 선점은 재고 정리 작업이 없어도 점유로 보지 않습니다. 확정 시 선점의 소유자·만료·재고 ID를 다시 확인합니다. 슬롯당 `space.slotPrices[minuteOfDay]`가 있으면 이를, 없으면 `slotPrice`를 사용합니다.

## Callable 목록 (asia-northeast3)

| 함수 | 주요 입력 | 결과 |
| --- | --- | --- |
| `getAvailability` | spaceId, date | busy epoch 목록, serverNow; 비회원 허용 |
| `createHold` | spaceId, startAt, endAt, people, requestKey | hold·amount·expiresAt·serverNow |
| `releaseHold` | holdId, requestKey | released |
| `confirmBooking` | holdId, paymentMode, policyAgreed, requestKey | confirmed + bookingId / payment_failed |
| `cancelBooking` | bookingId, expectedVersion, requestKey | cancelled |
| `getBookings` | 없음 | 본인 예약 최대 100개, 서버 기준 completed·canCancel |
| `submitReview` | bookingId, rating, content, requestKey | saved |
| `getHostBookings` | 없음 | 소유 운영자 예약 최대 100개 |
| `setBlock` | spaceId, startAt, endAt, blocked, requestKey | saved |

`paymentMode`: success / failure / delay. 외부 PG나 실제 과금은 없습니다. failure는 선점을 해제하고 거래를 생성하지 않습니다. delay는 정상 커밋 후 응답을 3초 지연합니다. 응답이 유실돼도 **기존 키**로 다시 요청하면 동일 예약을 반환합니다. 미확정 요청은 `transaction_recovery` 앱 preferences에 저장하며 확인 전 새 결제를 시도하지 않습니다.

취소는 정확히 24시간 전까지 포함해서 서버에서 판정합니다. 슬롯 반환·취소 버전 증가·테스트 환불액·고객 및 운영자 알림함·outbox를 같은 트랜잭션에서 커밋합니다. 이미 취소된 거래의 반복 취소는 같은 결과를 반환합니다. 운영 차단 해제는 자신의 block만 지우며 예약 슬롯을 지우지 않습니다.

오류는 Firebase Callable 표준 envelope의 code / message 및 `details.code`, `details.requestId`를 사용합니다. REST HTTP 409를 직접 반환하는 구조는 아닙니다. `SLOT_TAKEN`, `HOLD_EXPIRED`, `STATE_CONFLICT`, `CANCEL_DEADLINE` 등은 `failed-precondition`으로 매핑됩니다.

## 인증과 향후 연동

이메일 인증과 재설정 메일은 Firebase Auth를 사용합니다. 비밀번호를 Firestore·앱 저장소·로그에 저장하지 않습니다. 필수 동의 프로필이 없는 계정은 선점할 수 없으며 앱에서 가입 완료 화면으로 안내합니다. 고객 가입으로 host claim을 설정할 수 없습니다.

문서의 소셜 공급자별 별도 계정 정책과 Firebase Auth의 기본 이메일 연결 정책은 동일하지 않습니다. 공급자 구현 시 이를 명시적으로 설계해야 하며, 현재 소셜 로그인은 구현하지 않았습니다. 카카오는 서버 토큰 검증 후 Firebase custom token 발급 경로가 필요합니다. Apple·Google은 등록된 OAuth 클라이언트와 Android 서명 인증서 설정이 필요합니다.

FCM 구현 시 outbox 소비, 중복 발송 키, 상태 재검증, 그룹 설정, 기기별 토큰과 로그아웃 해제, TTL, 재시도 및 예약 알림을 추가해야 합니다. 현재 outbox 기록만으로 푸시 발송이 이루어지지는 않습니다.
