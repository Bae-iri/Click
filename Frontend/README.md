# Frontend

Click Android 앱 내부 화면과 다른 앱 위에서 동작하는 Agent Overlay를 구현하는 영역이다.

Frontend는 사용자 음성·텍스트 입출력, Android Accessibility Tree 수집, Guide Command 렌더링과 저위험 자동 스크롤 실행을 담당한다. 사용자 의도 분석, Goal Typing, Task Graph 선택과 다음 행동 판단은 AI-Agent가 담당한다.

- 전체 설계: [Frontend 아키텍처](../docs/frontend-architecture.md)
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

| 흐름 | Frontend | AI-Agent |
| --- | --- | --- |
| Goal | 음성·텍스트 입력, STT, Raw Goal 전달 | Goal Typing, Typed Goal 생성, Task Graph 선택 |
| 화면 관찰 | Accessibility Node 수집 및 직렬화 | Node 파싱·정규화, 화면 State 분류 |
| 안내 | Overlay·Highlight·Text/TTS 렌더링 | Target과 Guide Command 결정 |
| 탭 | 자동 실행하지 않고 사용자 직접 조작 유도 | 탭 대상과 안내 내용 결정 |
| 스크롤 | 허용된 Scroll Command를 실행하고 결과 반환 | Safety 판정, 방향·횟수·중단 조건 결정 |
| 검증 | 변경 화면 재수집 | State 전이와 완료 여부 검증 |

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

## Overlay UI 프로토타입

`feature/agent-overlay-control`의 초기 프로토타입은 다음 상태를 제공한다.

| 상태 | 위치·크기 | 동작 |
| --- | --- | --- |
| 축소 | 화면 우측 하단 `64dp` | 버전 3 말풍선형 Click 아이콘 표시, 탭하면 음성 모드 확장, 드래그로 위치 이동 |
| 음성 | 화면 우측 하단 `218 × 82dp` | 휴지통·Click·마이크 표시, 마이크 ON은 연두색, Click 재탭 시 축소 |
| 텍스트 | 화면 하단 중앙, 최대 `360 × 68dp` | 한 줄 입력 바, 마이크 OFF는 빨간색, 우측 끝에 Click 아이콘 표시 |

- 음성 모드의 휴지통을 탭하면 Overlay Service를 종료한다.
- 음성 모드의 연두색 마이크를 탭하면 텍스트 모드로 전환한다.
- 텍스트 모드의 빨간색 마이크를 탭하면 음성 모드로 돌아간다.
- 텍스트 모드의 Click 아이콘을 탭하면 기본 아이콘으로 축소한다.
- Overlay Window 크기를 상태별 실제 UI 크기로 변경해 투명 영역이 다른 앱의 터치를 가로채지 않도록 한다.

Android Studio에서 `Frontend` 디렉터리를 프로젝트로 열거나 다음 명령으로 Debug APK를 빌드한다.

```bash
cd Frontend
./gradlew :app:assembleDebug
```
