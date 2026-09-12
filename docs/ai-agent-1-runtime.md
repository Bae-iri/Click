# AI-Agent(1): 사용자 작업 수행 Runtime Agent

## 1. 문서 목적

이 문서는 **AI-Agent(1)**의 목표 아키텍처와 런타임 흐름을 정의한다. AI-Agent(1)은 FE가 확정한 **Goal Type과 Command Type**을 받아 **적절한 승인 Task Graph를 선택하는 단계부터** 시작한다. 이후 현재 화면을 읽어 guide/action을 결정하며, 새 관측으로 결과를 검증하고 Re-plan하는 사용자용 에이전트다. 특정 Task 하나에 종속되지 않고, Android 설정 앱, 커머스 앱, 공공 서비스 앱 등 여러 절차형 Task에 공통으로 적용되는 Agent 파이프라인을 설명한다.

이 문서에서 `Agent`는 별도 표시가 없으면 AI-Agent(1)을 뜻한다. AI-Agent(2)는 수집된 runtime 데이터에 success/fail 등 결과 라벨을 붙이고, 내부 데이터셋과 Task Graph 추가·업데이트 후보를 만드는 별도 에이전트다.

- [FE Goal Type·Command Type 정의와 전달 계약](./fe-goal-command-contract.md) — 자연어 분류, 실제 Goal 유형, slots, 요청 형식의 기준 문서
- [두 에이전트의 전체 구조와 책임 경계](./ai-agent-architecture.md)
- [AI-Agent(2): Runtime Data / Graph Proposal Agent](./ai-agent-2-graph-proposal.md)

Task별 State, Transition, UI 후보, 예외 처리는 별도 문서에서 관리한다.

- Task 상세 문서: [Android 설정 앱에서 글자 크기 확대](./tasks/font-size-increase.md)

이 문서는 “현재 코드가 전부 완성했다”는 의미가 아니라, 우리가 목표로 삼는 이상적인 AI Agent 구조를 기준으로 삼는다. 단, 현재 코드에 이미 들어온 런타임 단계는 별도로 표시해 앞으로의 구조에 흡수한다. 기능 확인 기준은 2026-09-12의 `a0d3609`이며, 파일 경로와 책임 경계는 이번 패키지 분리 작업을 반영했다. JSON은 명시적으로 현재 형식이라고 적은 경우를 제외하면 설계 예시이며, 실제 HTTP 계약은 `Backend/app/schemas/agent_turn.py`, runtime 타입은 `AI-Agent/src/ai_agent/runtime/types.py`를 기준으로 한다. 예시 confidence와 threshold는 운영 측정값 또는 확정 성능 목표가 아니다.

**2026-09-12 책임 변경:** Goal Typing과 Command Typing은 FE 소유로 이관한다. 이 문서의 실행 설계는 이 결정을 따른다. 이번 변경은 문서 정리이며, 현재 Python engine의 `type_goal()` 호출과 `raw_goal` HTTP 계약은 아직 남아 있다. 실제 코드 전환 위치와 API 버전 전환은 [별도 계약의 구현 전환 항목](./fe-goal-command-contract.md#11-현재-코드와-구현-전환)을 따른다. 새 typed JSON이 현재 API에 이미 적용된 것으로 해석하지 않는다.

## 2. Agent 목표

Agent는 사용자가 익숙하지 않은 모바일 앱에서 스스로 목적을 완료할 수 있도록 현재 화면을 이해하고 다음 행동을 단계별로 안내한다.

MVP와 초기 제품의 기본 원칙은 Human-in-the-loop이다.

- FE는 사용자 발화를 Goal Type·slots와 실행 제어용 Command Type으로 확정한다.
- Agent는 전달받은 값의 형식, 등록 여부, 세션 상태를 검증한다.
- Agent의 작업 판단은 해당 Goal에 맞는 승인 Task Graph 선택 또는 승인 Task Template의 slots 구체화부터 시작한다.
- Agent는 현재 화면의 UI 요소를 분석한다.
- Agent는 현재 화면이 Task Graph의 어느 State인지 판단한다.
- Agent는 다음 State로 가기 위해 눌러야 할 UI 또는 조작해야 할 UI를 선택한다.
- Agent는 Safety Gate를 통해 안내 가능한 행동인지 판단한다.
- Agent는 Guide Command를 생성한다.
- FE는 Guide Command를 받아 Overlay, Highlight, 안내 문구를 렌더링하고 TTS 음성을 출력한다.
- 사용자는 실제 tap, drag, 입력, 확인을 직접 수행한다.
- Agent는 조작 이후 화면 변화를 확인한다.
- Agent는 예상과 다르면 재계획한다.
- Agent는 대상 UI 탐색을 위한 scroll만 제한적으로 허용할 수 있다.
- Agent는 전체 실행 과정의 trace와 실시간 verification 결과를 남긴다. 데이터 라벨 확정, Task Graph 추가·수정 후보 생성, dataset 생성은 AI-Agent(2)가 담당한다.

현재 코드에서 추가된 중요한 방향:

- Agent는 `session_id`를 기준으로 task 진행 상태를 유지한다.
- Agent는 이전 Guide Command와 pending transition을 저장해 다음 observation에서 성공/실패를 검증한다.
- Agent는 같은 target이 반복 실패하면 해당 node를 제외하고 다시 matching한다.
- Accessibility Tree가 비어 있거나 target matching이 실패하면 동의 기반 Vision fallback을 사용할 수 있다.
- Backend는 DB에 저장한 요청 결과로 같은 요청의 중복 실행을 막는다. 재전송 시 personalization 동의는 다시 평가하므로 최종 안내 문구까지 항상 동일하다는 뜻은 아니다.
- 사용자 personalization 동의가 있으면 안내 문구를 사용자 이해 수준에 맞게 조정할 수 있다.

## 3. 입력 구조

FE는 STT/텍스트 입력 이후 Goal Typing과 Command Typing을 수행하고, Backend를 통해 확정된 구조를 Agent에 전달한다. 의미가 모호하거나 필수 slot이 빠졌으면 FE에서 먼저 질문한다. Agent는 원문에서 의도를 다시 추론하거나 빠진 slot을 임의로 채우지 않는다.

Agent가 소비하는 정보는 다음과 같다. 필드·enum·완전한 JSON 예시는 [FE Goal·Command 전달 계약](./fe-goal-command-contract.md)에 한 곳에서 관리한다.

| 입력 | Agent에서의 용도 |
| --- | --- |
| `command_type` | 새 작업 시작, 일시정지, 재개, 취소, 교체, 관측·확인 응답 분기 |
| `goal.goal_type` | 승인 graph의 정확한 lookup key |
| `goal.target_app`, `goal.slots`, `goal.catalog_version` | graph 적합성·필수 parameter·catalog 호환성 검사 |
| `request_id`, `session_id`, `expected_version`, `episode_id` | 멱등성, 소유권, 진행 중 작업 식별 |
| `accessibility_tree` 또는 동의된 `vision_snapshot` | 현재 화면 판단, UI 선택, 이전 step 검증 |
| `user_action_event`, `user_confirmation`, `confirmation_id` | 실제 사용자 행동과 현재 확인 대상 연결 |

신규 시작과 목표 교체에서만 새 `goal`을 받는다. 재개·일시정지·취소·후속 관측은 저장된 goal과 고정 graph를 사용한다. FE의 원문 `source_text`는 선택적 설명 정보이며 graph 선택의 입력 근거가 아니다.

입력 `command_type`은 사용자 실행 제어이고, 출력 `guide_command.command_type`은 `highlight`·`confirmation` 등 표시 방식이다. 두 enum을 공유하지 않는다. `resume_task`가 들어와도 관측·안전 검증을 생략하거나 예전 좌표를 바로 재출력하지 않는다.

현재 HTTP v1은 첫 turn의 `raw_goal`을 요구한다. 위 typed 입력은 구현 전환 후의 계약이며, 화면 관측·확인·인증의 현재 wire 형식은 [기존 FE/BE 연동 계약](./frontend-backend-contract.md)을 참고한다.

## 4. 핵심 설계 원칙

### 4.1 전달된 Goal 검증과 승인 Task Graph 선택

Agent의 첫 업무는 **FE가 확정한 Goal에 실행 가능한 graph를 연결하는 것**이다. 발화 정규화, embedding 기반 Goal 검색, slot extraction, Goal confidence 계산은 [FE 입력 처리 문서](./fe-goal-command-contract.md)로 분리한다.

1. Backend가 계약 버전, 인증·소유권·멱등성 및 세션 version을 검증한다.
2. Command Router가 실행 제어 명령을 분기한다. 일시정지·취소는 graph 선택과 화면 추론 없이 처리하며, 재개·관측·확인 응답은 저장된 graph를 복원한다.
3. 새 시작·교체에서는 `goal_type`을 catalog/registry의 canonical key로 조회한다. 가까운 다른 Goal로 대체하지 않는다.
4. 등록된 Goal의 `target_app`과 slot 타입·허용값·필수값을 검사한다. FE confidence와 `is_supported` 주장으로 이 검사를 대체하지 않는다.
5. 승인 상태, platform·OS·앱 버전, locale, FE 실행 capability 등 알려진 환경 조건으로 호환 graph 후보를 좁힌다. 현재 foreground app이 대상 앱과 다르다는 이유만으로 Goal을 바꾸지 않는다.
6. 하나의 호환 graph를 선택한다. 여러 variant라면 registry의 명시적 우선순위를 적용하고, 우선순위까지 같은 모호한 경우에는 `GRAPH_SELECTION_AMBIGUOUS`로 정지한다.
7. 선택된 `task_id`, graph version/hash, 승인 template parameter를 episode에 고정한다. `task_type`은 선택된 graph의 metadata에서 결정한다.
8. 고정 graph의 시작·복구 경로를 기준으로 현재 화면을 관측하고 실행 루프를 시작한다.

필수값 누락·slot 충돌은 `INVALID_GOAL_PAYLOAD`, 미등록 Goal은 `UNSUPPORTED_GOAL`, 호환 graph가 없으면 `NO_COMPATIBLE_GRAPH`로 FE에 돌려준다. 이는 제안 오류 코드이며 기존 API에 이미 구현된 값은 아니다. Agent가 source text를 다시 분류해서 오류를 숨기지 않는다. 미지원 수요는 정책상 허용된 event로 AI-Agent(2)에 전달한다.

현재 구현에서 확인되는 매핑은 `settings.font_size.increase` → `MVP-01` 하나다. Python registry에는 두 key가 같은 graph를 가리킨다. 이는 두 종류의 Goal이 아니다. 버전이 있는 다중 variant registry와 typed 입력 검증은 추가 구현 대상이다.

### 4.2 UI 판단 원칙

UI 판단은 Accessibility Tree 기반의 keyword matching과 embedding matching을 우선한다.

우선순위:

1. Accessibility Tree 수집
2. UI Normalizer로 Node 정규화
3. Screen State Classifier로 현재 State 판단
4. Task Graph / State Manager로 가능한 Transition 조회
5. keyword matching으로 명확한 후보 탐색
6. resourceId, role, bounds, enabled/actionable 상태로 후보 보정
7. embedding similarity로 의미적으로 가까운 후보 탐색
8. top-1 confidence와 top-2 gap 확인
9. confidence가 충분하면 Safety Gate로 전달
10. confidence가 낮으면 LLM/VLM fallback 또는 사용자 확인

현재 코드 반영:

- `normalize_accessibility_tree()`는 label 병합, role 추론, semantic tag 생성, position hint, slider value 추출을 수행한다.
- `parent_id`를 따라 이름 없는 클릭 영역에 자식 label을 연결하고 실제 조작 노드의 ID·bounds를 유지한다. 숨김 노드는 제외한다. [좌표 계약과 제한](../Frontend/docs/guide-target-geometry.md)을 따른다.
- `classify_screen_state()`는 required keyword group, optional keyword, role hint, package, previous state continuity로 State를 고른다.
- `match_next_ui()`는 keyword, similarity, semantic tag, role, actionable score를 합산한다.
- 현재 matcher는 top-2 gap 기반 ambiguity 처리가 없으므로 이상 구조에는 반드시 추가해야 한다.

### 4.3 LLM/VLM 사용 범위

Agent(1)의 LLM/VLM은 화면 해석·UI matching을 보완하는 fallback으로만 사용한다. Goal/Command 분류의 모델 선택과 clarification은 FE 책임이며 별도 문서에서 정의한다.

적용 상황:

- Accessibility Tree에 label이 없거나 부정확한 경우
- 아이콘 중심 UI라 keyword matching이 실패한 경우
- 제조사/앱 버전 차이로 기존 Task Graph와 맞지 않는 경우
- 팝업, 권한 요청, 오류 화면 등 예외 화면이 등장한 경우
- embedding 및 keyword matching confidence가 낮은 경우

현재 구현된 Vision fallback의 runtime 위치:

1. FE가 Accessibility Tree를 수집하지 못하면 `vision_snapshot`을 보낸다.
2. `vision_snapshot.user_consented`가 false면 Agent는 화면 이미지 분석 동의 요청 command를 반환한다.
3. 동의된 `vision_snapshot.elements` 또는 OCR 결과는 `vision:{element_id}` synthetic node로 변환한다.
4. 변환된 node는 기존 `Normalize -> Classify -> Match -> Guide` 흐름에 합류한다.
5. `sanitize_request()`는 screenshot 원문을 제거하고, 동의된 OCR/element text를 redaction한 뒤 사용한다. trace/debug에는 allowlist에 있는 상태와 수치만 남긴다.
6. 서버 이미지 redaction이 준비되지 않아 원본 screenshot의 외부 VLM 호출은 차단되어 있다. 동의 flag만으로 이미지 전송을 허용하지 않는다.

Vision fallback status:

- `not_requested`: vision fallback을 사용하지 않음
- `consent_required`: 외부 화면 이미지 분석 동의 필요
- `used`: vision payload를 Agent 판단에 사용함
- `not_configured`: provider 또는 입력 정보 부족
- `provider_error`: 외부 provider 호출 실패
- `no_signal`: 화면 분석 결과에서 유효한 UI signal 없음

Fallback 연결 규칙:

- Agent 응답은 `metadata.debug.observation`에 실제 수신한 Accessibility/Vision 입력 요약을 남긴다.
- engine 내부 debug는 fallback 이유와 복구 정보를 구성하지만 Backend `safe_response()`는 debug를 허용된 enum·수치만으로 축소한다. 클라이언트가 필요한 복구 동작은 별도 guide/render policy 계약으로 전달해야 한다.
- `accessibility_tree.nodes`가 빈 경우는 관측 실패로 보고, vision snapshot이 없으면 정보 부족 fallback command로 응답한다.
- `render_policy.recovery_action`은 FE가 사용자 활성화 기반으로 설정 앱 열기 또는 앱스 화면 이동을 제공해야 하는 경우 사용한다.
- `render_policy.fallback_action.type=request_fresh_observation`은 현재 viewport에서 target을 찾지 못했으니 fresh tree와 필요 시 vision snapshot을 다시 보내라는 의미다.
- 같은 logical state로 분류되더라도 화면 fingerprint가 바뀌었으면 `NO_CHANGE`가 아니라 `UNKNOWN`으로 기록해 스와이프, 앱스 화면 이동, 수동 이동을 단순 실패로 오판하지 않는다.
- 현재 runtime trace는 허용된 진단 상태·수치만 보존한다. training/evaluation에 필요한 observation·action·verification 연결과 snapshot은 별도의 개인정보 정책을 갖춘 event 저장 계약으로 추가해야 한다.

비판적 기준:

- LLM/VLM은 core planner가 아니라 fallback/annotation layer여야 한다.
- 외부 image provider를 다시 연결할 경우 graph transition과 target selector를 context로 제공하고 서버 측 이미지 redaction을 먼저 갖춰야 한다.
- screenshot redaction은 “FE가 해주면 좋다”가 아니라 contract 수준에서 강제해야 한다.

### 4.4 자동 조작 제한

MVP에서는 자동 tap을 제공하지 않는다. Agent는 사용자가 눌러야 할 위치를 Guide Command로 만들고, FE는 이를 Overlay로 렌더링하지만, 실제 tap은 사용자가 직접 수행한다.

단, target UI가 현재 viewport에 없고 아래/위로 이동해야 발견될 가능성이 높은 경우 architecture 차원에서는 scroll을 제한적으로 허용할 수 있다.

- tap, long press, text input, submit, purchase, consent는 자동 수행하지 않는다.
- scroll은 탐색 목적의 LOW_RISK action으로만 허용한다.
- scroll 전후에는 Accessibility Tree를 재수집해 target UI 발견 여부를 검증한다.
- scroll 횟수와 방향은 Task Graph의 `scroll_policy`로 제한한다.
- scroll 중 사용자가 직접 조작하면 Agent는 즉시 재관찰 루프로 돌아간다.
- scroll은 FE의 Android Accessibility Service가 실행하고, FE는 실행 중 상태를 시각적으로 표시한다.
- 결제·동의·개인정보 입력 등 민감 화면에서는 자동 scroll도 중단한다.

현재 코드 반영:

- `ActionType.SCROLL`과 matcher의 scroll fallback branch는 존재한다.
- 현재 MVP-01 Task Graph의 모든 transition은 `allow_scroll=False`다.
- `runtime/contracts.py`의 현재 allowed guide actions에는 scroll이 제외되어 있다.
- 따라서 현재 제품 기준에서는 scroll이 “목표 아키텍처에 포함된 단계”이지 “완성된 기능”은 아니다.

### 4.5 AI-Agent(2)와의 책임 경계

AI-Agent(1)은 승인된 graph를 읽어 현재 사용자 세션의 경로를 선택한다. 이미 승인된 template에 slots를 대입하는 것은 허용되지만, 미등록 목표에 대해 새 graph를 즉석 생성해 실행하지 않는다.

| 작업 | 담당 |
| --- | --- |
| 현재 화면 판단, 다음 guide/action 결정 | AI-Agent(1) |
| 직전 행동 결과 검증 및 세션 내부 Re-plan | AI-Agent(1) |
| 관측·후보·안내·행동·검증·중단·오류 trace 생산 | AI-Agent(1), FE, Backend |
| 세션 전체 재구성, step/task 단위 success/fail 라벨링 | AI-Agent(2) |
| 신규 Task Graph, State, Transition 및 selector 개선 후보 | AI-Agent(2) |
| dataset, 평가 보고서, graph release candidate | AI-Agent(2) |
| 승인 정책 집행, artifact 게시와 rollback | 내부 승인·배포 경계 |

AI-Agent(1)의 Re-plan은 **현재 세션의 선택을 바꾸는 작업**이다. AI-Agent(2)의 Graph Update는 **이후 세션들이 사용할 versioned graph를 바꾸는 작업**이다. Re-plan이 발생했다는 사실 자체는 task 실패 라벨이 아니다.

AI-Agent(2)가 지연되거나 중단되어도 AI-Agent(1)은 마지막으로 승인된 호환 graph로 동작해야 한다. 단, trace 저장 실패는 기록하고 재처리할 수 있어야 하며, 데이터 유실을 수집 성공으로 보고하지 않는다.

## 5. 사용자 작업 수행 루프

실행 turn은 다음 안내 하나를 반환하고, 일시정지·취소 turn은 UI target 없는 제어 응답을 반환한다. 사용자의 조작을 기다린 뒤 **다음 turn의 새 화면으로 직전 안내를 검증**한다. 하나의 관측을 두 번 성공 처리하지 않는다.

`FE Goal Type + Command Type → Session/Request 검증 → Command 분기 → 승인 Graph 선택/복원 → Observe → Normalize → Classify → 직전 Step Verify → 완료 확인 또는 Re-plan → Transition 선택 → UI Match → Safety → Guide/허용 Action → 새 관측`

```mermaid
flowchart TD
    A[FE 확정 Goal Type + Command Type] --> B[Backend 인증·세션·멱등성 검증]
    B --> C{Command 분기}
    C -->|일시정지·취소| CTRL[상태 저장·기존 Guide 무효화·제어 응답]
    C -->|재개·관측·확인| RESTORE[기존 Goal·고정 Graph 복원]
    C -->|시작·교체| D{등록 Goal·Slots·호환성 검증}
    D -->|불충분| E[계약 오류 / 미지원 결과를 FE에 반환]
    D -->|충분| F[승인 Graph 선택·버전 고정]
    RESTORE --> G
    CTRL -.-> Y
    F --> G[현재 화면 관측]
    G --> H{Accessibility 충분}
    H -->|아니오| I{Vision 동의·마스킹·사용 가능}
    I -->|아니오| J[동의 요청 / 새 관측 / 중단]
    I -->|예| K[Vision 결과를 Node로 변환]
    H -->|예| L[Normalize / Classify]
    K --> L
    L --> M[Pending Step 검증 및 시도 이력 갱신]
    M --> N{목표 완료 근거 충족}
    N -->|예| O[완료 안내 및 Episode 종료]
    N -->|아니오| P[Re-plan 판단 / Transition 선택]
    P --> Q[UI 후보 Matching]
    Q --> R{신뢰도·모호성 기준 통과}
    R -->|아니오| J
    R -->|예| S[Safety Gate]
    S -->|차단| T[Blocked 안내]
    S -->|확인 필요| U[현재 Guide에 대한 사용자 확인]
    U -->|확인 후 재검증| S
    S -->|허용| V[Guide 또는 허용된 Action Command]
    V --> W[FE Overlay / 사용자 조작]
    W --> X[다음 Turn의 새 화면]
    X --> B
    B -.-> Y[전체 경로의 Runtime Event 수집]
    E -.-> Y
    J -.-> Y
    M -.-> Y
    O -.-> Y
    T -.-> Y
    V -.-> Y
    Y -. 비동기 .-> Z[AI-Agent 2 입력]
```

이 다이어그램의 typed 입력·Command Router, 승인 graph 버전 고정, 모든 경로의 영속 event 기록, 명시적인 observation/step 연결은 목표 계약이다. 현재는 Python graph registry, DB checkpoint 호환성 검사, 일부 경로의 메모리 trace가 구현되어 있다.

Vision을 matching 실패 후 다시 시도하더라도 동의·마스킹 경계를 동일하게 적용한다. 확인을 받은 경우에도 현재 transition, 화면 시각, target 유효성을 다시 검사하며, 거절은 추가 조작을 허용하지 않는다.

## 6. 시스템 계층 구조

| 계층 | 핵심 질문 | 담당 모듈 | 결과물 |
| --- | --- | --- | --- |
| Request-level | 같은 요청이 재시도인가? 세션이 유효한가? | Backend AgentTurnService, Session Store | Idempotent Response, Session State |
| Command-level | 현재 작업을 시작·정지·재개·교체하는가? | Command Router, Session Store | 실행 제어 상태·응답 |
| Graph-selection-level | FE가 확정한 Goal을 어떤 승인 graph로 실행할 수 있는가? | Typed Input Validator, Graph Selector | Task ID, Graph Reference, 검증된 slots |
| Task-level | 목표를 위해 어떤 State를 거쳐야 하는가? | Task Graph, Graph Registry, State Manager | State, Transition, Graph Version |
| Screen-level | 현재 화면은 어떤 State인가? | Accessibility Tree Parser, UI Normalizer, Screen State Classifier | Current State, State Confidence |
| Verification-level | 직전 안내 이후 의도한 화면 전이가 일어났는가? | Verifier, Snapshot Fingerprint | SUCCESS, DONE, NO_CHANGE, WRONG_STATE, UNKNOWN |
| Replanning-level | 실패한 target/transition을 어떻게 처리할 것인가? | Replanner, Attempt Tracker | Excluded Nodes, Alternate Transition |
| UI-level | 다음 State로 가기 위해 어떤 UI를 선택해야 하는가? | Keyword Matching, Embedding Matching, Ranker | Target Node, Match Confidence |
| Safety-level | 이 행동을 안내하거나 실행해도 되는가? | Rule-based Safety Gate, Policy Engine | LOW_RISK, CONFIRM_REQUIRED, BLOCKED |
| Guide-level | 사용자에게 무엇을 보여줄 것인가? | Guide Command Builder, Personalization | Guide Command |
| Presentation-level | 안내를 실제 화면에 어떻게 렌더링할 것인가? | FE Overlay Renderer | Overlay, Highlight, Cursor, Text Guide |
| Execution-level | 허용된 자동 탐색 행동이 있는가? | FE Accessibility Service | Scroll Result |
| Fallback-level | 규칙 기반 판단이 실패했는가? | LLM/VLM Fallback, Recovery Action | 보조 판단, 동의 요청, 관측 요청 |
| Trace-level | 이번 turn의 사실과 판단 근거를 어떻게 남기는가? | Trace Producer, Backend Event Store | AI-Agent(2)에 전달할 Runtime Event |

## 7. 모듈별 책임

### 7.1 Request / Session / Idempotency

Agent는 turn 단위로 호출되지만, 사용자의 task는 여러 turn에 걸쳐 진행된다. 따라서 request와 session을 분리해야 한다.

역할:

- `request_id` 중복 호출을 감지해 저장된 기본 응답을 재사용하고 personalization 동의를 다시 적용한다.
- `session_id`와 `episode_id`를 기준으로 FE 확정 goal, task id, graph reference, current state를 유지한다.
- `observe`·`resume_task` 등 후속 명령은 새 goal 없이 저장된 goal과 graph를 복원한다.
- 존재하지 않는 session·이전 episode를 가리키는 후속 명령은 거부한다.
- `replace_task`는 새 goal의 검증·graph 선택이 성공한 경우에만 이전 episode를 교체한다.
- `pause_task`는 재개 가능한 상태로 보존하고 `cancel_task`는 종료한다. 둘을 동일하게 처리하지 않는다.
- 위 제어 상태와 typed 입력 규약은 목표 계약이며 현재 raw-goal runtime과 구분한다.

현재 코드 반영:

- Backend `AgentSessionRepository`가 DB 기반 turn claim, 응답 저장, session version, lease를 관리한다.
- `AgentTurnService`는 저장된 checkpoint를 복원해 turn별 runtime을 만들고 처리 후 checkpoint를 저장한다.
- `ClickAgentEngine` 자체는 `InMemorySessionStore`를 사용하지만 Backend 경로는 이 상태를 DB에 checkpoint한다.
- checkpoint에는 schema version과 graph 내용 hash가 저장된다. 불일치 시 `CHECKPOINT_INCOMPATIBLE`로 거부하며, 과거 graph artifact를 찾아 계속 실행하는 다중 버전 registry는 아직 연결되어 있지 않다.
- 영속 checkpoint와 replay 가능한 전체 runtime trace 저장은 서로 다른 요구사항이다.

### 7.2 Typed Input Validator / Command Router / Graph Selector

이 모듈은 FE가 전달한 enum과 parameter를 검증하고, 실행 명령에 맞게 승인 graph를 선택하거나 기존 실행 상태를 복원한다.

| 구성 요소 | 책임 | 결과 |
| --- | --- | --- |
| Typed Input Validator | 계약 버전, 등록 Goal, 대상 앱, slot schema·고정값 일치 확인 | 검증된 FE Goal 또는 구조화된 오류 |
| Command Router | Command별 허용 세션 상태·episode 확인, 시작·정지·재개·교체 분기 | 실행 경로 또는 제어 응답 |
| Graph Selector | Goal/app/환경/capability와 승인 registry의 정확한 매핑 | task ID, graph version/hash, parameter |
| Session Restorer | 후속 명령의 고정 goal·graph 복원, 과거 확인·좌표 유효성 검사 | 현재 episode의 재관측 가능한 상태 |

대표 매핑은 `goal_type=settings.font_size.increase`, `target_app=android_settings`,
`slots={setting: font_size, direction: increase}` → `task_id=MVP-01`이다.
`task_id`·graph version·graph hash는 Agent/registry가 결정한다. FE가 실행 graph를 지정하거나
`source_text`만 보내어 Agent 내부 Goal Typer를 호출하는 경로는 새 계약에 포함하지 않는다.

지원 유형·입력 필드·Command별 세부 규칙은 [FE Goal·Command 계약](./fe-goal-command-contract.md)이 기준이다. 이 모듈은 분류 점수를 계산하거나 모호한 자연어를 해결하지 않는다. 입력 오류의 필드 경로와 원인을 FE에 전달하면 FE가 사용자에게 확인 후 새 요청을 만든다.

현재 코드에는 별도 Typed Input Validator/Command Router가 없고 `engine._resolve_typed_goal()`이 여전히 `runtime/goal_typing.py`를 호출한다. 이 호출 제거, typed DTO와 router 추가, Backend adapter·checkpoint·tests 전환이 필요하다. Goal Typing을 Agent(1)의 확장 과제로 계속 추가하지 않는다.

### 7.3 Task Graph / Graph Registry / State Manager

Task별로 정의된 State와 Transition을 관리한다.

역할:

- 현재 Task의 시작 State와 완료 State 관리
- 현재 State에서 가능한 다음 Transition 조회
- graph version 고정
- 실패한 Transition 기록
- failed target node 제외
- 대체 경로 탐색
- `scroll_policy` 적용
- experiment variant 기록

Task Graph는 전체 구조 문서에 직접 상세 정의하지 않고 Task별 문서에서 관리한다.

현재 코드 반영:

- `MVP_01_TASK_GRAPH`는 Python dataclass로 정의되어 있다.
- 같은 graph가 `MVP-01`과 `settings.font_size.increase` key로 registry에 등록된다.
- session에는 `pending_transition_id`, `pending_confirmation_transition_id`, `last_target_node_id`, `failed_node_ids`, `transition_attempts`, `last_snapshot`이 저장된다.

개선 필요:

- Python graph와 JSON graph 중 하나를 source of truth로 정해야 한다.
- graph version은 runtime object에 명시되어야 한다. engine의 trace 입력에는 `mvp-01.v1` 상수가 있지만 실제 진단 저장에서는 제외된다. Backend checkpoint의 graph hash와 함께 영속 event/artifact version으로 연결해야 한다.
- session 시작 시 graph version을 고정해야 한다.

### 7.4 Accessibility Tree Parser

FE의 Android Accessibility Service가 수집·직렬화한 Screen Snapshot을 전달받아 원본 UI Tree를 파싱한다. Android 프레임워크를 통한 실제 수집은 FE가 담당한다.

수집 대상:

- `package_name`
- `class_name`
- `text`
- `content_description`
- `resource_id`
- `bounds_in_screen`
- `parent_id` (같은 snapshot 안의 부모 ID, 기본 `null`)
- `visible_to_user` (Android 표시 여부, 기존 payload의 기본값 `true`)
- `clickable`
- `enabled`
- `selected`
- `scrollable`
- `checkable`
- `extras`

현재 코드 반영:

- Backend API는 `AccessibilityTree`와 `AccessibilityNode` Pydantic schema로 입력을 검증한다.
- Runtime adapter가 Backend schema를 runtime dataclass로 변환한다.
- tree-level package name이 없으면 node package에서 추론한다.

### 7.5 UI Normalizer

FE가 원본 속성을 유지해 직렬화한 Accessibility Node를 Agent가 비교하기 쉬운 정규화 Node로 변환한다. label 병합, role 추론, semantic tag 생성 등 의미 기반 정규화는 Agent가 담당한다.

정규화 기준:

- `text`와 `contentDescription`을 `label` 후보로 병합
- 버튼, 리스트 항목, 슬라이더, 토글 등 `role` 추론
- 화면 위치를 `top`, `middle`, `bottom` 등으로 변환
- 비활성, 숨김, 중복 Node 제거
- slider/range value 추출
- Task별 synonym dictionary 적용

현재 구현은 비활성 노드를 화면 판별용으로 유지하되 조작 대상에서는 제외한다.
숨김 노드는 정규화 결과에서 제외하고, 양의 면적이 없는 bounds도 조작 대상으로 사용하지 않는다.
이름 없는 클릭 부모는 적절한 비조작 자식의 label을 상속할 수 있지만,
루트·스크롤 목록·입력란·화면의 절반 이상을 차지하는 부모에는 이 규칙을 적용하지 않는다.
`target_bounds`는 해당 조작 노드의 화면 픽셀 좌표이며 Overlay 로컬 변환은 FE에서만 수행한다.

예시:

```json
{
  "node_id": "node_12",
  "label": "디스플레이",
  "role": "menu_item",
  "semantic_tags": ["display", "screen"],
  "bounds": [24, 320, 1056, 408],
  "actionable": true,
  "position_hint": "middle",
  "value": null
}
```

비판적 기준:

- Normalizer는 공통 normalization과 task-specific semantic tagging을 분리해야 한다.
- 현재처럼 font/display/accessibility tag가 normalizer 안에 고정되면 다른 task로 확장할 때 오염된다.

### 7.6 Screen State Classifier

정규화 Node 목록과 Task Graph를 비교해 현재 화면 State를 판별한다.

판별 기준:

- 앱 패키지명
- 화면 제목
- 핵심 keyword 존재 여부
- 조작 가능한 UI role
- 이전 State에서 이동 가능한 State인지 여부
- screen fingerprint
- 제조사/OS/app version context

이상적인 출력:

```json
{
  "state_id": "S1_SETTINGS_HOME",
  "confidence": 0.87,
  "evidence": {
    "required_keyword_groups": ["설정"],
    "optional_keywords": ["디스플레이", "접근성"],
    "package_match": true,
    "continuity_bonus": false
  }
}
```

현재 코드 반영:

- `classify_screen_state()`는 state id만 반환한다.
- Backend response의 `state_confidence`는 실제 state confidence가 아니라 match confidence를 사용하고 있다.

개선 필요:

- classifier는 반드시 `state_id + confidence + evidence`를 반환해야 한다.
- `state_confidence`와 `match_confidence`를 분리해야 한다.
- 제목 node, 본문 node, menu item을 같은 weight로 합치면 안 된다.

### 7.7 Verification

사용자 행동 이후 새 화면을 관찰해 목표 State로 이동했는지 확인한다. Verification은 Guide 이후에만 동작하는 별도 단계가 아니라, 매 turn 초반에 pending transition을 보고 수행되는 핵심 runtime 단계다.

결과:

| 결과 | 조건 | 다음 처리 |
| --- | --- | --- |
| `SUCCESS` | 예상 State로 이동 또는 값 변화 성공 | 다음 Transition 진행 |
| `DONE` | 완료 State 도달 | 완료 안내 |
| `NO_CHANGE` | 화면 fingerprint가 변하지 않음 | 재안내 또는 target 제외 |
| `WRONG_STATE` | 예상과 다른 State | Re-plan |
| `UNKNOWN` | 화면은 바뀌었지만 State 판별 실패 | fallback 또는 재계획 |

현재 코드 반영:

- pending transition의 `to_state`와 현재 state를 비교한다.
- 이전 snapshot과 현재 snapshot의 fingerprint가 같으면 `NO_CHANGE`로 본다.
- 같은 logical state라도 fingerprint가 달라지면 `UNKNOWN`으로 본다.
- slider value가 증가하면 `S5_FONT_SIZE_CHANGED` 성공으로 본다.

비판적 기준:

- Verification은 state classifier에 강하게 의존하므로 classifier confidence와 evidence가 필요하다.
- 값 변경 task는 가능하면 OS/system setting signal로도 검증해야 한다.
- slider node id가 바뀌거나 이미 최대값인 경우를 별도로 처리해야 한다.
- 현재 `verify_transition()`은 pending transition의 목적지가 done state이면 실제 현재 state와 비교하기 전에 `DONE`을 반환하는 분기가 있다. 목표 설계에서는 완료 predicate의 관측 근거 없이 terminal transition 정의만으로 완료를 선언하지 않아야 한다.
- runtime의 `SUCCESS`/`DONE`은 온라인 판단 결과다. AI-Agent(2)의 정답 라벨로 그대로 복사하지 않으며, 후속 관측과 검증 근거로 재평가한다.

### 7.8 Re-plan

예상과 다른 화면이 나타났을 때 새로운 경로를 계산한다.

전략:

- 현재 State 재분류
- 직전 실패 Node 제외
- 대체 Transition 탐색
- 검색 기능 사용 가능 여부 확인
- 허용된 scroll 범위 안에서 추가 탐색 가능 여부 확인
- 반복 실패 시 LLM/VLM fallback
- 사용자의 추가 설명 요청

현재 코드 반영:

- `NO_CHANGE`, `WRONG_STATE`, `UNKNOWN`이면 replan decision을 만든다.
- 같은 transition이 `RETRY_LIMIT_BEFORE_EXCLUSION = 2` 이상 실패하면 마지막 target node를 제외한다.
- 이후 matcher가 같은 transition의 다른 node를 선택할 수 있다.

비판적 기준:

- 현재 구현은 “진짜 graph-level replanning”이라기보다 retry exclusion이다.
- 이상적인 Re-plan은 alternate transition ranking, recovery action, user-corrected path capture를 포함해야 한다.

### 7.9 UI Matching

현재 State에서 다음 Transition을 수행하기 위한 Target Node를 선택한다.

1차 판단:

- keyword matching
- synonym matching
- resourceId matching
- role matching
- enabled/actionable check

2차 판단:

- normalized label embedding similarity
- 현재 State와 후보 Node의 semantic similarity
- Task goal과 후보 Node의 semantic similarity
- bounds/position pattern
- top-1/top-2 score gap

fallback 조건:

- 후보가 없음
- 후보가 여러 개이고 score 차이가 작음
- 최고 후보 confidence가 threshold 미만
- 후보 Node가 실제로 clickable/actionable하지 않음

예시 출력:

```json
{
  "state_id": "S1_SETTINGS_HOME",
  "next_state_id": "S2_DISPLAY_MENU",
  "action_type": "tap",
  "target_node_id": "node_12",
  "target_label": "디스플레이",
  "target_bounds": [24, 320, 1056, 408],
  "match_method": "keyword+embedding+role",
  "confidence": 0.91,
  "transition_id": "S1_TO_S2_DISPLAY_MENU",
  "fallback_reason": null
}
```

현재 코드 반영:

- keyword match, similarity score, semantic tag, role, actionable score를 합산한다.
- 현재 match threshold는 `0.7`이다.
- Gemini API key가 없으면 similarity는 keyword-only Jaccard fallback이다.

개선 필요:

- top-k 후보와 score breakdown을 response/debug에 남겨야 한다.
- top-2 gap이 낮으면 바로 안내하지 말고 사용자 확인 또는 후보 선택을 해야 한다.
- resourceId selector를 transition spec에 명시적으로 포함해야 한다.

### 7.10 Safety Gate

Agent가 안내하려는 Action의 위험도를 판단한다.

위험 행동 기준:

- 금전적 결과가 발생하는가
- 개인정보 또는 민감정보가 관련되는가
- 법적/계약적 동의가 발생하는가
- 계정 또는 보안 상태가 변경되는가
- 기기 설정이 되돌리기 어렵거나 사용자에게 큰 영향을 주는가
- 사용자의 의도를 확신할 수 없는가

판정:

| 판정 | 의미 | 처리 |
| --- | --- | --- |
| `LOW_RISK` | 일반 탐색 또는 되돌릴 수 있는 설정 탐색 | 바로 안내 |
| `CONFIRM_REQUIRED` | 사용자 설정, 개인정보, 계정 상태 등에 영향 가능 | 사용자 확인 후 안내 |
| `BLOCKED` | 결제, 송금, 계정 삭제, 인증번호 입력 등 고위험 | 안내 중단 또는 사람 확인 유도 |

현재 코드 반영:

- runtime `guide.py`는 transition의 `safety_level`을 처리한다. Backend에는 별도 `services/safety.py`가 추가되어 confirmation ID·session version·만료·화면 hash 및 승인된 target/action을 검증한다.
- `S4_TO_S5_INCREASE_FONT_SIZE`는 `CONFIRM_REQUIRED`이고 confirmation 전에는 drag guide를 내지 않는다.
- `user_confirmation=rejected`면 blocked command를 반환한다.

개선 필요:

- Safety Gate는 Guide Builder에서 분리해야 한다.
- Backend의 confirmation 결합 검증을 유지하면서 runtime 단독 경로에서도 같은 policy 계약을 적용할 수 있도록 분리한다.
- graph의 safety level만 믿지 말고 action type, app category, current screen signal도 함께 봐야 한다.

### 7.11 Guide

Agent는 Target Node의 위치와 다음 행동을 Guide Command로 생성한다. FE는 이 명령을 받아 실제 Overlay를 렌더링하고, 안내 문장과 질문을 TTS 모델로 음성 변환해 재생할 수 있다.

Agent가 생성하는 정보:

- `guide_command_id`
- `command_type`: `highlight`, `gesture_hint`, `confirmation`, `done`, `blocked`
- `action_type`: `tap`, `drag`, `scroll`, `observe_only`
- `target_node_id`
- `target_label`
- `target_bounds`
- `message`
- `safety_level`
- `requires_user_action`
- `render_policy`
- `ttl_ms`

FE 책임:

- 버튼 또는 영역 윤곽선 강조 렌더링
- 움직이는 커서 또는 포인터 렌더링
- 짧은 텍스트 안내 렌더링
- 필요 시 사용자 확인 UI 렌더링
- 마이크 상태가 켜져 있으면 안내 문장과 질문을 TTS로 출력
- Overlay가 대상 UI를 가리지 않도록 위치 조정
- 실제 tap 수행 금지

현재 코드 반영:

- match fallback 시 `request_fresh_observation` fallback action을 render policy에 담는다.
- settings app icon을 찾지 못하면 `open_android_settings_or_show_app_drawer` recovery action을 담는다.
- vision fallback이 필요하면 `vision_fallback` render policy를 담는다.
- Backend personalization layer가 guide message 앞에 “천천히 한 단계씩 진행할게요.” 같은 문구를 붙이거나 concise하게 줄일 수 있다.

원칙:

- 한 번에 하나의 행동만 안내한다.
- 화면 위 Target Node와 안내 문장이 서로 충돌하지 않도록 배치한다.
- 자동 tap 없이 사용자가 직접 조작한다.
- Agent는 렌더링 좌표와 안내 의도를 전달하고, FE가 디바이스 해상도/밀도/안전영역을 반영해 최종 위치를 계산한다.

### 7.12 Scroll Handling

scroll은 target UI를 찾기 위한 탐색 행동으로만 허용한다. 자동 tap은 금지하지만, 화면 아래나 위에 있을 수 있는 버튼/메뉴를 찾기 위해 제한된 자동 scroll은 허용할 수 있다.

scroll 결정 조건:

- 현재 State에서 필요한 target keyword가 viewport에 없음
- 현재 화면에 `scrollable=true`인 container가 있음
- Task Graph의 현재 Transition이 `allow_scroll=true`임
- target이 아래/위에 존재할 가능성을 나타내는 근거가 있음
  - 같은 메뉴 목록의 정렬상 target이 아래에 있을 가능성
  - 화면 하단에 잘린 항목 존재
  - 이전 scroll history상 아직 끝에 도달하지 않음
- Safety Gate가 `LOW_RISK`로 판정함

Agent가 FE에 전달하는 정보:

- `guide_command_id`
- `action_type: "scroll"`
- `direction`: `down` 또는 `up`
- `message`
- `visual_hint`
- `target_hint`
- `manual_override_allowed: true`

Backend는 Scroll Command와 실행 결과를 Agent와 FE 사이에서 중계하며 Android Accessibility action을 직접 실행하지 않는다.

현재 코드 반영:

- scroll action type은 존재하지만 현재 FE contract allowed actions와 MVP-01 graph에서는 비활성화되어 있다.
- 따라서 이 섹션은 목표 구조이며, 기능화하려면 contract, graph, FE service, verifier, tests를 함께 추가해야 한다.

### 7.13 Fallback / Recovery

Fallback은 Agent가 확신할 수 없을 때 무리하게 다음 행동을 안내하지 않기 위한 안전 장치다.

Fallback 종류:

| 유형 | 조건 | 처리 |
| --- | --- | --- |
| Typed Input Rejection | Goal enum·slot·catalog가 계약과 불일치 | 필드 오류를 FE에 전달; FE에서 질문·재분류 |
| Fresh Observation | target이 viewport에 없음 또는 tree가 낡음 | FE에 새 Accessibility Tree 요청 |
| Vision Consent | Accessibility signal이 부족하고 screenshot/OCR 필요 | 화면 이미지 분석 동의 요청 |
| Vision Augmented Match | OCR/VLM element가 target 후보를 제공 | synthetic node로 기존 pipeline에 합류 |
| Recovery Action | 앱이 잘못 열려 있거나 설정 앱을 찾아야 함 | FE가 설정 앱 열기 또는 앱스 화면 이동 제공 |
| Blocked | 정보 부족 또는 고위험 action | 안내 중단 |

현재 코드 반영:

- 내부 debug와 외부 응답은 다르다. Backend는 debug를 진단 allowlist로 축소하므로 상세 reason/recovery 문자열의 응답 노출을 전제로 구현하지 않는다.
- empty tree + no vision은 blocked/vision fallback 계열로 처리한다.
- empty tree + unconsented screenshot은 vision consent command로 처리한다.


### 7.14 Session / Episode / Step 상태

아래는 두 에이전트 사이의 데이터 연결까지 고려한 목표 세션 계약이다. 현재 코드에 모든 필드가 존재하지는 않는다.

| 상태 | 내용 | 유지·초기화 시점 |
| --- | --- | --- |
| Session identity | session ID, 사용자 소유권, session version | Backend 생성·검증·만료 |
| Goal episode | episode ID, FE가 확정한 goal, 초기 완료 기준값 | 시작·검증된 목표 교체 시 생성 |
| Command control | 마지막 command ID/type, ACTIVE·PAUSED 등 실행 상태 | 유효한 제어 명령 접수 시 전이 |
| Graph reference | task ID, graph version/hash, template parameters | episode 시작 시 고정 |
| Environment | package, app version, OS, 제조사, locale, FE capability | 관측마다 변경 여부 확인 |
| Current state | 관측된 state, confidence, evidence | 유효한 새 관측으로 갱신 |
| Pending step | step/attempt/transition/guide ID, expected predicate | 안내 발행 시 설정, 결과 확정 시 해제 |
| Pending confirmation | 확인 대상 guide·transition·관측 ID, 유효 기간 | 응답·만료·화면 변경 시 재검증 |
| Previous observation | snapshot, observation ID, 시각, fingerprint, 관련 값 | 검증 기준으로 보존 |
| Retry / recovery | 시도 수, 제외 node, 대체 전이, 남은 예산 | 실패·state 변경·episode 종료 |
| Terminal status | completed, cancelled, expired, failed 등 이유 | 종료 후 새 action 발행 중단 |

세션은 여러 목표를 포함할 수 있으므로 `episode_id`를 따로 둔다. 사용자가 목표를 바꾸면 이전 episode의 종료 사유를 기록하고, 이전 pending transition·confirmation·실패 node·기준값을 새 목표에 재사용하지 않는다. 실행 전 clarification은 FE가 완료한다. 실행 중 목표 변경은 `replace_task`로 명시하며, `respond_confirmation`은 현재 action 승인만 처리하고 goal을 변경하지 않는다.

current state는 관측된 사실이고 expected state는 계획이다. 다음 state를 안내했다는 이유로 current state를 먼저 이동시키지 않는다. 사용자 취소·Backend 만료 시 pending guide를 무효화하고 Agent(2)가 확인할 종료 event를 남긴다.

현재 `AgentSessionState`는 typed goal, current state, pending transition/confirmation, last guide/target, failed nodes, attempts, snapshot, action event를 관리한다. 명시적인 episode/step/observation ID 및 versioned graph reference는 추가할 계약이다.

### 7.15 Turn 처리와 정합성

목표 알고리즘은 다음과 같다. 실제 Python 구현을 그대로 옮긴 코드는 아니다.

```text
handle_turn(request):
    validate_identity_ownership_and_request(request)
    return_saved_result_if_same_request(request)
    claim_session_version_and_restore_checkpoint()
    validate_typed_command_and_dispatch()
    if command is pause_task or cancel_task:
        invalidate_pending_guides_and_confirmations()
        persist_control_state_response_and_event()
        return_control_response_without_screen_inference()
    if command is start_task or replace_task:
        validate_fe_goal_against_catalog_and_slot_schema()
        select_compatible_approved_graph()
        commit_new_episode_and_pin_graph()
    else:
        restore_fe_goal_and_pinned_graph()
    # resume requires a fresh observation and never reuses old target coordinates.
    validate_observation_freshness_and_environment()
    normalize_and_classify_current_screen()
    if pending_step exists:
        correlate_guide_action_and_new_observation()
        verify_pending_step_without_assuming_success()
        update_attempt_outcome_once()
    if goal_completion_predicate_is_satisfied():
        close_episode_and_build_done_command()
    else:
        decide_retry_recovery_or_alternate_transition()
        rank_current_screen_targets()
        apply_safety_confirmation_and_capability_policy()
        issue_one_guide_or_allowed_action()
    persist_checkpoint_response_and_event_delivery_record()
    return_current_response()
```

중복 요청은 이미 수행한 판단이나 허용된 action을 다시 실행하지 않는다. 동일 request ID의 payload가 다르면 충돌이며, 재전송과 새로운 follow-up을 구분한다. 동일 세션의 동시 요청은 session version/lease로 직렬화하거나 충돌 처리한다.

FE에 전달한 command는 guide ID와 TTL을 가진다. 화면 전환, 새 guide, 취소, 만료 시 이전 좌표를 폐기한다. node ID는 화면마다 바뀔 수 있으므로 이전 화면의 node ID를 새 화면의 같은 ID와 무조건 동일 객체로 취급하지 않는다.

### 7.16 Verification과 Re-plan의 경계 사례

| 상황 | 온라인 판단 원칙 | 다음 처리 |
| --- | --- | --- |
| 안내 직후, 사용자 미조작 | 실패로 등록하지 않음 | 대기 또는 새 관측 |
| action event만 있고 새 화면 없음 | 성공으로 확정하지 않음 | 결과 관측 요청 |
| 같은 화면이나 stale한 관측 | 실제 결과로 사용하지 않음 | 최신 관측 요청 |
| 유효한 action 이후 충분히 기다려도 변화 없음 | 해당 attempt의 NO_CHANGE 가능 | 재안내·target 재선택·복구 |
| 예상 화면으로 이동 | state evidence와 전이 predicate 확인 | step 성공 후 다음 단계 |
| 같은 logical state에서 설정값만 변경 | Task 값 predicate 사용 | 값 변경 성공 여부 판단 |
| 사용자가 다른 경로로 이동 | 현재 화면을 다시 해석 | 승인 경로에서 이어가기, 우회 event |
| graph 밖 화면·앱 업데이트·권한 popup | 과거 state를 강제로 적용하지 않음 | fallback·복구·중단 |
| 초기부터 목표를 만족 | already-satisfied 의미 명시 | 불필요한 조작 없이 완료 가능 |
| 더 증가 요청이나 이미 최대값 | 증가 성공을 선언하지 않음 | 제약 설명·대안 질문 |

값 변경은 Task별 기준이 필요하다. `increase`는 episode 시작 기준값과의 비교, 특정 값 설정은 목표값·허용 오차·단위 일치, 메뉴 이동은 목적 화면과 핵심 요소 확인을 사용한다. 설정 반영에 지연이 있으면 제한된 관측 대기 정책을 둔다.

Re-plan은 최신 화면 재확인, 같은 transition의 다른 유효 target, 승인된 대체 transition, 복구 action, 동의된 fallback, 사용자 추가 설명 또는 중단을 선택한다. graph에 없는 위험 action을 임의 생성하지 않는다.

시도 횟수는 guide 생성 횟수와 구분한다. action이 확인되지 않은 observe-only 요청까지 실패로 누적하지 않는다. 실패 node 제외는 해당 state·관측 context에 한정하고 새 화면에서 ID가 재사용되었을 때 영구 제외하지 않는다.

현재 구현은 같은 transition의 반복 실패 후 node 제외가 중심이다. 이후 step별 retry, episode 전체 step 수, fallback 호출, scroll 횟수, 경과 시간의 상한을 정책으로 관리한다. 현재는 `runtime/budget.py`와 Backend `turn_executor.py`가 turn deadline과 동시 실행 상한을 적용한다. episode 전체의 retry·복구 예산은 추가할 정책이며, 예산 소진 시 이유와 가능한 복구 방법을 안내하고 종료한다.

### 7.17 Trace 생산 계약

AI-Agent(1)은 데이터 label을 확정하지 않아도 AI-Agent(2)가 재평가할 수 있는 증거를 남겨야 한다.

- guide/action, before/after observation, 검증 대상 step을 명시적으로 연결한다.
- FE가 제공한 분류 메타데이터, Agent의 Graph 선택 결과, State·Match confidence와 검증 evidence를 구분한다. FE 분류 점수를 Agent가 계산한 것으로 기록하지 않는다.
- 선택 target뿐 아니라 privacy 정책상 허용된 후보 집합과 score breakdown을 보존한다.
- 관측 state, 예상 state, runtime verification, terminal 이유를 분리한다.
- FE에서 발생한 clarification event와 Agent의 입력 거절·미지원 graph·동의 거절·fallback·오류·일시정지·재개·취소·만료를 각 생산 주체와 함께 연결한다.
- screenshot 원문을 기본 저장하지 않고, text·OCR·guide 등 민감 필드도 정책에 따라 제거한다.
- Backend의 DB checkpoint 저장과 학습 event 저장을 별도로 모니터링한다.
- event 전송 장애 시 실패와 재처리 위치를 남긴다.

현재 engine의 정상 반환 trace 외에 FE와 Backend 수명주기 event가 필요하다. request trace를 단순 복사한 데이터만으로 전체 runtime 라벨링이 완성되지 않는다.

## 8. Runtime 데이터 구조

아래는 모듈 간 의미를 설명하는 설계 예시다. HTTP DTO와 runtime dataclass의 필드명을 혼용해 그대로 요청으로 보내지 않는다. 자동 scroll 예시는 향후 계약이며 현재 MVP-01의 기능이 아니다.

### 8.1 FE Goal·Command Envelope

입력 DTO의 단일 기준은 [FE Goal Type·Command Type 정의와 전달 계약](./fe-goal-command-contract.md)이다.
`command_type`과 신규 시작/교체의 `goal`을 받으며 자연어 Goal Typing 입력을 정의하지 않는다.
원문과 분류 후보·confidence는 필요 시 FE가 생산하는 선택적 메타데이터다.

### 8.2 Agent Turn Request

신규 typed 요청의 공통 envelope와 명령별 JSON은 별도 계약에 정의한다. 기존 HTTP v1의
`raw_goal`과 runtime의 `RawGoalInput`은 전환 전 구현에만 해당한다.

실행을 진행하는 `start_task`, `replace_task`, `resume_task`, `observe`, `respond_confirmation`은
새 관측을 함께 전달한다. `pause_task`, `cancel_task`는 화면 수집 실패 중에도 처리할 수 있어야 한다.
이 구분은 새 schema의 조건부 검증 요구사항이며 현재 모든 요청에 관측을 요구하는 v1과 다르다.

### 8.3 Graph Selection Result

아래는 Agent 내부 선택 결과의 설계 예시다. `graph_version`과 `graph_hash`는 예시값이며
현재 다중 버전 registry가 존재한다는 뜻은 아니다.

```json
{
  "episode_id": "episode_001",
  "accepted_goal_type": "settings.font_size.increase",
  "task_id": "MVP-01",
  "task_type": "settings_change",
  "graph_version": "1.0.0",
  "graph_hash": "example-artifact-hash",
  "template_parameters": {
    "setting": "font_size",
    "direction": "increase"
  },
  "selection_method": "approved_registry_exact_match"
}
```

`accepted_goal_type`은 FE 입력을 검증 후 보존한 값이며 Agent가 새로 분류한 결과가 아니다.
FE는 응답의 task·graph 정보를 표시·추적에 사용할 수 있지만 이후 명령에서 이를 바꾸어
다른 graph 실행을 강제하지 않는다.

### 8.4 Task Definition

```json
{
  "task_id": "MVP-01",
  "goal_type": "settings.font_size.increase",
  "name": "Android 설정 앱에서 글자 크기 확대",
  "target_app": "android_settings",
  "type": "settings_change",
  "start_states": ["S0_UNKNOWN", "S1_SETTINGS_HOME"],
  "done_state": "S_DONE",
  "graph_version": "1.0.0"
}
```

### 8.5 State Definition

```json
{
  "state_id": "S1_SETTINGS_HOME",
  "name": "설정 홈",
  "required_keywords": ["설정"],
  "optional_keywords": ["디스플레이", "접근성", "연결"],
  "package_candidates": ["com.android.settings"],
  "role_hints": ["menu_item", "list"],
  "confidence_threshold": 0.75
}
```

### 8.6 Transition Definition

```json
{
  "transition_id": "S1_TO_S2_DISPLAY",
  "from": "S1_SETTINGS_HOME",
  "to": "S2_DISPLAY_MENU",
  "action_type": "tap",
  "target_keywords": ["디스플레이", "화면", "Display"],
  "target_role": "menu_item",
  "safety_level": "LOW_RISK",
  "allow_scroll": false
}
```

### 8.7 Normalized Node

```json
{
  "node_id": "node_12",
  "label": "디스플레이",
  "role": "menu_item",
  "semantic_tags": ["display"],
  "bounds": [24, 320, 1056, 408],
  "clickable": true,
  "enabled": true,
  "actionable": true,
  "position_hint": "middle",
  "similarity_ref": "node_embedding_ref"
}
```

### 8.8 Match Result

```json
{
  "state_id": "S1_SETTINGS_HOME",
  "next_state_id": "S2_DISPLAY_MENU",
  "action_type": "tap",
  "target_node_id": "node_12",
  "target_label": "디스플레이",
  "target_bounds": [24, 320, 1056, 408],
  "match_method": "keyword+embedding+role",
  "confidence": 0.91,
  "transition_id": "S1_TO_S2_DISPLAY",
  "fallback_reason": null
}
```

### 8.9 Guide Command

```json
{
  "guide_command_id": "guide_001",
  "command_type": "highlight",
  "action_type": "tap",
  "target_node_id": "node_12",
  "target_label": "디스플레이",
  "target_bounds": [24, 320, 1056, 408],
  "message": "디스플레이를 눌러주세요.",
  "safety_level": "LOW_RISK",
  "requires_user_action": true,
  "render_policy": {
    "avoid_covering_target": true,
    "preferred_position": "below_target"
  },
  "ttl_ms": 10000
}
```

### 8.10 User Action Event

```json
{
  "action_type": "tap_completed",
  "guide_command_id": "guide_001",
  "target_node_id": "node_12",
  "target_bounds": [24, 320, 1056, 408],
  "occurred_at_ms": 1788840000000
}
```

### 8.11 Verification Result

```json
{
  "status": "SUCCESS",
  "current_state_id": "S2_DISPLAY_MENU",
  "expected_state_id": "S2_DISPLAY_MENU",
  "message": "Expected screen transition succeeded."
}
```

### 8.12 Vision Snapshot

```json
{
  "user_consented": true,
  "redaction_applied": true,
  "provider": "frontend_ocr",
  "screenshot_base64": null,
  "ocr_text": null,
  "elements": [
    {
      "element_id": "display",
      "text": "디스플레이",
      "bounds_in_screen": [24, 320, 1056, 408],
      "role": "menu_item",
      "confidence": 0.94,
      "actionable": true
    }
  ]
}
```

### 8.13 Scroll Command

```json
{
  "scroll_command_id": "scroll_001",
  "task_id": "MVP-01",
  "state_id": "S1_SETTINGS_HOME",
  "transition_id": "S1_TO_S2_DISPLAY",
  "scrollable_node_id": "node_list_01",
  "direction": "down",
  "scroll_amount": "half_page",
  "max_attempts": 3,
  "target_hint": {
    "keywords": ["디스플레이", "화면", "Display"],
    "embedding_threshold": 0.78
  },
  "stop_condition": ["target_found", "end_of_list", "max_attempts"]
}
```

### 8.14 Runtime Trace와 데이터 라벨의 구분

현재 `TraceLogger`는 `agent-runtime-trace.v1`의 진단 dict를 메모리에 저장하며 기본 100개·900초의 개수/TTL 제한이 있다. `diagnostic_payload()`는 허용된 container·enum·수치만 남긴다. engine이 record 호출에 넣은 request/session/task ID, graph version, raw text, node label, action 상세가 그대로 저장되는 구조가 아니다.

주의할 점은 같은 record 안의 `verification`이 **직전 안내**를 평가하고, `guide`는 **현재 turn에서 새로 생성한 안내**라는 것이다. AI-Agent(2)는 record 안의 두 필드를 곧바로 정답 pair로 연결하면 안 된다.

현재 trace에는 request/session 연결을 포함한 안정적인 식별자, `episode_id/step_id`, normalized candidate snapshot, 검증 대상 guide ID가 보존되지 않는다. `label_sample`도 진단 allowlist에서 제외된다. 이 진단 로그만으로 전체 UI ranking replay나 episode 재구성을 수행할 수 없다.

전환 전 구현에서는 미지원·모호한 Goal이 `handle_turn()`의 조기 return으로 빠져 일반 trace 기록에 도달하지 않는다. 새 구조는 FE의 분류 event와 Agent의 입력 검증·graph 선택 거절을 별도로 연결해야 한다. API 검증 실패, timeout, 사용자 취소, 세션 만료도 engine의 정상 반환 trace만으로는 포착할 수 없다.

전체 event 계약과 필수 필드는 [AI-Agent(2)의 입력 및 라벨 계약](./ai-agent-2-graph-proposal.md)에 정의한다. AI-Agent(1)은 사실과 실시간 판단을 기록하고, AI-Agent(2)가 버전이 있는 학습 라벨을 별도로 생성한다.

## 9. 개발 항목

| 순서 | 개발 항목 | 결과물 | 현재 코드 반영 |
| --- | --- | --- | --- |
| 1 | Request / Session / Idempotency | 안정적인 turn 처리 | Backend DB claim/checkpoint 구현, runtime 단독은 메모리 |
| 2 | Typed Input / Command Router / Graph Selector | FE Goal·Command 검증 → 승인 graph 선택·복원 | typed DTO/router 미구현, legacy Goal Typing 제거 필요 |
| 3 | MVP Task 정의 | Task별 문서 | 구현 |
| 4 | Task Graph 설계 | State / Transition | Python graph 구현 |
| 5 | Graph Registry / Versioning 소비 | 승인 graph 선택, 세션 버전 고정, 호환성 | registry 일부, version 미흡 |
| 6 | Accessibility Tree 분석 | 실제 UI dataset | 입력 contract 구현 |
| 7 | UI Normalizer | 정규화된 Node | 구현 |
| 8 | Screen State Classifier | 현재 화면 판별 | rule 구현, confidence 미흡 |
| 9 | Verification | State 전이 확인 | 구현 |
| 10 | Re-plan | 예상 외 화면 처리 | retry exclusion 구현 |
| 11 | UI Matching | keyword + embedding 기반 Target Node 선택 | 구현, ambiguity 미흡 |
| 12 | Safety Gate | 위험 행동 판단 | runtime guide 처리 + Backend confirmation·출력 검증 |
| 13 | Guide Command Builder | FE Overlay 렌더링용 명령 | 구현 |
| 14 | Personalization | 사용자별 안내 문구 조정 | Backend 일부 구현 |
| 15 | Scroll Executor 연동 | 허용된 자동 scroll 실행 및 결과 | 구조만 있음 |
| 16 | LLM/VLM fallback | 실패 케이스 보완 | vision fallback 일부 구현 |
| 17 | Trace Logger | task 실행 이력 저장 | allowlist·TTL·개수 제한 진단 로그, 영속 학습 event 미구현 |

데이터 라벨링, dataset 생성, Graph Learner/Validator, graph release 및 model training은 AI-Agent(2)의 개발 항목이다. 두 에이전트를 나누기 위해 먼저 저장소나 배포 서비스를 물리적으로 두 개 만들 필요는 없다.

## 10. Task 문서 작성 규칙

Task별 문서는 다음 항목을 포함한다.

- Task ID와 사용자 목표
- 연결되는 `goal_type`
- FE catalog entry와 필수 slots·target app의 호환 조건
- 사용자 발화·Goal 분류 기준은 별도 FE 계약으로 링크
- 완료 조건
- State 목록
- Transition 목록
- scroll 허용 여부와 scroll policy
- keyword/synonym dictionary
- UI Matching 기준
- Safety 판정
- Guide Command 예시
- Verification 기준
- 예외 흐름 및 Re-plan 전략
- Fallback/Recovery 전략
- AI-Agent(2)에 전달할 Task별 완료 근거, 학습/업데이트 기준
- 필요한 Accessibility Tree dataset
- 제조사/OS/app version별 known variant

## 11. AI-Agent(1) 구현 성숙도

사용자 실행 능력은 화면 인식, 여러 turn의 상태 유지, 실제 결과 검증, 복구 품질로 평가한다. 현재는 MVP-01에 State·UI matching·pending transition·verification·node 제외 재시도가 구현되어 있다. FE typed 입력을 받는 경계는 아직 전환해야 한다.

남은 실행 과제는 State/Match confidence 분리, 모호한 후보 처리, 완료 predicate 강화, 제조사·앱 버전 일반화, 승인 graph version 고정과 graph 수준의 대체 경로 선택이다. 자동 scroll은 현재 비활성이며 실제 지원 기능으로 계산하지 않는다.

학습·graph 개선 능력을 Agent(1)의 완료 기준에 포함하지 않는다. 제품 전체의 기존 L0~L5 용어와 Agent(2)의 개선 루프는 [전체 구조 문서](./ai-agent-architecture.md)에서 관리한다.

## 12. 객관적/비판적 피드백

### 12.1 전체 흐름에 대한 피드백

현재 이상 구조의 큰 방향은 좋다. Accessibility-first, Human-in-the-loop, Task Graph, Verification, Trace Learning은 모바일 조작 지원 Agent에 맞는 안전한 접근이다.

사용자 실행에서 필요한 목표 구조와 현재 구현을 구분한다. Agent(1)의 검증·복구와 공통 관측 계약을 먼저 안정화하고, graph 데이터 분석과 개선 로직은 Agent(2) 문서·패키지에서 관리한다.

### 12.2 불필요하거나 줄여야 할 것

1. 모듈 중복

   `runtime/*`와 상위 `matching/*`, `task_graph/*`의 실행 개념을 하나의 canonical path로 정리한다. 기존 `goal_typing/*`는 FE 분류 구현의 참고·이관 대상이며 Agent(1) 내부의 새 분류 경로로 통합하지 않는다.

2. 실행 증거 연결

   Agent(1)은 guide·action·before/after observation·검증 대상 step을 연결해 기록해야 한다. 현재 진단 요약만으로는 이를 재현할 수 없으므로 공통 event 계약을 보강해야 한다.

3. Scroll을 현재 기능처럼 말하는 것

   Scroll은 목표 구조에는 필요하지만 현재 구현에서는 꺼져 있다. FE contract, graph, verifier가 준비되기 전까지는 “지원 예정”으로 표현해야 한다.

4. LLM/VLM 과신

   Vision fallback은 보조 수단이다. Accessibility Tree 품질과 graph selector가 약한 상태에서 VLM으로 덮으면 디버깅이 어려워진다.

5. Safety를 Guide에 묶어두는 것

   Backend에는 독립 confirmation·출력 검증이 추가되어 있다. runtime의 guide 내부 판정도 공통 Safety policy 경계로 분리해야 한다.

### 12.3 코드/로직별 피드백

| 영역 | 피드백 |
| --- | --- |
| Typed 입력 / Graph 선택 | FE의 Goal Type·Command Type을 검증하는 경계로 전환하고 legacy Goal Typer 호출을 제거해야 한다. |
| Task Graph | Python graph와 JSON graph가 함께 있으면 drift가 생긴다. source of truth를 정해야 한다. |
| State Classification | state id만 반환한다. confidence와 evidence가 필요하다. |
| UI Matching | top-2 ambiguity 처리가 없다. 후보가 비슷하면 확인해야 한다. |
| Verification | slider value 증가만으로 완료 판단하는 것은 약하다. system setting 또는 value boundary 처리가 필요하다. |
| Replan | 현재는 node exclusion이다. alternate transition ranking이 필요하다. |
| Vision Fallback | 동의된 OCR/element는 활용하며 원본 screenshot 외부 호출은 차단한다. 이미지 fallback 활성화에는 서버 redaction과 graph context가 필요하다. |
| Trace | in-memory라 학습 자산이 되기 어렵다. persistent trace store가 필요하다. |
| Backend | DB 기반 session·turn·checkpoint는 구현되어 있다. graph artifact 버전 고정과 영속 학습 trace 연계는 추가해야 한다. |
| Personalization | 시작은 좋지만 guide wording layer와 결합해야 일관성이 생긴다. |

### 12.4 가장 먼저 고쳐야 할 P0

최우선 경계 변경은 FE typed 입력 계약의 적용이다. Goal/Command enum과 slot schema, 명령별 세션 전이, adapter/checkpoint 전환을 함께 구현한다. Goal Typing 모델의 품질 개선은 FE 소유 문서에서 관리한다.

1. `state_confidence`와 `match_confidence` 분리

   현재 Backend 응답의 `state_confidence`가 match confidence를 사용한다. 의미가 다르므로 반드시 분리해야 한다.

2. canonical runtime 구조 결정

   `ai_agent.runtime`을 임시 MVP runtime으로 둘 것인지, 상위 모듈들을 canonical architecture로 삼고 runtime을 흡수할 것인지 정해야 한다.

3. Task Graph source of truth 결정

   Python dataclass graph와 JSON graph 중 하나를 중심으로 삼아야 한다.

4. DB checkpoint와 영속 trace의 분리

   Backend가 session checkpoint를 DB에 저장하더라도 engine의 trace는 메모리에 머문다. 전체 runtime event를 별도 영속 저장하고 checkpoint의 graph hash와 연결해야 한다.

5. Safety Gate 분리

   guide builder 안의 safety 처리를 별도 policy layer로 분리해야 한다.

## 13. MVP 성공 기준

MVP 성공 기준은 이상 구조 전체가 아니라 `L2` 완성에 맞춰 잡는다.

- FE의 확정 Goal·Command로 올바른 승인 Task Graph를 선택하거나 기존 graph를 복원한다.
- 원문 재분류 없이 미등록 Goal·누락/충돌 slots·지원되지 않는 Command를 구분해 거부한다.
- 일시정지·재개·취소·목표 교체가 오래된 guide와 pending confirmation을 재사용하지 않는다.
- Accessibility Tree 기반으로 현재 화면을 수집한다.
- UI Normalizer가 화면 Node를 일관된 구조로 변환한다.
- Screen State Classifier가 현재 State와 confidence를 반환한다.
- UI Matching은 keyword, resourceId, role, embedding 기반으로 Target Node를 선택한다.
- top-2 ambiguity가 있으면 무리하게 안내하지 않는다.
- Safety Gate가 위험 Action을 차단하거나 사용자 확인을 요구한다.
- Agent는 Guide Command를 생성하고 FE는 Overlay를 렌더링한다.
- 자동 tap은 수행하지 않는다.
- scroll 활성화 시 계약·FE 실행·검증을 함께 갖추고 허용된 target 탐색에만 사용한다. 현재 MVP-01은 비활성 상태를 유지한다.
- Verification이 State 전이를 확인하고, 실패 시 Re-plan한다.
- runtime trace를 persistent하게 저장한다.
- AI-Agent(2)가 생산한 승인 graph artifact를 검증하고 세션 시작 시 버전을 고정한다.
- AI-Agent(2)의 처리 실패가 현재 guide 응답 경로를 막지 않는다.

## 14. 구현 파일과 책임 매핑

| 파일 / 컴포넌트 | AI-Agent(1)의 책임 | 추가할 부분 |
| --- | --- | --- |
| `AI-Agent/src/ai_agent/runtime/engine.py` | turn orchestration | 모든 종료 경로 event, 검증 대상 step 명시 |
| 신규 typed validator / command router / graph selector | FE 입력 검증과 승인 graph 선택·복원 | 모듈 추가, DTO·session 상태 전환 |
| `runtime/normalizer.py`, `classifier.py` | UI 정규화 및 State 판단 | evidence, confidence 분리 |
| `runtime/matcher.py`, `similarity.py` | UI 후보 선택 | 후보별 점수, selector, top-2 gap |
| `runtime/verification.py`, `state_manager.py` | 이전 관측과 결과 비교 | 완료 predicate, 지연 관측, action 귀속 |
| `runtime/replanner.py` | 실패 이력과 node 제외 | 대체 transition, 재시도·복구 예산 |
| `runtime/guide.py`, `contracts.py` | guide와 허용 action 계약 | 독립 Safety Gate, 실행 capability 확인 |
| `runtime/session.py` | turn 중 세션 상태 | 승인 artifact version과 episode 연결 |
| `telemetry/trace_logger.py` | 공통 진단 producer를 사용 | 필터를 유지하며 별도 영속 event 연결 |
| `Backend/app/clients/runtime_agent.py` | HTTP/runtime 변환, graph 경로 기반 progress | State/Match confidence 분리 |
| `Backend/app/services/agent_turn.py` | DB checkpoint 복원·저장 및 응답 조정 | event outbox 또는 동등한 전달 보장 |
| `Backend/app/repositories/agent_sessions.py` | DB session·turn claim·멱등성 | 학습 event와의 식별자 연계 |

`runtime/*`를 현재 실행 경로의 기준으로 삼는다. 상위 `observation/*`, `matching/*`, `workflows/*`의 구조가 존재한다는 이유만으로 현재 제품에 연결된 기능으로 표시하지 않는다. 기존 `runtime/goal_typing.py`와 `goal_typing/*`는 [FE 이관 목록](./fe-goal-command-contract.md#11-현재-코드와-구현-전환)에서 관리한다. AI-Agent(2) 코드는 `graph_proposal/`, 공통 진단과 관측 계약은 `telemetry/`로 이동했다. 사용자 실행 경로는 Agent(2)를 import하지 않는다. 기존 컴포넌트용 `replanning/state_manager.py`는 세션 내 상태만 관리하고 공통 `task_graph/`에는 graph 구조·registry만 둔다.

## 15. 대표 시나리오와 평가 기준

### 15.1 정상 작업과 복구

글자 크기 확대의 첫 turn에서 Agent(1)은 FE의 `start_task`와 `settings.font_size.increase`를 받아 `MVP-01`을 선택하고 현재 설정 홈의 디스플레이 메뉴를 안내한다. 다음 turn에서 디스플레이 화면이 확인되면 직전 step을 검증하고 글자 크기 메뉴를 안내한다. 설정 변경 확인 후 사용자가 slider를 조작하면 기준값 대비 증가를 검증해 완료한다.

메뉴 이름이 달라 target을 찾지 못하면 graph의 synonym·selector 후보를 검사하고 새 관측 또는 동의 기반 Vision을 요청한다. 다른 승인 경로가 있으면 Re-plan한다. 새 화면·우회 경로는 trace에 남기고, 여러 세션에 적용할 graph 변경은 Agent(2)가 처리한다.

### 15.2 인수 시나리오

| 검증 시나리오 | 기대 결과 |
| --- | --- |
| 유효한 FE Goal + `start_task` | 승인 task 선택, episode·graph 고정 |
| `observe` 후속 요청 | 저장된 FE goal과 graph reference 유지 |
| `pause_task` / `resume_task` | 일시정지 저장; 재개 시 새 관측·좌표로 안내 |
| `replace_task` | 새 입력·graph 검증 후 이전 episode 종료 및 새 episode 생성 |
| Goal이 누락된 시작 / 후속 요청에 새 Goal 혼입 | 계약 오류; 원문 재분류 또는 묵시적 목표 교체 없음 |
| 없는 session의 follow-up | 계약 오류 또는 재시작 안내 |
| 중복 request 및 session 동시 요청 | 중복 실행 방지와 version 일관성 |
| 미등록 Goal·slot 충돌·호환 graph 없음 | FE에 구분된 오류와 field 정보를 반환하고 관련 event 기록 |
| 빈 tree·동의 없는 Vision | 무근거 target 없이 정보·동의 요청 |
| State는 높고 Match는 낮은 신뢰도 | 두 값을 구분하고 matching fallback |
| 사용자 조작 전 같은 화면 | 곧바로 반복 실패로 누적하지 않음 |
| 예상과 다른 화면 | 현재 관측 기준 복구·Re-plan |
| 잘못된 guide의 확인 응답 | 현재 action을 허용하지 않음 |
| slider 값 증가·최대값·node ID 변경 | Task predicate에 따른 서로 다른 처리 |
| 새 graph release 중 진행 세션 | 고정 graph 유지 또는 명시적 호환성 실패 |
| 사용자 취소·만료 | 오래된 guide 폐기, 종료 event |
| Agent(2) 장애 | 사용자 runtime은 승인 graph로 지속 |

### 15.3 품질 지표

FE 분류 정답률은 FE의 지표로 분리한다. Agent(1)은 Graph 선택 정확도, 입력 오류 검출률, Command 상태 전이 정확도, State 정답률, Target 정확도, 검증된 task 완료율, false-DONE 비율, 잘못된 화면 안내, 반복 실패·replan·fallback 빈도, 사용자 취소, 응답 시간과 외부 호출 비용을 측정한다. 제조사·OS·앱 버전별 결과를 나눠 본다.

완료율은 관측 가능한 결과의 success/(success+fail)과 전체 접수 대비 확인된 완료 비율을 구분하며, unknown·cancelled·미지원의 분모 정책을 표시한다. 자동화 수준이나 LLM 사용량을 사용자 작업 품질의 대체 지표로 삼지 않는다. 라벨 정확도·graph proposal 품질은 Agent(2)의 지표로 별도 관리한다.
