> 새 온보딩 UI와 기능은 `:app` 하나로 통합되었습니다. `design-preview`는 내부 UI 라이브러리입니다. [변경 기록](../docs/goal-progress-result-flow.md)

# Frontend

파일별 수정 위치와 테스트 실행 방법은 [코드 안내](docs/code-map.md)를 참고한다.

클루 튜토리얼의 말풍선과 버튼 문구는 [clue_tour.xml](app/src/main/res/values/clue_tour.xml)에서 수정한다.
화면과 자동 음성 안내·‘안내 다시 듣기’가 같은 문구를 사용한다. 실제 요청을 처리하는 서버 안내 대본은 별도로 관리한다.

최신 디자인과 실제 기능은 `:app`에서 함께 실행한다.
파란색 Click 스플래시, 웰컴·온보딩, 회원가입, 접근성 권한 안내와 기능 화면이 포함되어 있다.
Android Studio에서 `Frontend/`를 열고 Gradle Sync 후 `app`을 실행한다.
[`design-preview/`](design-preview/README.md)는 `app`이 사용하는 내부 UI 라이브러리다.

온보딩 마지막의 ‘가입하기’에서 회원가입 선택 화면으로 이동한다. 전화번호 가입과 카카오 가입을 제공하며,
아래 ‘로그인하기’에서 전화번호 로그인과 카카오 로그인을 선택한다.
실제 카카오 연결에는 Android `KAKAO_NATIVE_APP_KEY`, Backend `KAKAO_APP_ID`, 카카오디벨로퍼스 Android 플랫폼·키 해시 등록이 필요하다.
[카카오 연결 설정](../Backend/docs/kakao-login.md)을 따른다. 전화번호는 계정 식별자이며 문자 인증은 사용하지 않는다.
전화번호 가입의 이름은 필수이며, 앞뒤 공백을 제거한 이름을 1~50자로 입력해야 한다. 나이·직업은 수집하지 않는다.
전화번호 회원가입은 `name`, `phone_number`, `password`를 전송한다.

2026-11-04 배포 서버 `/openapi.json` 및 백엔드 인증 검증 기준은 `phone_number`와 숫자 4자리 `password`다.
전화번호의 공백·하이픈을 제거한 뒤 국내 휴대전화 형식을 검사하며, PIN의 앞자리 0은 그대로 보존한다.
회원가입·로그인 요청은 같은 검증과 JSON 생성 함수를 사용하고 `email`은 전송하지 않는다.
기존 이메일 계정은 DB에 보존되며, 전화번호 로그인에는 별도의 전화번호 연결이 필요하다.
앱은 가입 시 서버의 전화번호 필드와 4자리 길이 조건까지 확인하며, 다른 규격이면 진행하지 않는다.
가입 기준을 확인하기 전에는 다음 단계로 넘어가지 않으며, 조회 실패 시 재시도를 안내한다.
기준 조회는 응답을 최대 30초 기다리고 일시적인 연결 오류·408·502·503·504에 한 번 재시도한다.
서버에서 확인한 기준은 앱 프로세스 내에서 15분 동안 재사용해 가입 제출 시 중복 조회를 피한다.
유효한 기준을 받지 못하면 임의의 기준으로 진행하지 않으며, 실제 가입 POST는 자동 재시도하지 않는다.
로그인도 숫자 4자리 비밀번호를 입력하도록 안내하고 검증한다.
HTTP 422를 비밀번호 길이 오류로 단정하지 않으며 가입 오류 응답 본문은 로그에 남기지 않는다.

처음 온보딩에서 권한 설정을 마치면 홈에서 클루와 사용법을 연습할 수 있다.
홈이나 앱 설정에서 접근성 안내를 다시 연 경우에는 각각 홈·앱 설정으로 돌아간다.

메인 오버레이의 STT는 `DeviceSpeechInput`에서 Android 12(API 31) 이상의 온디바이스 전용 `SpeechRecognizer`를 사용한다. 한국어(`ko-KR`)와 중간 인식 결과를 요청하며, 마이크 버튼을 놓으면 `stopListening()`으로 결과를 확정한다. 취소·화면 이탈 시 인식을 종료하고 늦은 콜백은 무시한다. 한국어 로컬 모델이 없거나 지원하지 않는 기기에서는 글 입력을 안내한다. Backend에는 인식한 텍스트만 전달한다. 튜토리얼도 같은 마이크 조작과 기기 내 인식을 사용하며, 입력한 문장은 일반 요청과 같은 서버 요청 경로로 실행한다. 입력창을 열거나 TTS가 끝나는 것만으로 녹음하지 않는다. [음성 입력 안내](../docs/stt-model-selection.md)를 본다.

TTS는 휴대폰에 설치된 한국어 음성 엔진을 사용한다. 공통 UI 라이브러리의 manifest에 `TTS_SERVICE` 조회 선언이 포함되며, 초기화 콜백은 메인 스레드에서 처리한다. 초기화 실패 시 대기 중인 안내를 해제하고 재생 불가 상태를 알린다. 실제 기기 확인은 `LiveNarrationTest`에 `liveSpeech=true`를 명시해 실행한다. 고정된 한국어 문장의 재생 완료를 검사하며 마이크는 사용하지 않는다. 완료 콜백과 별도로 미디어 음량과 실제로 들리는지도 확인한다.

STT 자동 검증은 `DeviceSpeechInputTest`와 오버레이 입력 테스트에서 한국어 요청, 중간·최종 결과, 버튼 놓기, 취소·시간 초과와 오래된 결과 무시를 확인한다. 실제 한국어 발화 품질은 온디바이스 한국어 음성 모델이 준비되고 마이크 권한이 허용된 기기에서 별도로 확인한다. `LiveTranscriptionTest`는 남아 있는 서버 전사 API의 합성 WAV 진단용이며 앱의 현재 STT 경로를 검증하지 않는다.

홈과 설정의 사용법 항목에서 다시 연습할 수 있으며, 화면을 회전해도 연습 단계가 유지된다.

Click 앱 내부에서는 실제 플로팅 오버레이를 자동으로 숨기고, 홈 화면이나 다른 앱으로 이동하면 자동으로 표시한다. 접근성 권한과 서비스 연결이 필요하며, 앱 내부 화면 이동·화면 회전 중에는 표시하지 않는다. 튜토리얼 입력은 앱 창 안에서 설명하고, 실행을 시작하면 실제 설정 화면 위에 터치를 통과시키는 클루와 말풍선을 띄운다.
Click 버튼은 홈 화면과 다른 앱 위에서 같은 기능 메뉴를 제공한다.
한 번 누르면 말/글 입력 메뉴가 열리고, 꾹 누르면 휴지통이 나타난다.
누른 채 움직이면 위치를 옮기며, 하단 휴지통에 놓으면 숨긴다. 꾹 누른 뒤 제자리에서 놓으면 숨기지 않는다.
음성 입력창을 열어도 마이크는 켜지지 않는다. 마이크 버튼을 누를 때 한 번만 입력받고,
말을 마치면 녹음을 끝낸다. 안내 음성 재생 후나 인식 실패 후에도 자동으로 다시 켜지지 않는다.
입력 중 마이크를 다시 누르면 취소할 수 있다. 처리 중에는 “사용자 요청을 처리하고 있어요”와
“요청 중지” 버튼을 표시한다. 중지하면 기기 내 안내·화면 처리를 즉시 멈추고 서버의 실행 요청도 취소한다.

메뉴와 입력창은 회원가입 화면과 같은 흰색 카드, 파란색 마이크·버튼, Pretendard 글꼴을 사용한다.
플로팅 본문과 주요 버튼은 기본 18sp, 요청 입력은 20sp, 헤더 조작은 15sp로 표시하고 앱·시스템 글자 배율을 함께 따른다.
큰 글자에서는 헤더 조작과 확인 버튼이 세로로 배치되며 긴 본문은 스크롤한다.
새 요청을 보내면 기존에 줄였던 안내창을 다시 펼쳐 처리·확인 화면을 표시한다. 홈에서는 진행 중 작업의 재개 카드를 표시하거나 이를 위한 대기열 조회를 하지 않는다.

```bash
sh ./gradlew :app:assembleDebug
```

Android 빌드는 경로에 `:`가 없는 폴더에서 실행한다. macOS에서 경로의 `:`는
Java 클래스 경로와 충돌할 수 있다. Android Studio에서 사용 중인
`~/StudioProjects/Click/Frontend`는 이 제한에 해당하지 않는다.

에이전트 연결 시 서버 `/openapi.json`의 지원 명령을 확인한다. `resolve_goal`이 있으면
서버 목표 해석을 사용하고, 이전 서버이면 앱의 승인 목표 카탈로그로 선택지를 표시한다.
사용자가 목표를 확인하면 동일한 `/api/v1/agent/commands`의 `start_task`로 실제
AI-Agent 런타임을 호출한다. 서버에서 제공하지 않는 ‘결과 확인하기’ 명령은 표시하지 않는다.
연결 검증용 `LiveAgentConnectionTest`는 `liveAgent=true`로 명시 실행할 때만 로그인된
계정으로 가상의 설정 화면을 보내며, 런타임 응답을 확인한 뒤 해당 테스트 세션을 취소한다.

Vision fallback은 Android 11 이상 설정 화면에서 매 분석 전 사용자 확인 후 기기 내 OCR을 실행한다. [동의·전송 필드·마스킹 실패 정책](../docs/ai-agent-vision-fallback.md)을 따른다. 앱 업데이트 후 접근성 서비스를 다시 켜 screenshot capability를 반영한다.

설정 목록의 목표 메뉴가 화면 밖에 있다는 `TARGET_NOT_VISIBLE` 응답을 받으면 같은 설정 창의 보이는 목록을 자동 스크롤한다. 한 번 이동할 때마다 화면이 안정된 뒤 접근성 정보를 새로 읽어 서버가 목표를 다시 찾는다. 목록 끝이거나 같은 화면이 반복되면 반대 방향으로 전환하며, 같은 작업 단계·설정 창에서는 최대 4번 탐색한다. 목표를 찾으면 자동 스크롤을 멈추고 다음 행동을 안내한다. 한도에 도달하거나 양쪽으로 움직일 수 없으면 ‘아래 항목 찾기’·‘위 항목 찾기’로 한 번씩 더 탐색할 수 있다. 화면 전환·일시정지·취소 후에는 예약된 스크롤을 실행하지 않는다. 스크롤은 메뉴 탐색이며 설정 변경 성공의 근거로 사용하지 않는다.

실기기 목록 재수집 검증은 앱·AndroidTest APK 설치와 click 접근성 활성화 후 `adb shell am instrument -w -e class com.click.SettingsScrollTest -e liveScroll true com.click.test/androidx.test.runner.AndroidJUnitRunner`로 실행한다. 설정 목록을 한 번 스크롤해 새로 보이는 글자 수집을 확인하고 반대 방향으로 복원하며, 실제 설정값을 변경하지 않는다. 자동 탐색의 목표 발견·탐색 한도·방향 전환·재관측·화면 전환·일시정지·취소와 버튼으로 시작한 탐색은 단위 테스트에서 별도로 검증한다.

Click Android 앱 내부 화면과 다른 앱 위에서 동작하는 Agent Overlay를 구현하는 영역이다.

Frontend는 사용자 음성·텍스트 입출력, Android Accessibility Tree 수집, Guide Command 렌더링과 저위험 자동 스크롤 실행을 담당한다. Goal Typing과 실행 제어용 Command Typing은 FE의 입력 해석 계층이 담당하고, Backend는 DB의 승인 Task Graph를 선택·전달하고 AI-Agent(1)은 전달된 graph에 따른 다음 행동·검증·Re-plan을 담당한다. 이는 갱신된 설계이며 현재 raw-goal DTO의 코드 전환은 남아 있다.

- 관측·행동 ID와 AI1 검증 연동/남은 tap·drag 수집 작업: [AI1 실행 정책](../docs/ai-agent-1-runtime-policy.md#7-fe-전달-형식과-적용-경계)
- Goal·Command 유형과 전달 형식: [FE 입력 계약](../docs/fe-goal-command-contract.md)
- 전체 설계: [Frontend 아키텍처](../docs/frontend-architecture.md)
- UI 강조 좌표와 클릭 영역 처리: [Guide 좌표 계약·검증](docs/guide-target-geometry.md)
- 연동 기준: [AI-Agent 아키텍처](../docs/ai-agent-architecture.md)

## 핵심 역할

- 음성 입력을 STT 모델로 Raw Goal 텍스트로 변환
- 텍스트 입력과 STT 결과를 Backend를 통해 AI-Agent에 전달
- AI-Agent 안내 문장을 TTS 모델로 음성 변환·재생
- Android Accessibility Service로 화면 Node 수집 및 Screen Snapshot 직렬화
- AI-Agent의 Guide Command를 Overlay, Highlight, Cursor, Text/TTS로 출력
- 자동 탭 없이 사용자가 직접 조작하도록 안내
- `LOW_RISK` Scroll Command만 Accessibility Service로 실행
- 사용자 행동 또는 자동 스크롤 이후 변경된 화면 재수집
- 앱 내부 인증, 권한, 온보딩, 홈, 기록, 설정과 피드백 화면 제공

## 기능별 개발 브랜치

Frontend 작업은 파트 전체 브랜치 하나가 아니라 아래 기능 단위로 나눈다. 각 브랜치는 최신 `develop`에서 생성하고 작업 완료 후 Pull Request를 통해 `develop`에 병합한다.

| 순서 | 브랜치 | 주요 작업 | 선행 기능 |
| --- | --- | --- | --- |
| 1 | `feature/android-project-setup` | Gradle, Android 앱 모듈, 공통 패키지, 테마와 테스트 기반 구성 | 없음 |
| 2 | `feature/auth-flow` | 회원가입, 로그인, 로그아웃과 인증 상태 처리 | 프로젝트 설정 |
| 3 | `feature/permission-onboarding` | Overlay·접근성·마이크 권한 요청, 거부·재요청, 튜토리얼 | 프로젝트 설정 |
| 4 | `feature/home-navigation` | 앱 Navigation, 홈 화면, Agent 상태와 주요 화면 진입 | 프로젝트 설정 |
| 5 | `feature/agent-overlay-control` | Agent 아이콘, 마이크 상태, 탭·드래그, 종료 영역과 생명주기 | 권한 설정 |
| 6 | `feature/speech-stt-tts` | STT·TTS 모델 연동, 녹음·변환·재생·중단, 텍스트 fallback | 권한 설정, Overlay |
| 7 | `feature/accessibility-observer` | Accessibility Tree 수집, Screen Snapshot 직렬화, 화면 변경 감지 | 권한 설정 |
| 8 | `feature/agent-session-api` | Raw Goal·Snapshot 전송, Guide Command·Task 상태 수신, 오류·재시도 | 프로젝트 설정 |
| 9 | `feature/guide-overlay` | Target bounds 하이라이트, 커서, 안내 말풍선, 진행·경고·완료 UI | Overlay, Agent API |
| 10 | `feature/low-risk-scroll` | `LOW_RISK` Scroll Command 실행, 상태 표시, 제한 횟수와 중단 처리 | Accessibility, Agent API |
| 11 | `feature/task-history` | 완료 Task 목록과 날짜·성공 여부 상세 화면 | Navigation, Agent API |
| 12 | `feature/user-settings` | 안내 상세도, 권한 관리, 계정 설정 | Navigation, 권한 설정 |
| 13 | `feature/feedback-flow` | Task 완료 후 만족도·의견 입력 | Navigation, Agent API |

## 권장 개발 순서

```text
android-project-setup
├─ auth-flow
├─ permission-onboarding
│  ├─ agent-overlay-control
│  │  └─ speech-stt-tts
│  └─ accessibility-observer
├─ home-navigation
│  ├─ task-history
│  ├─ user-settings
│  └─ feedback-flow
└─ agent-session-api
   ├─ guide-overlay
   └─ low-risk-scroll
```

기능 간 의존성이 있더라도 다른 feature 브랜치를 장기간 기준 브랜치로 사용하지 않는다. 필요한 선행 기능이 아직 `develop`에 병합되지 않았다면 인터페이스와 mock으로 분리하고, 선행 기능 병합 후 최신 `develop`을 반영한다.

## 역할 경계

| 흐름 | Frontend | Backend | AI-Agent(1) |
| --- | --- | --- | --- |
| Goal·Command | STT, Goal/slots·Command 확정 | typed 계약·명령·세션 검증 | 전달된 실행 명령 소비 |
| Task Graph | 목표·환경 전달 | DB 조회·선택·버전 고정·복원·본문 전달 | 전달 graph·goal·실행 상태 정합성 검사 |
| 화면 관찰 | 원본 Node 수집·직렬화 | 관측 검증·전달 | 정규화·State 분류 |
| 안내 | 최신 화면에 Overlay·Text/TTS | 승인·출력 계약 강제 | Target·Guide 결정 |
| 탭·스크롤 | 직접 조작 유도, 허용된 scroll 실행·결과 전달 | 실행 capability·승인 계약 집행 | 전달 graph 내부 허용 행동 선택 |
| 검증·복구 | 새 관측·행동 이벤트 | 결과 저장, graph 재선택 요청 처리 | 결과 검증·graph 내부 Re-plan |

선택·실행 계약은 [Backend Task Graph 전달](../docs/backend-task-graph-dispatch.md)을 따른다. FE는 `agent-fe-contract.v0.2` typed DTO를 `/api/v1/agent/commands`로 보내며 Backend가 선택한 DB graph를 runtime에 주입한다.

## 브랜치 작업 규칙

```bash
git switch develop
git pull origin develop
git switch -c feature/<feature-name>
```

- 한 브랜치에는 표에 정의한 하나의 기능만 구현한다.
- 브랜치 이름은 소문자 kebab-case를 사용한다.
- 공통 기반 변경이 필요하면 해당 기능에 섞지 않고 별도 feature 브랜치로 분리한다.
- PR에는 기능 범위, 의존 기능, 테스트 결과와 미해결 사항을 기록한다.
- 자동 탭, 결제·송금, 비밀번호·인증번호 입력은 구현하지 않는다.

## Overlay 입력과 작업 제어

- Click 버튼을 누르면 말/글 입력 메뉴가 열린다.
- ‘말로 시작’은 입력창만 열고, 창 안의 마이크 버튼을 눌렀을 때 한 번 녹음한다.
- 마이크 버튼을 놓거나 Android 인식 서비스가 발화 종료를 알리면 최종 결과를 기다린다. 다음 녹음은 버튼을 다시 눌러야 시작한다.
- 인식 실패 시 기기 STT 재시도 역시 사용자가 마이크를 다시 눌렀을 때 시작한다.
- 처리 상태와 ‘요청 중지’는 입력 패널 및 작업 안내 화면에서 확인할 수 있다.
- 진행 중에 추가한 요청은 현재 요청을 덮어쓰지 않고 서버 FIFO 대기열에 저장한다. 작업 안내에 대기 요청 수와 앞의 세 요청을 표시한다.
- “와이파이 켜고 밝기 낮춰줘”처럼 독립적인 요청은 한 번에 순서대로 등록한다. 생략한 대상이나 조건문은 추론해서 분리하지 않는다.
- 현재 작업을 완료하면 다음 요청의 목표 확인으로 자동 진행한다. 각 요청의 필수 입력·실행 동의·개인정보 보호 중단은 별도로 유지한다.
- 요청 중지는 기기 내 실행을 즉시 멈추고 대기열 중단 API로 서버 세션을 취소한다. 늦은 응답은 무시하고 대기 요청은 자동으로 시작하지 않는다.
- 오른쪽 위 X는 입력 패널을 작은 click 버튼으로 접고 녹음을 취소한다. 진행 중인 요청의 취소는 ‘요청 중지’를 사용한다.

Android Studio에서 `Frontend` 디렉터리를 프로젝트로 열거나 위의 Debug APK 빌드 명령을 사용한다.

## 클루와 실제 사용법 배우기

1. 움직이는 클루와 얇은 글씨의 말풍선으로 시작한다. 말풍선이나 클루를 누르고, “글자 크기 키워줘”라고 말하거나 적어 보낸다. 처음부터 실제 휴대폰 설정이 바뀐다고 설명한다.
2. 입력한 원문을 `ClickOverlayService`의 일반 요청 큐·서버 확인·안내·결과 확인 경로로 보낸다. 실제 플로팅 확인창에서 내용을 수정하거나 안내를 시작하고, 실제 Android 설정에서 강조된 메뉴와 글자 크기 막대를 조작한다. 연습용 설정 사진과 로컬 완료 화면은 사용하지 않는다.
3. `LiveTutorialGuide`가 해당 요청 ID와 세션에만 연결되어 실제 안내와 누를 위치를 따라간다. 클루 레이어는 `FLAG_NOT_TOUCHABLE`이라 설정과 실제 플로팅 버튼의 조작을 막지 않는다. 말풍선은 설정의 대상·플로팅 패널·키보드를 피해서 배치하고, 실제 안내의 음성을 함께 사용한다. 사용자가 선택한 자동 모드와 패널 접힘 상태를 바꾸지 않는다.
4. 사용자가 실제 작업의 결과를 확인하고 끝내기를 누른 뒤, 요청 종료 응답이 성공해야 홈 튜토리얼을 이어간다. 다른 대기 요청을 취소하지 않는다. 홈 설정의 실제 자동 모드 스위치, 보관함·최근 질문을 살펴보고 실제 홈 탭으로 마친다. 요청이 실패하거나 취소되면 완료한 것으로 넘어가지 않는다.

진행 중에 사용법을 다시 열면 같은 요청 ID로 재개한다. 앱 재시작이나 글자 배율 변경으로 화면을 다시 만들더라도 요청을 중복 등록하지 않는다. 말풍선은 시스템 탐색 영역도 피하고, 키보드를 연 홈 입력 단계에서는 입력창 위의 빈 공간으로 옮긴다.

[SM-N981N의 실제 설정 안내 화면](docs/previews/click-tutorial-live-settings.png)에서 클루와 실제 디스플레이 메뉴의 강조를 확인할 수 있다. 튜토리얼 안에는 이 사진을 띄우지 않고 현재 기기의 화면을 사용한다.

튜토리얼 전용 시작·이전·예시·완료 버튼은 없다. 홈의 설명은 말풍선으로 이어가고 실제 동작은 해당 화면의 컨트롤로 익힌다. 홈 재안내는 설명 종료 뒤 30초간 조작이 없을 때 한 번만 나온다. 녹음·인식 중에는 음성 안내를 쉬며, 실제 실행 중에는 기존 작업 안내가 읽어준다.

접근성 설정 이후의 목적지는 안내를 연 경로로 결정한다.

| 진입 경로 | 서비스 연결 후 목적지 |
| --- | --- |
| 초기 온보딩의 가입·로그인 | 사용법 시작 안내 |
| 홈의 접근성 다시 보기·연결 복구 | 홈 |
| 앱 설정의 접근성 안내·권한 확인 | 앱 설정 |

초기 온보딩만 `PermissionGuideActivity.EXTRA_ONBOARDING`을 전달한다.
`AccessibilityOnboardingState`와 접근성 서비스도 같은 목적지를 사용하므로 서비스 연결 콜백이 홈의 다시 보기를
튜토리얼로 바꾸지 않는다. 처음 여는 안내·안내 닫기는 이전 복귀 대기를 취소하고,
화면 재생성은 진행 중인 복귀 대기를 유지한다. 목적지가 실제로 재개되어야 서비스의 복귀 재시도가 종료된다.
이전 버전의 출처 없는 대기 상태는 온보딩으로 간주하지 않는다.
