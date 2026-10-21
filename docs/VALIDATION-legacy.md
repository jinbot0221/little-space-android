# 검증 기록

2026-10-20 클라우드 환경에서 수행.

| 검사 | 상태 | 근거 |
| --- | --- | --- |
| Node 22 단위·핸들러 테스트 | 통과 11개 | `cd functions && npm test` |
| 서버 JS 구문 | 통과 | `node --check` |
| Android Debug APK / Lint | 통과 | `:app:assembleDebug :app:lintDebug`, Lint 오류 0 / 백업 설정·KTX 사용 권고 경고 2 |
| 실제 Firebase Emulator 통합 | 차단, 미실행 | `storage.googleapis.com`에서 Emulator JAR 다운로드 403 |
| 실제 Firebase 연결 UI / 배포 | 미실행 | 아래 촬영용 UI 확인과 별도 |

핸들러 테스트는 실제 Cloud Functions 코드를 메모리 트랜잭션 어댑터에 연결합니다. 시간 경쟁·중복 키·만료·실패 결제·소유권을 검사하며 모든 읽기가 쓰기 전에 수행되는지도 확인합니다. **실제 Firestore 트랜잭션 동작이나 보안 규칙 통과를 증명하지는 않습니다.** 별도의 `test/integration/booking.test.js`가 Auth·Functions·Firestore Emulator를 사용하는 검증을 담당합니다.

환경 문제 해결: 시스템 Java는 JRE이므로 공식 JDK 21을 체크섬 검증 후 설치했습니다. Gradle은 환경 HTTPS 프록시를 사용하고 시스템 Java CA store로 TLS를 검증합니다. 검증을 우회하지 않았습니다. Android SDK 35·Build Tools 35.0.0·플랫폼 도구를 설치했습니다.

네트워크 초안에는 `storage.googleapis.com`(Firebase Emulator)과 `images.unsplash.com`(문서의 예시 사진)을 추가했습니다. 초안 저장은 현재 네트워크 적용 또는 환경 게시를 의미하지 않습니다. 네트워크 적용 후 `./scripts/test-integration.sh`를 실행해야 합니다.

`./scripts/install-cloud.sh` 재실행도 성공했습니다. 의존성을 lockfile 기준으로 재설치하고 APK·Lint·11개 서버 테스트를 다시 완료했습니다. 새 머신에서의 스냅샷 복원·환경 게시 검증은 수행하지 않았습니다.

## 화면 촬영 확인 · 2026-10-21

Android 8.0(API 26) 에뮬레이터에서 debug 전용 촬영 Activity를 실행해 **14개 화면의 원본 PNG(1080×2340)**를 저장했습니다. Compose 접근성 텍스트가 나타날 때까지 기다린 뒤 캡처했으며, PNG 청크 CRC와 파일 완결성, 해상도, README 이미지·확대 링크를 확인했습니다. 대표 화면의 실제 렌더링도 시각적으로 확인했습니다.

촬영용 오프라인 가상 공간·거래를 사용했습니다. 실제 Firebase 인증·거래 검증과 구분합니다. 사진은 실패 대체 상태로 표시됩니다. 촬영 진입점은 debug 소스에만 있고 release 병합 manifest에서 제외된 것을 확인했습니다. 변경 후 Debug APK 빌드와 Lint도 통과했습니다. 재촬영 방법과 전체 이미지는 [스크린샷 갤러리](screenshots/README.md)에 있습니다.
