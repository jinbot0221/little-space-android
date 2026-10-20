# 검증 기록

2026-10-20 클라우드 환경에서 수행.

| 검사 | 상태 | 근거 |
| --- | --- | --- |
| Node 22 단위·핸들러 테스트 | 통과 11개 | `cd functions && npm test` |
| 서버 JS 구문 | 통과 | `node --check` |
| Android Debug APK / Lint | 통과 | `:app:assembleDebug :app:lintDebug`, Lint 오류 0 / 백업 설정·KTX 사용 권고 경고 2 |
| 실제 Firebase Emulator 통합 | 차단, 미실행 | `storage.googleapis.com`에서 Emulator JAR 다운로드 403 |
| Android 기기 UI / 실제 Firebase 배포 | 미실행 | 기기·실제 프로젝트 연결 전 |

핸들러 테스트는 실제 Cloud Functions 코드를 메모리 트랜잭션 어댑터에 연결합니다. 시간 경쟁·중복 키·만료·실패 결제·소유권을 검사하며 모든 읽기가 쓰기 전에 수행되는지도 확인합니다. **실제 Firestore 트랜잭션 동작이나 보안 규칙 통과를 증명하지는 않습니다.** 별도의 `test/integration/booking.test.js`가 Auth·Functions·Firestore Emulator를 사용하는 검증을 담당합니다.

환경 문제 해결: 시스템 Java는 JRE이므로 공식 JDK 21을 체크섬 검증 후 설치했습니다. Gradle은 환경 HTTPS 프록시를 사용하고 시스템 Java CA store로 TLS를 검증합니다. 검증을 우회하지 않았습니다. Android SDK 35·Build Tools 35.0.0·플랫폼 도구를 설치했습니다.

네트워크 초안에는 `storage.googleapis.com`(Firebase Emulator)과 `images.unsplash.com`(문서의 예시 사진)을 추가했습니다. 초안 저장은 현재 네트워크 적용 또는 환경 게시를 의미하지 않습니다. 네트워크 적용 후 `./scripts/test-integration.sh`를 실행해야 합니다.

`./scripts/install-cloud.sh` 재실행도 성공했습니다. 의존성을 lockfile 기준으로 재설치하고 APK·Lint·11개 서버 테스트를 다시 완료했습니다. 새 머신에서의 스냅샷 복원·환경 게시 검증은 수행하지 않았습니다.
