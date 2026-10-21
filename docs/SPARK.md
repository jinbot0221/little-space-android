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

기존 공간의 `schemaVersion`이 2가 아니면 자동 변환하지 않고 중단합니다. 실제 상품 정보는 별도 관리 도구/콘솔에서 적합한 v2 스키마로 등록하세요. 운영자 UID는 Auth 계정에 실제로 존재해야 합니다. 운영자에게는 Admin SDK에서 기존 custom claims를 보존해 `host:true`를 추가해야 합니다. 앱 회원가입에서 선택할 수 없습니다.

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
