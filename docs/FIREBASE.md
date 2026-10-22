# Spark용 Firestore 계약

Android → Firebase Auth → Firestore SDK 트랜잭션 → Security Rules 구조입니다. Cloud Functions·Admin SDK를 앱의 예약 처리 경로에서 사용하지 않습니다. Admin SDK는 로컬 시드·신뢰할 수 있는 관리자 도구에만 있습니다.

## 데이터와 권한

| 경로 | 용도 |
| --- | --- |
| `spaces/{spaceId}` | 공개 공간·정원·운영 시각·고정단가, `schemaVersion:2`, `pricingMode:flat` / 관리자 등록 |
| `sparkInventory/{spaceId}_{KST-date}` | 48개 30분 슬롯·운영일 UTC Timestamp·버전·마지막 원자적 작업 |
| `sparkHolds/{requestKey}` | 고객별 5분 선점·계약 스냅샷·생성 서버 Timestamp |
| `sparkBookings/{holdId}` | 확정·취소 예약, 계약 스냅샷·버전·확정 요청 키 |
| `sparkOperations/{requestKey}` | 운영 차단·해제 요청의 불변 결과 |
| `sparkReviews/{bookingId}` | 완료된 본인 예약에 대한 후기 1회 |
| `users/{uid}` | 본인의 닉네임·약관 동의·가입 유형(`accountType`); 권한 쓰기 금지 |
| `users/{uid}/clock/current` | 본인만 기록하는 서버 Timestamp 기준 시각 |
| `users/{uid}/favorites/{spaceId}` | 본인 찜 |
| `users/{uid}/notifications/{bookingId}_{version}` | 확정·취소와 원자적으로 생성하는 앱 알림함 |

금액은 정수 원, Firestore 시각은 UTC Timestamp, 앱의 표시는 Asia/Seoul입니다. Repository 경계에서 Timestamp를 기존 화면의 epoch milliseconds로 변환합니다.

## 원자성

한 날짜 재고 문서의 `slots` map에 `'0'`~`'47'` 키를 고정합니다. 슬롯 값은 `free`, `hold`(ID·서버 stamp), `booking`(ID), `block`(운영자 ID) 중 하나입니다. 공개 재고에 고객 이름·연락처·결제 정보는 넣지 않습니다. 하루 단위 문서에서 경쟁하는 거래를 Firestore 트랜잭션이 재시도합니다. 포트폴리오 규모를 위한 구조이며 같은 공간·일자의 쓰기 경합이 큰 규모에는 다른 설계가 필요합니다.

보안 규칙은 다음을 함께 검증합니다.

- 바꾼 슬롯 키가 선택한 2~16개 범위 안에만 있는지 검사합니다. 남는 슬롯·다른 예약을 변경할 수 없습니다.
- 30분 경계, 1~8시간, 정원, 운영 시각, `dayStart` 기반 시작·종료, 관리자 가격·명칭·주소 스냅샷을 검사합니다.
- `getAfter()`로 선점/예약/재고/앱 알림함의 변경이 **같은 커밋**에 들어왔는지 검사합니다. 문서 하나만 직접 쓰는 요청은 거절합니다.
- 고객 UID는 인증 토큰에서 비교하고 운영 권한은 `host` custom claim과 공간 `hostId`를 모두 검사합니다.
- 사용자가 금액·서버 Timestamp·UID·예약 상태를 바꾸거나 권한을 높이는 쓰기는 거절합니다.

16개 슬롯을 별도 문서로 읽지 않아 get() 한도를 피합니다. 비교식도 불필요한 인덱스 평가를 생략하도록 구성했습니다. 최대 8시간의 생성·확정·취소를 실제 Emulator에서 검사합니다.

## 선점·확정·실패 복구

선점의 `createdAt`와 슬롯 `stamp`는 `serverTimestamp()`이며 규칙에서 `request.time`과 같은지 검사합니다. 만료는 **서버 생성 시각 + 5분**으로 계산합니다. 사용자가 임의의 `expiresAt`를 보내는 구조가 아닙니다. 만료 선점은 자동 삭제 없이 새 선점이 대체할 수 있습니다. 늦은 해제 요청은 대체된 다른 선점을 지우지 않습니다.

선점 ID는 요청 키이며 변경 불가능합니다. 예약 ID는 선점 ID이므로 한 선점에 여러 예약을 만들 수 없습니다. 확정 요청 키·결제 모드·동의 값이 같은 재시도는 기존 결과를 반환합니다. 같은 키의 본문을 바꿔 덮어쓸 수 없습니다. 동시에 확정한 요청이 규칙 충돌로 거절돼도 서버에서 기존 결과를 다시 읽고 같은 키인지 확인합니다. 기기에 저장한 미확정 요청은 새 키로 다시 결제하지 않습니다.

테스트 결제만 있습니다. `failure`는 선점을 해제하고 예약을 만들지 않으며 결과를 선점에 기록합니다. `delay`는 커밋 후 응답을 3초 지연합니다. 이는 PG·실제 과금·실제 환불이 아닙니다.

취소는 `startAt - request.time >= 24시간`일 때만 가능합니다. 슬롯 반환·취소 버전 증가·테스트 환불액·고객/운영자 알림함을 한 트랜잭션에서 저장합니다. 완료 여부는 서버 시각 조회 후 화면에서 계산하며 후기 규칙은 종료 시각이 지난 본인 확정 예약인지 다시 확인합니다.

## 서버 시각과 읽기 비용

보호 동작 전 본인의 `clock/current.at`를 서버 Timestamp로 기록·조회합니다. 규칙은 그 값을 직접 신뢰하지 않고 각 쓰기의 `request.time`으로 최종 판정합니다. 기기 시각이 잘못돼도 거래 조건을 우회할 수 없습니다. 게스트의 시간·금액 표시는 안내용이며 실제 선점에서 다시 검증합니다.

자동 Firestore 리스너·주기적 폴링은 없습니다. 공간/예약/알림 목록은 30개, 후기 20개, 찜 50개로 제한합니다. 예약은 최신순으로 조회하고 필요한 composite index를 `firestore.indexes.json`에 포함했습니다. 페이지네이션은 후속 범위입니다. 서버 시각 동기화에도 읽기·쓰기가 발생하고, 보안 규칙의 문서 접근도 읽기 비용에 포함됩니다.

현재 `call(name,data)`는 화면과 Repository의 내부 호출 호환 인터페이스일 뿐 HTTP Callable API가 아닙니다. 앱에 `firebase-functions` SDK나 배포할 Functions 소스가 없습니다.

## 회원 유형별 화면

회원가입과 미완료 가입 화면에서 `customer`(일반 이용자) 또는 `host`(장소 제공자)를 선택합니다. `accountType`은 최초 생성 후 클라이언트가 변경할 수 없습니다. 기존 필드 없는 계정은 일반 이용자로 처리하되 신뢰할 수 있는 `host:true` claim이 있으면 기존 운영자 화면을 유지합니다.

- 일반 이용자: 탐색 / 찜 / 예약내역 / 마이.
- 장소 제공자: 운영 홈 / 내 공간 / 운영 예약 / 마이. 미승인 계정은 승인 대기 안내만 표시합니다.
- 운영 권한: `accountType`이 아니라 관리자 발급 Auth claim과 공간 `hostId`를 함께 검사합니다. 제공자 화면에서는 고객 취소·후기 버튼을 제공하지 않습니다.

공간 등록은 현재 관리자 도구·콘솔에서 처리합니다. 앱 내 공간 등록 폼과 승인 심사 관리자 화면은 아직 제공하지 않습니다. 새 앱 사용 전에 변경된 Firestore 보안 규칙을 배포해야 `accountType`을 포함한 가입이 저장됩니다.

## 로그인·앱 시작 시 PERMISSION_DENIED

예약하지 않았는데 권한·선점·예약 상태 안내가 나왔다면 이전 앱의 공통 오류 문구입니다. 새 앱은 회원정보(`users`)와 공간 목록(`spaces`) 조회 거절을 구분합니다. 현재 규칙에서는 로그인한 본인의 프로필(아직 없는 문서 포함) 조회와 `active:true` 조건을 넣은 공간 조회를 허용합니다. 계정만 생성된 상태로 앱을 다시 열면 가입 완료 화면으로 복구합니다.

실제 앱에서 계속 거절된다면 `google-services.json`의 프로젝트 ID와 배포 프로젝트가 같은지, Firestore `(default)` 데이터베이스에 저장소의 규칙이 배포됐는지 확인하세요. `.firebaserc` 기본값은 로컬 테스트용 `demo-teumgot`이므로 실제 배포에는 프로젝트를 명시합니다.

```bash
firebase/node_modules/.bin/firebase login
firebase/node_modules/.bin/firebase deploy --project littlespace-a7087 --only firestore
```

Firebase 콘솔을 사용한다면 Firestore Database → 규칙에서 저장소 루트의 `firestore.rules` 전체 내용을 반영하고 게시합니다. 규칙을 모두 공개하는 방식으로 해결하지 않습니다. Firebase Auth에 이미 만들어진 계정은 삭제하거나 다시 가입할 필요 없이 로그인 후 가입 완료를 이어갑니다.

실제 연결 대상 프로젝트는 `littlespace-a7087`입니다. `.firebaserc`의 `production` 별칭으로 등록했으며 로컬 테스트용 기본 프로젝트는 `demo-teumgot`을 유지합니다. 실제 Android 설정 파일도 `project_info.project_id`가 `littlespace-a7087`인 파일을 사용해야 합니다. 이 클라우드 환경에서는 Firestore API 접속이 프록시에서 차단되고 Firebase CLI 인증 계정도 없어 라이브 규칙 상태는 검증하지 못했습니다.
