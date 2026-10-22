# Spark 운영·전환 안내

## 사용하는 서비스와 제외한 서비스

| 항목 | Spark 버전 |
| --- | --- |
| 이메일 Auth / Firestore | 무료 할당량 안에서 사용 |
| Cloud Functions / Cloud Run | 배포 구성·Android 의존성 제거 |
| Cloud Storage | 사용하지 않음; 관리자 등록 외부 사진 URL만 표시 |
| 자동 FCM·스케줄러·outbox | 사용하지 않음; 앱 알림함만 원자적으로 기록 |
| 실제 PG / SMS 인증 | 사용하지 않음 |
| 관리용 Node.js 도구 | 관리자의 로컬 PC에서 실행; 호스팅 서버 아님 |

Firestore Spark의 일반 무료 할당량은 저장 1GiB, 일 읽기 50,000회·쓰기 20,000회·삭제 20,000회입니다. 프로젝트 콘솔의 해당 데이터베이스 사용량과 공식 한도를 확인하세요. 무료 할당량은 무제한 운영이나 계속 가용성을 보장하지 않습니다. 앱은 한도 초과 오류를 표시하고 자동 연속 재시도를 하지 않습니다. 결제 계정을 연결하거나 Blaze로 바꾸는 명령을 실행하지 않습니다.

선점은 재고+선점 2개, 확정은 재고+선점+예약+수신자 알림함, 취소는 재고+예약+수신자 알림함을 씁니다. 각 동작의 서버 시각 동기화에 추가 1회 쓰기와 조회가 있습니다. 규칙 접근과 트랜잭션 재시도로 실제 읽기 수가 증가할 수 있습니다. 초기 시드는 가상 공간 3곳·30일 재고 90개를 만들고, 반복 실행은 기존 재고를 덮어쓰지 않습니다.

FCM 메시지 수신 자체가 유료라서 뺀 것이 아닙니다. 사용자 기기에 관리자 발송 자격 증명을 넣지 않기 위해, 신뢰할 수 있는 자동 발송 서버가 없는 이번 버전에서는 전송 기능을 제공하지 않습니다. 알림함은 앱 재조회로 확인합니다.

## 운영일과 운영자 준비

새로운 운영일은 관리자가 미리 등록합니다. 앱 고객은 재고 문서를 임의로 만들 수 없습니다. 미등록 날짜는 예약 가능한 것으로 가정하지 않고 안내합니다.

로컬 데모는 `npm --prefix firebase run seed`로 향후 30일을 등록합니다. 실제 프로젝트에는 두 에뮬레이터 변수가 없는 시드 명령이 거절됩니다.

실제 프로젝트에 **가상 데모 카탈로그**를 등록하려면 관리자의 신뢰할 수 있는 로컬 환경에서 Application Default Credentials를 준비한 뒤 다음을 명시적으로 실행합니다. 이 도구는 기존 공간·재고를 덮어쓰지 않고, Functions나 결제 계정을 만들지 않습니다.

```bash
node firebase/scripts/prepare-catalog.js --live --project YOUR_PROJECT_ID --host-uid YOUR_HOST_UID --days 30
```

기존 공간의 `schemaVersion`이 2가 아니면 자동 변환하지 않고 중단합니다. 실제 공간은 승인된 제공자가 앱의 운영 홈/내 공간 → 새 공간 등록에서 등록할 수 있습니다. 기존 공간의 관리자 등록도 유지합니다.

### Firebase 콘솔에서 장소 제공자 승인

1. Firebase 콘솔에서 **littlespace-a7087 → Firestore Database → 데이터 → users → 해당 회원 UID**를 엽니다.
2. `accountType`이 문자열 `host`이고 `termsAccepted`가 boolean `true`인지 확인합니다.
3. **필드 추가**에서 이름 `approved`, 유형 **boolean**, 값 **true**로 저장합니다. 기존 `approved:false`가 있으면 값을 true로 수정합니다. 문자열 `"true"`는 승인으로 인정하지 않습니다.
4. 회원이 앱의 **승인 상태 새로고침**을 누르면 운영 기능이 열립니다. 로그아웃·토큰 재발급 명령은 필요 없습니다.
5. 승인된 회원은 앱에서 새 공간을 등록합니다. 등록 시 `hostId`는 로그인 UID로 자동 지정됩니다. 기존에 관리자가 만든 공간만 해당 문서의 `hostId`를 회원 UID로 연결하세요.

권한을 회수하려면 `approved`를 boolean `false`로 바꿉니다. 이후 서버 요청은 즉시 거절되고, 앱에서도 상태 새로고침/재실행 시 승인 대기로 돌아갑니다. 회원 본인은 앱/API에서 승인 값을 생성·변경·삭제할 수 없습니다. 신규 가입은 `approved:false`로 생성됩니다. 필드가 없는 기존 계정도 미승인으로 처리합니다.

**최신 앱 설치와 Firestore 규칙 배포를 함께 적용해야 합니다.** 이전 `host:true` Custom Claim은 이제 운영 권한으로 사용하지 않습니다. 이전 운영자도 콘솔에서 `accountType:host`, `approved:true`를 설정하세요. `approve-host.js`는 대량 관리 시 선택적으로 사용할 수 있는 도구이며 이제 동일한 Firestore 승인 필드만 변경합니다. 일반적인 승인은 콘솔에서 처리하면 됩니다.

시드와 카탈로그 준비는 서로 목적이 다릅니다. 전자는 테스트 계정·데이터를 로컬에 만들고, 후자는 명시한 라이브 프로젝트에 누락된 가상 카탈로그만 등록합니다. 라이브 관리 도구는 이 작업에서 실행하지 않았습니다.

## 기존 데이터 전환

이 버전은 새 `sparkInventory`, `sparkHolds`, `sparkBookings`, `sparkOperations`, `sparkReviews`를 사용합니다. 기존 `inventory`, `holds`, `bookings`, `requests`, `reviews`, `outbox` 원본을 삭제하지 않습니다. 다만 새 앱/규칙은 기존 거래 컬렉션을 사용하거나 공개하지 않습니다.

이미 실제 예약 데이터가 있다면 바로 규칙을 덮어쓰지 말고, 별도 관리 환경에서 백업·소유권·가격·UTC Timestamp·일자별 슬롯·예약 버전을 검증해 전환해야 합니다. 자동 변환은 제공하지 않습니다. 기존 사용자 프로필·찜·알림함 경로는 유지됩니다. 미확정 요청 저장소도 v2로 분리하며 기존 미확정 요청을 새 예약으로 재전송하지 않습니다. 이미 운영 중이라면 전환 전에 이전 서버에서 그 요청 결과를 확인하세요.

## 공식 참고

- [Firebase 요금제](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans)
- [Firestore 무료 할당량·한도](https://firebase.google.com/docs/firestore/quotas)
- [원자적 작업과 getAfter 보안 규칙](https://firebase.google.com/docs/firestore/manage-data/transactions#data_validation_for_atomic_operations)
- [Cloud Storage 요금제 변경](https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024)

현재 클라우드에서는 Firebase 문서·기본 JAR 배포 호스트가 403을 반환했습니다. 실제 규칙 검증은 Google 공식 Cloud SDK의 Firestore Emulator 1.22.0 배포본을 SHA-256 검증 후 원래 launcher로 실행했습니다. `scripts/install-firestore-emulator.sh`에서 출처·핀·체크섬을 확인할 수 있습니다. TLS/체크섬 검증을 끄거나 JAR를 수정하지 않았습니다.

## 제공자 공간 등록과 운영일

승인된 제공자는 **운영 홈 또는 내 공간 → 새 공간 등록**에서 공간명·소개·진주 상세주소·유형·정원·30분 고정요금·운영 시간·편의시설·이용 수칙·선택 사진 URL을 입력합니다. 사진 업로드 대신 HTTPS URL을 사용합니다. 공간과 최초 연속 운영일(기본 내일부터 7일)을 하나의 트랜잭션으로 생성하며 공간은 일반회원의 탐색에 공개됩니다.

운영일은 서버 기준 오늘부터 30일 범위 안에서만 생성합니다. 날짜별 48개 슬롯은 빈 상태로 초기화하고, 기존 재고 문서를 덮어쓰거나 예약을 삭제하는 초기화는 허용하지 않습니다. **내 공간 → 예약 가능한 날짜 추가**는 누락된 날짜만 추가하고 기존 예약·선점·차단은 그대로 둡니다. 제공자의 공간 수정·삭제와 사진 업로드는 이번 범위에 포함하지 않았습니다.

## 인덱스 오류 처리

`The query requires an index`는 실제 프로젝트에 해당 복합 인덱스가 없거나 생성 중임을 뜻합니다. 저장소의 `firestore.indexes.json`에 필요한 인덱스를 정의했습니다. 로그인한 관리자 환경에서 프로젝트 폴더 기준으로 다음을 실행합니다.

```bash
firebase/node_modules/.bin/firebase deploy --project littlespace-a7087 --only firestore
```

이번 변경은 규칙과 인덱스를 모두 게시해야 합니다. 콘솔의 Firestore Database → 인덱스에서 **사용 설정됨(Enabled)**이 될 때까지 기다린 뒤 다시 조회합니다. 오류 메시지의 생성 링크로 해당 인덱스를 만들어도 됩니다.

| 조회 | 컬렉션 | 필드 |
| --- | --- | --- |
| 공개 공간 | spaces | active 오름차순, schemaVersion 오름차순 |
| 제공자 내 공간 | spaces | hostId 오름차순, active 오름차순, schemaVersion 오름차순 |
| 고객 예약 | sparkBookings | userId 오름차순, createdAt 내림차순 |
| 제공자 예약 | sparkBookings | hostId 오름차순, createdAt 내림차순 |
| 공간 후기 | sparkReviews | spaceId 오름차순, createdAt 내림차순 |

앱 알림은 createdAt 단일 필드 범위·정렬이며 기본 단일 필드 인덱스를 사용합니다. Emulator는 실제 운영의 복합 인덱스 필요 여부를 강제하지 않으므로 로컬 테스트 통과가 라이브 인덱스 배포 완료를 의미하지 않습니다.
