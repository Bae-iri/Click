# Click Frontend

Click의 Android 앱입니다. 음성·텍스트 요청, 접근성 기반 화면 관측, 하이라이트·음성 안내, 허용된 자동 조작과 결과 확인을 담당합니다. 목표 해석과 작업 경로 선택은 Backend·AI-Agent와 연동하며, 앱은 사용자의 확인과 현재 기기의 화면·상태를 전달합니다.

실행 모듈은 **`:app`**, 온보딩·계정·권한 화면을 제공하는 내부 Android UI 라이브러리는 **`:design-preview`**입니다. 두 모듈을 함께 사용하며, 앱 진입점과 서비스는 `app`에서 관리합니다.

## 개발 환경과 빠른 시작

| 항목 | 기준 |
| --- | --- |
| 언어·JDK | Java 17 / JDK 17 |
| Gradle | Wrapper 8.11.1 |
| Android Gradle Plugin | 8.10.1 |
| Android SDK | minSdk 26 / compileSdk·targetSdk 36 |
| 기본 application ID | `com.clueclick.app` |
| Java namespace | `com.click` |

저장소 전체를 체크아웃하고 Android Studio에서 `Frontend/`를 엽니다. Android SDK 위치는 로컬 `local.properties` 또는 개발 환경에서 설정합니다. 앱 빌드가 `AI-Agent/goal_catalog/`의 공유 계약과 `Backend/docs/`의 테스트 자료를 참조하므로 `Frontend/`만 따로 복사하지 않습니다.

```bash
# 저장소 루트에서
cd Frontend
./gradlew :app:assembleDebug
```

Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다. macOS의 체크아웃 경로에는 `:`를 넣지 않습니다. Java classpath 처리와 충돌할 수 있습니다.

### 빌드 종류

아래 명령은 `Frontend/`에서 실행합니다.

| 빌드 | 명령 | 용도 |
| --- | --- | --- |
| Debug | `./gradlew :app:assembleDebug` | 일반 개발 앱 |
| Validation | `./gradlew :app:assembleValidation` | 준비한 Backend 환경과 주소·fingerprint를 맞춘 검증 앱 |
| UI test driver | `./gradlew :app:assembleUiTestDriver :app:assembleUiTestDriverAndroidTest` | 접근성·오버레이 서비스를 제외한 별도 UI·합성 회귀 테스트 앱 |
| Release | `./gradlew :app:bundleRelease -PCLICK_REQUIRE_RELEASE_SIGNING=true` | 업로드 키로 서명한 배포 번들 |

Validation은 기본 ID에 `.validation`, UI test driver는 `.uitestdriver`를 붙입니다. Validation과 UI test driver는 일반 앱의 카카오 OAuth redirect를 사용하지 않습니다. 릴리스는 HTTPS API 주소와 로컬 서명 설정이 필요하며, 기존 앱을 데이터 유지 방식으로 업데이트하려면 같은 서명키를 사용해야 합니다.

## Backend와 카카오 연결

### 서버 주소

Debug 앱의 기본 API 주소는 `https://click-db.onrender.com`입니다. 다른 서버를 사용할 때는 빌드 속성으로 지정합니다.

```bash
./gradlew :app:assembleDebug -PCLICK_API_BASE_URL=https://YOUR_BACKEND_HOST
```

`CLICK_API_BASE_URL`은 APK를 만들 때 반영됩니다. 서버 주소를 바꾸면 앱도 다시 빌드합니다. 지원하는 목표·명령은 서버 `/openapi.json`에서 확인하며, 서버가 제공하지 않는 명령을 UI에 임의로 표시하지 않습니다.

### 서버와 짝을 맞춘 검증 빌드

먼저 [Backend 안내](../Backend/README.md)에 따라 의존성과 로컬 `.env`를 준비한 뒤 저장소 루트에서 실행합니다.

```bash
cd Backend
python scripts/validation.py prepare --env-file .env --profile jev --gov24 --port 8001
python scripts/validation.py serve
```

별도 터미널에서 저장소 루트를 기준으로 검증 APK를 만듭니다.

```bash
cd Frontend
./gradlew :app:assembleValidation :app:assembleValidationAndroidTest
```

앱은 생성된 `Backend/instance/validation/environment.json`의 주소·환경 ID·fingerprint를 사용합니다. 기본 경로를 바꾸려면 `-PCLICK_VALIDATION_MANIFEST=...`를 지정합니다. 서버 소스·모델 설정·공유 계약이 바뀌면 환경을 다시 준비하고 APK도 갱신합니다. 계약 JSON의 바이트와 줄바꿈 차이도 hash에 영향을 주므로 앱·서버의 계약 불일치를 확인합니다.

### 카카오와 전화번호 로그인

- 카카오 Android Native App Key는 `-PKAKAO_NATIVE_APP_KEY=...`, 환경변수 `KAKAO_NATIVE_APP_KEY`, `Frontend/local.properties` 순서로 읽습니다.
- Backend의 `KAKAO_APP_ID`, 카카오디벨로퍼스 Android 플랫폼·키 해시 등록도 필요합니다. [카카오 연결 설정](../Backend/docs/kakao-login.md)을 따릅니다.
- 전화번호 가입은 이름·전화번호·숫자 4자리 비밀번호를 사용합니다. 문자 인증을 수행하는 방식은 아니며, 서버 OpenAPI의 가입 규격을 확인한 뒤 제출합니다.
- 로그인 토큰은 기기의 암호화 저장소에 보관합니다. API 키, 서명 설정과 `local.properties`는 Git에 올리지 않습니다.

### 로그인 유지와 계정 관리

인증 응답을 검증해 암호화 저장소에 동기 저장하고, 만료된 작업 요청은 토큰 갱신 후 한 번 재시도합니다. 일시적인 통신 오류·429·5xx 때문에 로그인을 지우지 않으며, 이전 세션의 응답이 새 로그인 정보를 덮어쓰지 않도록 사용자와 로그인 세대를 확인합니다.

카카오 SDK 진입 전 새 계정 생성 가능성을 확인받습니다. 탈퇴는 Click 서버의 삭제 성공 이후 로컬 인증·자동 모드 상태를 정리하고 카카오 연결 해제를 요청하며, 외부 연결 해제 실패는 별도로 안내합니다. 일반 로그아웃은 카카오 연결 해제를 요청하지 않습니다. 상세 규칙은 [로그인·계정 수명주기](../docs/authentication-lifecycle.md)를 참고하세요.

## 주요 화면과 사용 흐름

| 영역 | 현재 동작 |
| --- | --- |
| 온보딩·인증 | 글자 크기 선택, 전화번호·카카오 가입/로그인, 권한 안내 |
| 실제 사용법 배우기 | 홈에서 요청을 쓰거나 말하고, 실제 설정 화면의 하이라이트를 따라 연습 |
| 홈 | 음성·텍스트 요청, 목표 확인과 작업 시작 |
| 보관함 | 즐겨찾기·최근 질문 확인과 재사용 |
| 앱 설정 | 권한 상태, 자동 모드, 글자 크기·안내창 투명도, 개선 참여, 계정 관리 |
| 다른 앱 위의 안내 | 플로팅 Click 버튼, 입력·목표 확인·진행·결과·피드백 패널 |

### 권한과 튜토리얼

| 권한·상태 | 용도 |
| --- | --- |
| 접근성 서비스 활성화·실제 연결 | 현재 화면 관측, 하이라이트 대상 확인과 허용된 동작 실행 |
| 다른 앱 위에 표시 | 플로팅 버튼·안내창 표시 |
| 마이크 | 사용자가 시작한 음성 요청 입력 |

접근성 설정 이후 돌아갈 위치는 진입 경로에 따라 정합니다. 초기 가입·로그인은 홈의 요청 입력 연습으로, 홈에서 다시 연 안내는 홈으로, 앱 설정에서 연 안내는 앱 설정으로 돌아갑니다. 접근성 서비스가 실제로 연결됐는지도 확인합니다.

튜토리얼은 홈에서 요청하기 → 실제 디스플레이 설정의 글자 크기 메뉴 찾기 → 앱 설정·보관함 둘러보기로 이어집니다. 화면이 재생성되면 연습 문구와 단계를 복원하고, 연습 종료 후에는 기존 홈 입력 초안을 복구합니다. 문구·음성 안내의 공통 리소스는 [clue_tour.xml](app/src/main/res/values/clue_tour.xml)에 있습니다.

### 오버레이와 요청 제어

- Click 버튼을 누르면 말/글 입력 메뉴가 열립니다. 길게 누른 채 움직여 위치를 바꾸고, 하단 휴지통에 놓으면 숨깁니다.
- Click 앱 안에서는 실제 플로팅 오버레이를 숨기고, 홈 화면·다른 앱에서는 접근성 연결 상태에 따라 표시합니다.
- 입력창을 열거나 TTS가 끝나는 것만으로 녹음하지 않습니다. 마이크를 누르면 한 번 입력받고, 손을 떼면 최종 인식을 기다립니다. 취소·화면 이탈 후의 늦은 콜백은 무시합니다.
- 입력 패널의 X는 접기입니다. 진행 중인 작업을 취소하려면 **요청 중지**를 사용합니다. 기기 처리를 멈추고 서버 요청도 취소하며 늦은 응답을 재사용하지 않습니다.
- 추가 요청은 현재 요청을 덮어쓰지 않고 서버 FIFO 대기열로 관리합니다. 다음 요청도 목표·필수 값·실행 동의를 별도로 확인합니다.
- 안내창은 대상 영역을 가리지 않도록 배치하고, 화면 변화 후 대상 위치를 다시 확인합니다. 큰 글자에서는 줄바꿈·세로 배치·스크롤을 적용합니다.

## 화면 관측·자동 모드·결과 확인

Frontend는 접근성 정보와 사용자 행동을 수집하고, Backend가 전달한 안내를 현재 화면에 적용합니다. Task Graph의 선택·승인과 모델 판단을 앱에서 임의로 대체하지 않습니다.

자동 모드는 사용자가 설명을 확인하고 켰을 때 허용된 메뉴 이동·스크롤·지원 설정 조절을 보조합니다. 매 동작의 허용 여부는 공유 capability, 사용자 동의, 현재 목표·화면·대상, 서버 승인과 기기 정책으로 확인합니다. 중복 실행·횟수·재관측 대기는 별도로 제한합니다. 결제·송금·비밀번호·인증번호 입력 등은 자동 처리 범위에 포함하지 않습니다.

설정 목록에서 목표가 보이지 않으면 현재 창을 제한된 횟수로 스크롤하고 화면을 다시 수집합니다. 화면 전환·일시정지·취소 후에는 예약된 탐색을 실행하지 않습니다. 스크롤 성공을 설정 변경 성공으로 취급하지 않습니다.

글자 크기·밝기·미디어 음량을 직접 조절한 경우에도 지원 서버에는 `manual-setting-evidence.v1` 근거를 전달합니다. 서버가 받은 변경 화면과 같은 작업·대상·방향·안정된 기기값이 일치해야 변경으로 인정하며, 사용자의 만족도 응답과 구분합니다. 자동으로 완료를 확인하기 어려운 경우에는 사용자 확인으로 이어집니다.

관련 계약: [FE 입력·명령](../docs/fe-goal-command-contract.md) · [Graph 전달](../docs/backend-task-graph-dispatch.md) · [실행 정책](../docs/ai-agent-1-runtime-policy.md) · [좌표 검증](docs/guide-target-geometry.md)

### 음성·OCR

- **STT**: Android 12(API 31) 이상에서 온디바이스 한국어 `SpeechRecognizer`를 사용합니다. 로컬 모델이 없거나 지원하지 않으면 글 입력을 안내합니다. Backend에는 인식한 텍스트를 전달합니다.
- **TTS**: 기기에 설치된 한국어 음성 엔진으로 재생합니다. 초기화·재생 실패 상태를 처리하며, 음성이 끝나도 다음 녹음을 자동으로 시작하지 않습니다.
- **OCR fallback**: Android 11 이상 설정 화면에서 접근성 정보가 부족하면 분석 전 동의를 받고 ML Kit 한국어 OCR을 기기 내에서 실행합니다. 해당 경로의 원본 이미지는 외부로 보내지 않습니다.

자세한 범위는 [음성 입력](../docs/stt-model-selection.md), [Vision fallback](../docs/ai-agent-vision-fallback.md)에 정리되어 있습니다.

## 코드 수정 위치

앱 Java 경로는 `app/src/main/java/com/click/` 기준이며, `design-preview/`와 공유 계약 경로는 `Frontend/` 기준입니다. [전체 코드 안내](docs/code-map.md)에서 더 자세한 책임을 확인할 수 있습니다.

| 수정 대상 | 주요 위치 |
| --- | --- |
| 홈·앱 설정 | `HomeActivity.java`, `MenuActivity.java`, `DisplaySettingsActivity.java` |
| 온보딩·인증·권한 UI | `design-preview/src/main/java/com/click/designpreview/`, `RequiredPermissionsActivity.java` |
| 실제 튜토리얼 | `HomeTutorial.java`, `TutorialActivity.java`, `ui/tutorial/` |
| 입력·진행·결과 패널 | `ui/overlay/ClickOverlayLayout.java`, `TaskFlowOverlayView.java` |
| 요청·안내·취소 조율 | `service/overlay/ClickOverlayService.java`, `VoiceRequestQueue.java` |
| 접근성 관측·실행 | `service/accessibility/ClickAccessibilityService.java`, `ForegroundWindowReader.java`, `TouchAutomationPolicy.java` |
| 재관측·탐색·조절 검증 | `service/overlay/ObservationRecovery.java`, `AutomaticScrollSearch.java`, `SettingsAdjustment.java` |
| 서버 API·인증 정보 | `data/remote/`, `data/local/AuthTokenStore.java` |
| 글자·투명도·자동 모드 | `data/local/GuidancePreferences.java`, `TouchAutomationSettings.java` |
| 공유 정책·계약 | `../AI-Agent/goal_catalog/`, 생성 코드는 `app/build/` 아래 |

## 검증

### 단위 테스트와 APK 생성

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

특정 실행·음성·자동 조작 정책만 확인할 때는 다음처럼 필터링할 수 있습니다.

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.click.service.overlay.ClickOverlayServiceExecutionTest' \
  --tests 'com.click.service.overlay.DeviceSpeechInputTest' \
  --tests 'com.click.service.accessibility.TouchAutomationPolicyTest'
```

- JVM 단위 테스트는 `app/src/test/`, UI·기기 테스트는 `app/src/androidTest/`에 있습니다.
- 테스트 보고서는 `app/build/reports/tests/`, APK는 `app/build/outputs/apk/`, 배포 번들은 `app/build/outputs/bundle/`에서 확인합니다.
- 실기기 테스트는 SDK·ADB 연결과 필요한 권한을 준비한 후 대상 클래스별로 실행합니다. `LiveAgentConnectionTest`, `LiveNarrationTest`, `SettingsScrollTest`는 각각 `liveAgent`, `liveSpeech`, `liveScroll` 옵션과 준비 조건을 소스에서 확인합니다.
- `LiveTranscriptionTest`는 남아 있는 서버 전사 경로의 진단이며 현재 앱의 온디바이스 STT 품질 검증과 구분합니다.

인증 회귀는 `AuthRefreshRecoveryTest`, `AuthenticatedHttpClientSessionTest`, `AuthTokenStoreResponseTest`, `KakaoRegistrationConfirmationTest`에 정리되어 있습니다. `AuthSessionDeviceRegressionTest`는 별도 UI test driver와 `http://127.0.0.1:18780`의 합성 서버 전용이며, 운영 계정이나 실제 카카오 SSO 시험으로 실행하지 않습니다. 준비 범위는 [인증 검증 안내](../docs/authentication-lifecycle.md#검증)를 따릅니다.

Robolectric의 SDK runtime 다운로드는 `app/build.gradle`에서 canonical Maven Central(`repo.maven.apache.org/maven2`)로 지정합니다.

### 문제 확인

- **오버레이가 안 보임**: 다른 앱 위 표시, 접근성 서비스 활성화와 실제 연결을 함께 확인합니다. Click 내부에서 오버레이가 숨겨지는 동작은 정상입니다.
- **말 입력을 사용할 수 없음**: 마이크 권한, Android 버전, 한국어 온디바이스 인식 지원을 확인하고 글 입력을 사용합니다.
- **서버·APK 계약 불일치**: 같은 소스와 계약으로 검증 환경을 다시 준비하고 앱을 갱신합니다.
- **기기 오류 조사**: 로컬 [CrashJournal](app/src/main/java/com/click/data/local/CrashJournal.java)과 [CandidateDiagnosticLog](app/src/main/java/com/click/data/local/CandidateDiagnosticLog.java)의 진단 경로를 참고합니다. 로그 공유 전 계정·기기 정보 포함 여부를 확인합니다.

## 작업 기준과 관련 문서

기능 작업은 최신 `develop`에서 시작하고 기존 사용자 변경을 보존합니다. PR에는 변경 범위·실행한 검증·남은 제약을 기록합니다. 루트 [AGENTS.md](../AGENTS.md)의 반영 규칙을 따릅니다.

- [Frontend 아키텍처](../docs/frontend-architecture.md)
- [Backend 실행·연동](../Backend/README.md)
- [앱·오버레이 상태 흐름](../docs/goal-progress-result-flow.md)
- [런처 앱 탐색](docs/launcher-app-search.md)
- [전체 프로젝트](../README.md)
