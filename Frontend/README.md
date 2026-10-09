> 새 온보딩 UI와 기능은 `:app` 하나로 통합되었습니다. `design-preview`는 내부 UI 라이브러리입니다. [변경 기록](../docs/goal-progress-result-flow.md)

# Frontend

최신 디자인과 실제 기능은 `:app`에서 함께 실행한다.
파란색 Click 스플래시, 웰컴·온보딩, 회원가입, 접근성 권한 안내와 기능 화면이 포함되어 있다.
Android Studio에서 `Frontend/`를 열고 Gradle Sync 후 `app`을 실행한다.
[`design-preview/`](design-preview/README.md)는 `app`이 사용하는 내부 UI 라이브러리다.

회원가입은 연결된 서버의 `/openapi.json`에서 비밀번호 길이와 지원 필드를 확인한다.
현재 배포 서버는 최소 12글자이며 이름 필드를 받지 않아, 이름은 기기에 저장하고 요청에서는 제외한다.
이름을 지원하는 서버로 업데이트하면 이름도 전송하며 화면 안내 역시 서버 기준을 따른다.
가입 기준을 확인하기 전에는 다음 단계로 넘어가지 않으며, 조회 실패 시 재시도를 안내한다.
로그인에는 가입 당시의 비밀번호를 입력하도록 안내하며 최소 길이를 고정해서 표시하지 않는다.
HTTP 422를 비밀번호 길이 오류로 단정하지 않으며 가입 오류 응답 본문은 로그에 남기지 않는다.

접근성 안내 뒤에는 **click 기능 튜토리얼**이 이어진다. 접근성 허용 안내와 동일한
`PermissionPhoneView`를 배경으로 사용하고, 실제 앱의 `ClickOverlayLayout`을 그 위에 띄운다.
버튼 펼치기 → 글로 입력하기 → 대화 확인 → 마이크 버튼으로 입력 → 요청 중지 → 접기·이동·숨기기를 직접 익힌다.
키보드를 열어 입력한 질문은 플로팅 대화창 안에 표시되며, 별도 입력 팝업을 사용하지 않는다.
연습에서는 녹음하거나 서버로 요청을 보내지 않는다. 배경의 할머니와 설명은 조작에 맞춰 바뀐다.
메뉴의 `튜토리얼`에서 다시 볼 수 있으며, 작성 중인 글·대화·열린 기능은 화면 회전 후 복원된다.

Click 버튼은 홈 화면과 다른 앱 위에서 같은 기능 메뉴를 제공한다.
한 번 누르면 말/글 입력 메뉴가 열리고 꾹 누르면 즉시 숨겨진다.
누른 채 바로 움직이면 위치를 옮길 수 있으며, 이동 중 하단 휴지통에 놓아도 숨길 수 있다.
음성 입력창을 열어도 마이크는 켜지지 않는다. 마이크 버튼을 누를 때 한 번만 입력받고,
말을 마치면 녹음을 끝낸다. 안내 음성 재생 후나 인식 실패 후에도 자동으로 다시 켜지지 않는다.
입력 중 마이크를 다시 누르면 취소할 수 있다. 처리 중에는 “사용자 요청을 처리하고 있어요”와
“요청 중지” 버튼을 표시한다. 중지하면 기기 내 안내·화면 처리를 즉시 멈추고 서버의 실행 요청도 취소한다.

메뉴와 입력창은 회원가입 화면과 같은 흰색 카드, 파란색 마이크·버튼, Pretendard 글꼴을 사용한다.

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
- 문장 입력이 끝나면 마이크를 해제한 뒤 STT를 요청한다. 다음 녹음은 버튼을 다시 눌러야 시작한다.
- 인식 실패 시 기기 STT 재시도 역시 사용자가 마이크를 다시 눌렀을 때 시작한다.
- 처리 상태와 ‘요청 중지’는 입력 패널 및 작업 안내 화면에서 확인할 수 있다.
- 요청 중지는 기기 내 실행을 즉시 멈추고 대기열 중단 API로 서버 세션을 취소한다. 늦은 응답은 무시하고 대기 요청은 자동으로 시작하지 않는다.
- ‘접기’는 입력 패널을 접고 녹음을 취소한다. 진행 중인 요청의 취소는 ‘요청 중지’를 사용한다.

Android Studio에서 `Frontend` 디렉터리를 프로젝트로 열거나 위의 Debug APK 빌드 명령을 사용한다.
