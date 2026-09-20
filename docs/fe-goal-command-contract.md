# FE Goal Type·Command Type 정의와 전달 계약

## 1. 결정과 적용 범위

**Goal Typing과 Command Typing은 FE가 담당한다. Backend가 확정 요구에 맞는 Task Graph를 DB에서 선택·전달하고, AI-Agent(1)은 그 graph에 따른 Guide/Action·Verification·Re-plan을 담당한다.** 사용자가 말한 `comment type`은 **Command Type**, 즉 새 요청·중단·재개 등의 실행 제어 명령을 뜻한다.

이 문서는 다음 내용을 한 곳에서 관리한다.

- FE가 분류해야 하는 Goal·Command의 의미와 실제 등록 유형
- Goal별 필수 slots, 허용값, 전달할 필드와 전달하지 않을 필드
- FE → Backend 요청 형식과 명령별 예시; Backend → AI1 graph 실행 입력은 별도 계약
- 모호한 발화·미지원 Goal·실행 중 목표 변경 처리
- 기존 Goal Typing 내용을 FE 책임으로 옮기는 방법

typed 계약 이름은 **`agent-fe-contract.v0.2`**다. `POST /api/v1/agent/commands`에 구현되어 있으며, 기존 `POST /api/v1/agent/turn`은 전환 기간의 legacy 계약으로 유지한다. 신규 FE 경로는 `raw_goal`을 보내지 않는다.

- Backend 선택·실행 전달: [Task Graph 내부 계약](backend-task-graph-dispatch.md)
- Agent 실행 설계: [AI-Agent(1)](ai-agent-1-runtime.md)
- 현재 HTTP 계약: [FE/BE 연동](frontend-backend-contract.md), [OpenAPI](../Backend/docs/openapi.json)
- 내부 데이터·graph 후보 생산: [AI-Agent(2)](ai-agent-2-graph-proposal.md)

## 2. 용어와 소유권

| 개념 | 답하는 질문 | 생산 주체 | 예시 |
| --- | --- | --- | --- |
| Goal Type | 사용자가 무엇을 이루려는가? | FE | `settings.font_size.increase` |
| 입력 Command Type | 현재 작업을 어떻게 제어할 것인가? | FE | `start_task`, `pause_task`, `resume_task` |
| Slots | 그 목적에 필요한 구체적인 값은 무엇인가? | FE | `setting=font_size`, `direction=increase` |
| Target App | 목적을 수행할 논리적 앱은 무엇인가? | FE, catalog 기준 | `android_settings` |
| Task Type | 선택된 graph가 어떤 실행 패턴인가? | Backend/DB graph metadata | `settings_change` |
| Task ID | 어떤 승인 graph/template를 실행할 것인가? | Backend | `MVP-01` |
| Graph Reference | 어떤 버전의 승인 실행 정의인가? | Backend/DB | task ID + version + hash |
| 출력 Guide Command Type | FE가 안내를 어떻게 표시할 것인가? | Agent(1) | `highlight`, `confirmation`, `done` |
| Guide Action Type | 사용자가 다음에 할 UI 행동은 무엇인가? | Agent(1) | `tap`, `drag`, `observe_only` |

입력 `command_type`과 출력 `guide_command.command_type`은 이름의 끝이 같아도 다른 enum이다. 예를 들어 `start_task`를 받으면 Backend가 graph를 선택하고 AI1이 전달 graph와 화면을 판단해 `highlight`를 반환할 수 있다. FE가 Goal을 확정했다는 사실은 위험한 action에 대한 사용자 승인과도 다르다.

### 2.1 FE의 책임

1. 음성을 STT로 변환하거나 텍스트 입력을 받는다.
2. 작업 목적과 실행 제어 의도를 구분한다.
3. 공유 catalog의 canonical Goal Type과 대상 앱·slots를 확정한다.
4. 불명확한 목적·방향·중단 의미는 사용자에게 질문한다.
5. 확정된 Command와 필요한 Goal·현재 화면을 전송한다.
6. 제어 응답·session version·episode ID를 반영하고 오래된 안내를 버린다.

STT 자체가 Goal을 분류한다는 뜻은 아니다. FE가 소유하는 **입력 해석 계층**을 STT 뒤에 둔다. 온디바이스 rule/model을 사용할지 별도 분류 서비스를 호출할지는 FE 구현 선택이며, Agent(1)의 실행 루프에 분류 책임을 다시 넣지 않는다.

### 2.2 Backend와 AI-Agent(1)의 책임

- Backend: 계약·Goal/slots·Command 검증, 인증·소유권·멱등성·세션 version, DB graph 선택·고정·복원·parameter 확정, AI1 입력 조립, 응답·상태 저장.
- Agent(1): 전달된 graph·goal·실행 상태의 정합성 검사, 화면 판단, 안내, Safety, Verification, graph 내부 Re-plan.
- AI1은 자연어 재분류나 graph 검색을 하지 않는다. 전달 graph 부적합/복구 불가를 Backend에 반환하면 Backend가 재선택·재시작 또는 지원 불가를 처리한다.
- Goal Typing dataset을 AI-Agent(2)가 생산한다면 FE 분류 품질을 위한 데이터다. Agent(1)이 Goal 분류기를 운영한다는 의미가 아니다.

## 3. 실제 Goal Type 목록

### 3.1 현재 코드에 등록된 유형

2026-09-20 저장소 기준 catalog version은 `2.1.0`이다. 자연어 분류용 Goal은 총 160개(설정 89, 쿠팡 71)이고, 실행 graph까지 승인된 Goal은 글자 크기 확대·축소 두 가지다. Backend는 전환 기간 동안 기존 `1.0.0`, `1.1.0`, `2.0.0` 요청도 허용한다.

| 필드 | 실제 값 |
| --- | --- |
| `goal_type` | `settings.font_size.increase` |
| 의미 | Android 설정에서 현재보다 글자 크기를 크게 변경 |
| `target_app` | `android_settings` |
| graph의 `task_type` | `settings_change` |
| Backend가 선택해 전달할 `task_id` | `MVP-01` |
| 필수 `slots.setting` | 문자열 `font_size` |
| 필수 `slots.direction` | 문자열 `increase` |
| 현재 graph 동작 | 설정·디스플레이·글자 크기 화면 안내 → 확인 → 사용자 직접 조절 → 새 관측으로 증가 검증 |
| 현재 제한 | 자동 tap 없음, 현재 MVP graph의 자동 scroll 비활성, 제조사별 실기기 검증 필요 |

| 필드 | 실제 값 |
| --- | --- |
| `goal_type` | `settings.font_size.decrease` |
| 의미 | Android 설정에서 현재보다 글자 크기를 작게 변경 |
| `target_app` | `android_settings` |
| graph의 `task_type` | `settings_change` |
| Backend가 선택해 전달할 `task_id` | `MVP-02` |
| 필수 slots | `setting=font_size`, `direction=decrease` |
| 현재 graph 동작 | 같은 글자 크기 화면 안내 → 확인 → 왼쪽 조절 → 감소 검증 |

근거 파일:

- [실제 Goal Catalog](../AI-Agent/goal_catalog/mvp_goals.json)
- [Goal Type·키워드 전체 표](goal-type-keyword-catalog.md)
- [확대 Task 정의](../AI-Agent/src/ai_agent/runtime/tasks/mvp_01_font_size_increase.py)
- [축소 Task 정의](../AI-Agent/src/ai_agent/runtime/tasks/mvp_02_font_size_decrease.py)
- [현재 Graph Registry](../AI-Agent/src/ai_agent/runtime/tasks/__init__.py)

Registry는 `MVP-01`/확대 Goal과 `MVP-02`/축소 Goal을 각각 같은 graph release의 lookup key로 연결한다. Task ID를 Goal Type으로 보내지 않는다.

### 3.2 발화와 slots 해석

| 사용자 입력 | FE 처리 |
| --- | --- |
| “글자 크기 키우고 싶어” | `settings.font_size.increase`, `setting=font_size`, `direction=increase` |
| “휴대폰 글씨를 크게 보고 싶어” | 같은 Goal·slots |
| “폰트가 작아서 크게 하고 싶어” | 같은 Goal·slots |
| “글자 크기 줄여줘” | `settings.font_size.decrease`, `setting=font_size`, `direction=decrease` |
| “폰트가 너무 커서 작게 하고 싶어” | 같은 감소 Goal·slots |
| “글자가 너무 작아” | catalog의 대표 발화이며 확대 의도가 충분한 context인지 FE에서 판단 |
| “화면 크게 해줘” | 글자 크기와 화면 전체 확대를 구분해 질문한 뒤 확정 |
| “글자 크기는 키우지 마” | 확대 Goal로 시작하지 않음; 부정 또는 기존 요청 취소 의도 확인 |
| “글자 크기를 줄여줘” | 감소 Goal로 확정; 확대 graph로 보내지 않음 |

입력 slots는 이미 확정된 enum 값이다. 확대 표현은 `increase`, 축소 표현은 `decrease`로 FE에서 정규화한다. Agent가 문자열을 번역하거나 방향을 추측하지 않는다.

### 3.3 설정·쿠팡 확장 catalog와 실행 상태

분류기는 공백·문장부호·대소문자를 정규화한 뒤 각 Goal의 `keyword_groups`마다 하나 이상의 표현이 일치해야 해당 Goal로 확정한다. 같은 점수의 여러 Goal이 남으면 질문하며, 원문을 임의의 가까운 Goal로 보내지 않는다.

| 범위 | 개수 | 대표 Goal Type | 대표 키워드 그룹 | 실행 상태 |
| --- | ---: | --- | --- | --- |
| 글자 크기 | 2 | `settings.font_size.increase`, `.decrease` | `글자/글씨/폰트` + `키워/줄여/너무 작/너무 커` | `MVP-01`, `MVP-02` 승인 |
| 기타 Android 설정 | 87 | `settings.wifi.enable`, `settings.nfc.enable`, `settings.app_cache.clear` | 대상(`와이파이/NFC/앱 캐시`) + 동작(`켜/공유/삭제`) | `catalog_only` |
| 쿠팡 탐색·장바구니·찜 | 27 | `coupang.product.search`, `coupang.cart.quantity.increase`, `coupang.search.sort` | 대상(`상품/장바구니/정렬`) + 동작(`검색/수량 변경/적용`) | `catalog_only` |
| 쿠팡 주문·배송·후기·거래 | 23 | `coupang.order.track`, `coupang.order.refund_status`, `coupang.review.edit` | 대상(`배송/환불/리뷰`) + 동작(`조회/확인/수정`) | `catalog_only` |
| 쿠팡 계정·혜택·지원 | 21 | `coupang.address.change`, `coupang.coupay.balance.view`, `coupang.customer_service.open` | 대상(`배송지/쿠페이/고객센터`) + 동작(`변경/확인/연결`) | `catalog_only` |

전체 Goal·키워드 표의 단일 원본은 [Goal Catalog](../AI-Agent/goal_catalog/mvp_goals.json)다. `catalog_only`는 문장을 canonical Goal과 slots로 추출할 수 있다는 뜻이지 실행을 허용한다는 뜻이 아니다. 새 Goal은 호환되는 승인 graph, Safety·완료 조건·검증 fixture가 준비된 뒤에만 `execution_status=approved`로 바꾼다. 특히 주문·결제·반품·교환 등은 `risk_level=high`이며 분류만으로 자동 실행하지 않는다.

### 3.4 이름·버전 규칙

- canonical Goal Type은 소문자 dotted string으로 통일한다. 예: `<domain>.<target>.<intent>`.
- 표현만 다른 발화는 같은 Goal Type에 매핑한다. 표시용 한국어 이름을 wire enum으로 사용하지 않는다.
- `unknown`, `ambiguous`, 빈 문자열은 실행 가능한 Goal Type이 아니다. FE 내부 분류 결과로만 사용한다.
- catalog version과 graph version은 별개다. FE는 자신이 사용한 catalog version을 보내고, Backend는 DB에서 호환되는 graph version을 선택해 AI1에 전달한다.
- 새 catalog를 썼다는 이유로 서버에 없는 Goal을 실행할 수 없다. 지원 목록은 승인 registry와 맞춰 배포하며 캐시는 version 단위로 교체한다.

## 4. 입력 Command Type

다음은 **새 계약의 제안 enum**이다. 현재 Java의 `VIP/NORMAL`은 로컬 큐 우선순위이며 아래 명령 enum의 구현이 아니다.

| `command_type` | 의미 | 새 `goal` | 현재 `episode_id` | 화면 관측 | 핵심 처리 |
| --- | --- | --- | --- | --- | --- |
| `start_task` | 새 작업 시작 | 필수 | 생략 | 필수 | Backend가 새 session/episode 생성, DB graph 선택 |
| `pause_task` | 나중에 이어갈 수 있게 일시정지 | 금지 | 필수 | 선택 | 상태 보존, 이전 guide·확인 무효화 |
| `resume_task` | 일시정지한 작업 재개 | 금지 | 필수 | 필수 | 같은 goal·graph 복원 후 현재 화면 재판단 |
| `cancel_task` | 현재 작업 취소·종료 | 금지 | 필수 | 선택 | 종료 기록, 후속 실행 금지 |
| `replace_task` | 진행 중 목표를 새 목표로 교체 | 필수 | 교체할 이전 episode 필수 | 필수 | 새 goal·graph 검증 후 원자적으로 episode 교체 |
| `observe` | 동일 작업의 새 화면·행동 결과 전달 | 금지 | 필수 | 필수 | 직전 step 검증 후 다음 안내 |
| `respond_confirmation` | 현재 action의 확인 질문에 응답 | 금지 | 필수 | 필수 | confirmation ID·관측·version 검증 후 처리 |

화면 관측 필수는 `accessibility_tree` 또는 `vision_snapshot` 중 하나를 뜻한다. 빈 tree는 수집 실패 신호로 보낼 수 있지만 정상 화면이 있다는 증거로 취급하지 않는다. `pause_task`·`cancel_task`는 tree 수집 실패 때문에 거부되어서는 안 된다.

### 4.1 자연어 명령 구분

| 사용자 의도 | FE Command |
| --- | --- |
| “글자 크기 키워줘” — 실행 중 작업 없음 | `start_task` + 확정 Goal |
| “잠깐 기다려”, “일단 멈춰” | 재개 의도가 명확하면 `pause_task` |
| “이어서 해줘” — 일시정지 중 | `resume_task` |
| “이 작업 취소해”, “안 할래” | `cancel_task` |
| “아니, 지금 하던 것 대신 … 해줘” | 새 Goal 확정 후 `replace_task` |
| “응”, “아니” — 유효한 action 확인 UI가 열려 있음 | `respond_confirmation` + `accepted`/`rejected` |
| 화면 변경 이벤트·실제 조작 결과 | FE가 `observe` 생성; 별도 자연어 분류 없음 |

“그만”처럼 일시정지와 취소가 모호하면 FE는 로컬 안내를 우선 멈추고 뜻을 확인한다. 불명확한 발화만으로 `cancel_task`를 확정하지 않는다. 확인 UI가 없는 “응”을 `accepted`로 보내지 않는다.

### 4.2 실행 중 새 요청과 큐

- 한 session에는 동시에 하나의 활성 episode만 둔다.
- 실행 중 다른 목적이 들어왔을 때 FE는 “대기 요청”과 “현재 목표 교체”를 구분한다. 암묵적으로 현재 목표를 덮어쓰지 않는다.
- 대기 큐의 일반 요청은 FE에 보관하고, 실제 시작 시 새 `session_id`와 `start_task`로 보낸다. 큐에 쌓았다는 사실은 Agent의 Task 시작이 아니다.
- pause/cancel 등 제어 명령은 FE의 일반 요청 큐보다 먼저 처리한다. Backend의 소유권·version 검증을 우회하는 뜻은 아니다.
- `replace_task`는 새 Goal과 graph 선택이 실패하면 기존 episode를 변경하지 않는다. 성공할 때만 이전 episode를 `REPLACED`로 종료하고 이전 pending step·확인·실패 이력·완료 기준을 새 episode에 섞지 않는다.

### 4.3 일시정지·재개·취소의 상태 의미

아래 상태는 새 제어 계약의 목표 상태다. 현재 DB의 `task_status` enum이 전부 이와 같다는 뜻은 아니다.

| 현재 상태 | 허용 명령 | 결과 |
| --- | --- | --- |
| 신규 session | `start_task` | Backend graph 선택 후 `ACTIVE`, 필요 시 `WAITING_CONFIRMATION` |
| `ACTIVE` | `observe`, `pause_task`, `cancel_task`, `replace_task` | 관측 진행 / `PAUSED` / `CANCELLED` / 새 episode |
| `WAITING_CONFIRMATION` | `respond_confirmation`, `observe`, `pause_task`, `cancel_task`, `replace_task` | 확인·화면 재검증 또는 명시적 상태 전이 |
| `PAUSED` | `resume_task`, `cancel_task`, `replace_task` | 재관측 후 실행 / 종료 / 새 episode |
| `COMPLETED`, `CANCELLED`, `EXPIRED`, `FAILED`, `BLOCKED` | 세션 조회·동일 요청 재전송 | 새 실행 없음; 새 작업은 새 session에서 시작 |

진행 중인 session에 `start_task`를 보내면 `ACTIVE_TASK_EXISTS`로 거절한다. 이미 `PAUSED`인 episode의 새로운 `pause_task`는 상태를 유지하는 제어 응답으로 처리한다. `resume_task`는 `PAUSED`일 때만 허용하고, `respond_confirmation`은 유효한 pending confirmation이 있을 때만 허용한다. 표의 관측 명령은 pause를 해제하지 않으며 `PAUSED`에서 `observe`를 보내면 `COMMAND_NOT_ALLOWED`다.

재개 시 이전 위치를 그대로 그리거나, 이미 폐기된 확인을 자동 승인하지 않는다. 새 화면을 분류하고 같은 고정 graph에서 다시 UI를 선택한다. 새 graph가 배포됐더라도 실행 중 episode의 graph를 조용히 바꾸지 않는다.

## 5. FE → Backend 데이터 형식

### 5.1 공통 envelope

아래는 FE가 Backend에 보내는 typed DTO다. Backend는 이 입력에 DB에서 선택한 graph 본문·reference·복원 상태를 더해 AI1의 내부 실행 입력을 만든다. [Backend → AI1 계약](./backend-task-graph-dispatch.md)을 따른다.

새 typed DTO는 아래 필드만 허용하는 것을 기준으로 한다. enum의 대소문자를 임의로 바꾸거나 camelCase alias를 혼용하지 않는다.

| 필드 | 타입·형식 | 필수 조건 | 규칙 |
| --- | --- | --- | --- |
| `contract_version` | string | 항상 | `agent-fe-contract.v0.2` |
| `request_id` | string, 1~200자 | 항상 | 새 논리 요청마다 새 ID; 정확한 재전송은 같은 ID·본문 |
| `session_id` | string, 1~200자 | 항상 | FE 생성; Backend가 인증 사용자 소유권 검증 |
| `expected_version` | integer ≥ 0 | 항상 | 새 session은 `0`; 이후 최신 응답의 `session_version` |
| `episode_id` | string, 1~200자 | `start_task` 외 | 현재 episode ID; `replace_task`는 교체 전 episode |
| `command_type` | §4 enum | 항상 | 실행 제어 분기 |
| `goal` | §5.2 object | `start_task`, `replace_task` | 다른 명령에는 생략; `{}`나 `null`로 대체하지 않음 |
| `device_context` | §5.3 object | 시작·교체 | 후속 실행 명령에도 최신 환경 전달 권장 |
| `accessibility_tree` | 현재 관측 object | 명령별 | 화면 px·package·nodes; 현재 snapshot 계약 유지 |
| `vision_snapshot` | 현재 fallback object | 명령별 | 동의·redaction 정책 유지; 원본 외부 이미지 전송 허가 아님 |
| `user_action_event` | 현재 action event object | 선택 | 실제 행동이 발생한 `observe`·확인 응답에만 사용 |
| `user_confirmation` | `accepted` / `rejected` | `respond_confirmation` | 다른 명령에는 생략 |
| `confirmation_id` | UUID string | `respond_confirmation` | 현재 pending confirmation ID |
| `source_text` | string, 최대 500자 | 선택 | FE의 원문/설명; graph 선택에 사용하지 않음 |
| `classification` | §5.4 object | 선택 | FE의 진단 metadata; 실행 권한·지원 여부 근거 아님 |

기존 Bearer 인증을 유지한다. client의 `user_id`로 소유자를 정하지 않는다. `goal_type`은 **`goal.goal_type` 한 곳에만** 둔다. 같은 의미의 top-level `goal_type`, `raw_goal`, `typed_goal`을 중복해서 보내지 않는다.

### 5.2 Goal object

| 필드 | 타입 | 필수 | 규칙 |
| --- | --- | --- | --- |
| `catalog_version` | string | 예 | FE가 사용한 catalog version. 현재 등록 목록 `2.1.0` |
| `goal_type` | canonical string | 예 | 공유 catalog에 등록된 정확한 값 |
| `target_app` | canonical string | 예 | 현재 Goal은 `android_settings` |
| `slots` | object | 예 | Goal별 schema를 만족하는 이름·타입·값 |

MVP의 완전한 Goal object:

```json
{
  "catalog_version": "2.1.0",
  "goal_type": "settings.font_size.increase",
  "target_app": "android_settings",
  "slots": {
    "setting": "font_size",
    "direction": "increase"
  }
}
```

FE가 `task_id`, `task_type`, `graph_version`, `graph_hash`, `is_supported`, `required_slots_filled`를 Goal에 보내지 않는다. Task와 지원 가능 여부는 서버의 승인 자산으로 결정하며, 필수 slots는 실제 값으로 검증한다.

현재 MVP Goal에 대한 JSON Schema 예시:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "MvpFontSizeIncreaseGoal",
  "type": "object",
  "additionalProperties": false,
  "required": ["catalog_version", "goal_type", "target_app", "slots"],
  "properties": {
    "catalog_version": {"const": "2.1.0"},
    "goal_type": {"const": "settings.font_size.increase"},
    "target_app": {"const": "android_settings"},
    "slots": {
      "type": "object",
      "additionalProperties": false,
      "required": ["setting", "direction"],
      "properties": {
        "setting": {"const": "font_size"},
        "direction": {"const": "increase"}
      }
    }
  }
}
```

이 schema는 Goal object만 검증하는 인계용 예시다. 전체 envelope·인증·Command 상태 전이를 검증하는 구현은 별도로 필요하다. 향후 숫자형 slot을 추가하면 문자열 숫자로 보내지 않고 단위·범위·완료 predicate를 함께 정의한다.

### 5.3 Device context

```json
{
  "platform": "android",
  "manufacturer": "samsung",
  "os_version": "15",
  "locale": "ko-KR",
  "foreground_package": "com.android.settings",
  "app_version": null,
  "capabilities": {
    "accessibility_tree": true,
    "guide_overlay": true,
    "automatic_scroll": false
  }
}
```

`platform`과 `locale`은 필수이며 나머지 정보는 수집 가능한 값을 전달한다. `app_version`은 현재 관측 package의 버전이다. 대상 앱과 foreground가 다르면 이를 대상 앱 버전으로 해석하지 않는다. 특정 graph variant가 필수로 요구하는 환경 정보가 없으면 `MISSING_ENVIRONMENT_CONTEXT`로 보완 요청한다. 알려지지 않은 값을 제조사명 등에서 임의로 추론하지 않는다.

`target_app=android_settings`는 논리적 대상이고 `foreground_package`는 현재 실제 화면이다. 홈 화면에서 요청했다고 목표 앱을 launcher로 바꾸지 않는다. 해상도와 bounds는 같은 관측의 `screen_width`, `screen_height`, `bounds_in_screen`을 사용한다. capability는 클라이언트의 가능 여부일 뿐, graph나 Safety의 허용 조건을 대체하지 않는다.

### 5.4 선택적 분류 metadata

```json
{
  "resolved_by": "user_confirmed",
  "method": "keyword+slot_rule",
  "goal_confidence": 0.93,
  "command_confidence": 0.99,
  "classifier_version": "fe-intent.example-1"
}
```

`resolved_by`의 제안 값은 `classifier`·`user_confirmed`·`explicit_ui`다. `goal_confidence`·`command_confidence`는 선택적인 0~1 수치이고 측정하지 않으면 생략한다. 예시 수치는 실제 성능 측정값이 아니다. Backend의 Graph 선택 결과, AI1의 State confidence·UI match confidence와 분리한다.

미확정 후보 목록이나 `needs_clarification=true`인 Goal을 실행 명령에 넣지 않는다. FE 내부에서 질문을 끝낸 뒤 확정 값만 보내며, runtime이 낮은 FE score를 근거로 원문을 재분류하지 않는다. 원문·후보는 필요 목적과 개인정보 정책 없이 로그나 AI-Agent(2) 데이터에 자동 저장하지 않는다.

## 6. 명령별 전송 예시

아래는 모두 **v0.2 설계 예시**다. session version·episode·confirmation ID는 서버 응답에서 받은 값으로 대체한다. 예시마다 서로 다른 테스트 상황을 가정하며 번호를 그대로 이어 보낼 수 있다는 뜻은 아니다.

### 6.1 새 작업 시작

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-start-001",
  "session_id": "session-001",
  "expected_version": 0,
  "command_type": "start_task",
  "goal": {
    "catalog_version": "2.1.0",
    "goal_type": "settings.font_size.increase",
    "target_app": "android_settings",
    "slots": {"setting": "font_size", "direction": "increase"}
  },
  "device_context": {"platform": "android", "locale": "ko-KR"},
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [
      {"node_id": "settings-title", "text": "설정", "bounds_in_screen": [24, 40, 1056, 96]},
      {"node_id": "display", "text": "디스플레이", "clickable": true,
       "bounds_in_screen": [24, 320, 1056, 408]}
    ]
  }
}
```

Backend는 등록값·slots를 검증해 DB의 `MVP-01` graph를 선택·전달한다. AI1은 전달 graph와 화면을 판단해 디스플레이 메뉴 안내를 만든다. 원문을 재분류하지 않는다.

### 6.2 후속 관측

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-observe-002",
  "session_id": "session-001",
  "expected_version": 1,
  "episode_id": "episode-001",
  "command_type": "observe",
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [
      {"node_id": "display-title", "text": "디스플레이", "bounds_in_screen": [24, 40, 1056, 96]},
      {"node_id": "font-menu", "text": "글자 크기와 스타일", "clickable": true,
       "bounds_in_screen": [24, 500, 1056, 620]}
    ]
  },
  "user_action_event": {
    "action_type": "tap_completed",
    "guide_command_id": "guide-001",
    "target_node_id": "display",
    "target_bounds": [24, 320, 1056, 408]
  }
}
```

행동 event의 target은 직전 화면에서 실제 조작한 대상이다. 현재 화면의 같은 ID와 동일 객체라고 가정하지 않는다. 새 Goal을 보내지 않고 저장된 Goal·graph로 검증·안내를 이어간다.

### 6.3 일시정지

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-pause-003",
  "session_id": "session-001",
  "expected_version": 2,
  "episode_id": "episode-001",
  "command_type": "pause_task"
}
```

FE는 즉시 TTS·좌표 안내·예약된 허용 action을 멈추고 서버 응답을 기다린다. 서버는 goal·고정 graph·완료 비교 기준을 보존하되 이전 guide·pending confirmation을 무효화한다. 네트워크 실패 시 로컬 정지와 서버의 `PAUSED` 저장 성공을 구분해서 표시한다.

### 6.4 재개

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-resume-004",
  "session_id": "session-001",
  "expected_version": 3,
  "episode_id": "episode-001",
  "command_type": "resume_task",
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [
      {"node_id": "font-slider", "text": "글자 크기", "class_name": "android.widget.SeekBar",
       "clickable": true, "bounds_in_screen": [160, 430, 920, 480], "extras": {"progress": 3}}
    ]
  }
}
```

재개는 새 Goal 생성이 아니다. 저장된 기준값·graph를 유지하고 현재 화면에서 이어갈 위치를 판단한다. 필요한 action 확인은 새 confirmation으로 다시 받는다.

### 6.5 취소

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-cancel-005",
  "session_id": "session-001",
  "expected_version": 4,
  "episode_id": "episode-001",
  "command_type": "cancel_task"
}
```

이후 같은 session을 `resume_task`로 되살리지 않는다. 사용자가 다시 같은 Goal을 요청하면 새 session에서 `start_task`를 보낸다. 현재 구현에서는 기존 세션 cancel endpoint가 이 기능의 일부를 담당하며, 위 Command envelope는 아직 연결되어 있지 않다.

### 6.6 목표 교체

현재 등록 Goal이 하나이므로 실행 가능한 payload 예시는 같은 유형을 **새 작업으로 다시 시작**하는 경우다. 다른 Goal로 교체하려면 그 Goal의 승인 graph가 먼저 등록되어 있어야 한다.

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-replace-006",
  "session_id": "session-001",
  "expected_version": 4,
  "episode_id": "episode-001",
  "command_type": "replace_task",
  "goal": {
    "catalog_version": "2.1.0",
    "goal_type": "settings.font_size.increase",
    "target_app": "android_settings",
    "slots": {"setting": "font_size", "direction": "increase"}
  },
  "device_context": {"platform": "android", "locale": "ko-KR"},
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": []
  }
}
```

새 Goal·graph 검증이 성공하면 새 episode ID를 응답한다. 이 예시의 빈 tree는 관측 실패이므로 graph 선택 이후 fresh observation/fallback을 요청하고, 화면 위치나 완료를 추측하지 않는다. 취소된 session에 보내는 예시가 아니다.

### 6.7 action 확인 응답

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-confirm-007",
  "session_id": "session-001",
  "expected_version": 5,
  "episode_id": "episode-002",
  "command_type": "respond_confirmation",
  "user_confirmation": "accepted",
  "confirmation_id": "1ae21e56-7c38-43e4-a0b3-870d3ef91968",
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [
      {"node_id": "font-slider", "text": "글자 크기", "class_name": "android.widget.SeekBar",
       "clickable": true, "bounds_in_screen": [160, 430, 920, 480], "extras": {"progress": 3}}
    ]
  }
}
```

실제 payload는 pending confirmation을 만들 때의 관측과 검증 조건에 맞아야 한다. `accepted`는 confirmation ID·version·화면·target·만료 조건이 유효할 때만 받아들인다. 화면이 달라졌다면 기존 확인으로 다음 action을 허용하지 않는다. `rejected`는 action을 승인하지 않으며, 안전한 중단 처리 원칙을 유지한다. Goal 분류 단계에서 “글자 크기 확대 맞나요?”에 답한 것은 이 action 확인과 별개다.

## 7. 응답과 오류 처리

### 7.1 공통 응답 의미

새 계약에서는 실행 안내 응답과 제어 응답을 구분한다. 기존 `guide_command`에 가짜 UI target을 넣어 pause/cancel을 표현하지 않는다.

| 필드 | 의미 |
| --- | --- |
| `contract_version`, `request_id`, `session_id` | 요청과 응답의 계약·상관관계 |
| `session_version` | 다음 요청에서 보낼 최신 version |
| `episode_id` | 현재 episode; 목표 교체 성공 시 변경 |
| `response_type` | 제안 enum `guidance` / `control` |
| `command_result` | 접수한 입력 Command와 그 처리 결과 |
| `accepted_goal_type`, `task_id`, `graph_reference` | Backend가 실제 검증·선택·고정한 Goal/graph 정보 |
| `task_status` | 현재 실행 상태 |
| `guide_command` | `guidance`일 때만; 표시 방식·행동·좌표·Safety |
| `message` | 제어 응답의 사용자 안내 문구 |

`graph_reference`는 version/hash를 포함한 registry 전환 후 제공한다. 아래 제어 응답은 식별자·상태를 설명하기 위해 이를 생략한 예시다. 실운영 계약에서는 시작·교체 응답에 선택된 graph reference를 반환하고 후속 응답의 생략 여부를 고정한다.

일시정지 응답 설계 예시:

```json
{
  "contract_version": "agent-fe-contract.v0.2",
  "request_id": "req-pause-003",
  "session_id": "session-001",
  "session_version": 3,
  "episode_id": "episode-001",
  "response_type": "control",
  "command_result": {"command_type": "pause_task", "status": "applied"},
  "accepted_goal_type": "settings.font_size.increase",
  "task_id": "MVP-01",
  "task_status": "PAUSED",
  "message": "작업을 잠시 멈췄어요."
}
```

`command_result.status`의 제안 값은 `applied`·`unchanged`다. 정확한 재전송은 저장한 응답의 의미를 재사용한다. 취소·교체·새 version 이후 도착한 오래된 guide는 FE가 렌더링하거나 실행하지 않는다.

### 7.2 제안 오류 코드와 복구

아래는 새 계약의 구분 요구사항이다. 기존 HTTP 오류 DTO를 이미 교체했다는 뜻은 아니다. HTTP status와 envelope는 Backend 구현 시 기존 공통 오류 규약에 맞춰 연결한다.

| 코드 | 조건 | FE 처리 |
| --- | --- | --- |
| `UNSUPPORTED_CONTRACT_VERSION` | 서버가 해당 typed 계약을 처리하지 못함 | 지원 버전 확인·업데이트; raw Goal로 자동 우회하지 않음 |
| `INVALID_COMMAND` | 없는 enum 또는 명령별 필드 조합 오류 | DTO/분류 결과 수정 |
| `INVALID_GOAL_PAYLOAD` | 필수 slot 누락, 타입·값 충돌, target app 불일치 | 오류 field에 해당하는 값 보완 후 새 요청 |
| `UNSUPPORTED_GOAL` | 미등록 canonical Goal | 미지원 안내; 다른 Goal로 자동 치환하지 않음 |
| `CATALOG_VERSION_MISMATCH` | 서버와 FE catalog의 호환성 없음 | 지원 catalog 갱신 후 재확정 |
| `MISSING_ENVIRONMENT_CONTEXT` | graph 선택에 필요한 환경 정보 부족 | 실제 환경 수집·재전송 |
| `NO_COMPATIBLE_GRAPH` | Goal은 등록됐지만 현재 환경에 승인 graph 없음 | 해당 환경 미지원 안내 |
| `GRAPH_SELECTION_AMBIGUOUS` | 우선순위로도 승인 graph 하나를 정할 수 없음 | 임의 실행 없이 registry 점검 요청 |
| `ACTIVE_TASK_EXISTS` | 진행 session에 새 `start_task` | FE 큐 대기 또는 사용자가 명시한 교체 |
| `COMMAND_NOT_ALLOWED` | 현재 상태에서 resume/observe 등 불가 | 최신 세션 상태에 맞춰 UI 갱신 |
| `SESSION_NOT_FOUND` / `SESSION_TERMINATED` | session 없음 또는 종료 | 필요 시 새 session 시작 |
| `EPISODE_MISMATCH` | 이미 교체된 작업에 대한 과거 제어 명령 | 현재 episode 동기화; 새 작업에 취소를 자동 재적용하지 않음 |
| 기존 version·멱등성·확인 충돌 | stale version, 같은 ID의 다른 본문, 잘못된 확인 | 현재 충돌 규약 유지, 최신 상태·관측 확인 |

오류는 `goal.slots.direction` 같은 field 경로와 원인 enum을 제공해야 한다. FE가 임의의 서버 로그 문자열을 파싱해 다음 Command를 정하지 않도록 한다.

## 8. FE Goal/Command Typing 처리

이 절은 기존 runtime 문서의 자연어 Goal 처리 내용을 FE 소유로 옮긴 것이다. 구현 알고리즘은 FE가 선택하되, 출력 계약과 미지원·모호성 처리 원칙을 맞춘다.

### 8.1 처리 순서

1. **입력·context 수집:** source text, locale, 현재 활성/일시정지 작업, 열려 있는 확인 UI, 관측 앱을 확인한다.
2. **Command 판단:** 신규 목적, 일시정지, 재개, 취소, 목표 교체, 확인 답변을 구분한다. 순수 제어 명령이면 Goal 검색을 생략한다.
3. **텍스트 정규화:** 공백·오타·동의어·한국어/영어 표현을 정리한다. “하지 마” 같은 부정을 지우지 않는다.
4. **App hint 추출:** 사용자가 지정한 대상 앱과 현재 앱을 구분한다. foreground만으로 목표 앱을 확정하지 않는다.
5. **Goal 후보 검색:** catalog의 대표 발화·동의어를 keyword/rule 또는 embedding으로 검색한다.
6. **Slots 추출:** 각 Goal의 schema에 따라 방향·대상 설정·값을 채운다. 모호한 값을 기본값으로 숨기지 않는다.
7. **후보 점수·모호성 확인:** keyword·slot·app 신호, 부정, 후보 간 차이를 함께 고려한다.
8. **필요한 질문:** 부족한 목적·slot만 짧게 확인한다. 중단 종류와 새 목표 교체 여부도 이 단계에서 명확히 한다.
9. **canonical DTO 생성:** 최종 Goal/Command를 공유 schema로 검증한 뒤 전송한다.

### 8.2 분류 방식과 confidence

MVP는 keyword/synonym, slot rule, app/context 신호로 시작할 수 있다. embedding·LLM 사용 여부는 FE가 정확도·지연·비용을 평가해 정하며 Agent 실행의 필수 의존성으로 만들지 않는다.

기존 설계의 score fusion 예시는 embedding 0.45, keyword 0.30, slot 0.20, app prior 0.05다. `confidence ≥ 0.80`·후보 gap `≥ 0.12`·필수 slots 충족 시 확정, 0.60~0.80 또는 작은 gap은 질문, 0.60 미만은 추가 설명 요청이라는 기준도 **평가 전 예시**다. 현재 FE가 이 모델·수치를 구현했다는 의미가 아니다.

원문 유형·언어·부정·중단 명령별 평가 후 기준을 정한다. 다른 분류 방식의 score를 같은 확률처럼 비교하지 않는다. FE에서 확정한 Goal을 받은 뒤 Agent가 같은 confidence threshold로 다시 자연어를 분류하지 않는다.

### 8.3 미확정 입력

미확정 상태는 FE 내부의 `needs_clarification`·`unsupported` 등으로 관리하고 `start_task`를 보내지 않는다. 실행 중 goal 자체를 바꾸는 답변이 들어왔으면 새 Goal을 확정해 `replace_task`로 보낸다. 기존 작업에 새 `source_text`만 덧붙여 Agent가 새 목표를 알아서 추측하게 하지 않는다.

## 9. Catalog와 Graph 연결 관리

FE catalog는 사용자가 고를 수 있는 목적과 slot schema를 설명하고, Backend의 DB graph registry는 그 목적을 수행할 승인 graph와 선택 조건을 관리한다. 같은 release에서 호환성을 검증한다.

새 Goal 등록 시 필요한 항목:

1. canonical Goal Type, 표시 이름, 대상 앱, 대표 발화·동의어·부정/혼동 사례.
2. slot 이름·타입·필수 여부·허용값/단위·제약과 FE clarification 문구.
3. Agent 측 승인 Task ID, graph version/hash, 지원 환경·capability 조건.
4. State·Transition·UI selector, Safety, 완료 predicate, replay·회귀 fixture.
5. 지원 catalog version과 graph 호환 정책, rollback 시 FE 지원 목록 정리.

현재 catalog JSON에는 `required_slots` 목록이 있고, 정확한 고정값은 Python `TaskDefinition.required_slots`에 있다. 이 둘이 별도로 drift하지 않게 타입·제약을 포함한 공통 schema로 배포하는 작업이 필요하다. 문서에 후보 Goal 이름을 추가하는 것만으로 지원을 선언하지 않는다.

## 10. 멱등성·중단·관측 연결

- 같은 논리 요청의 네트워크 재전송은 `request_id`, 본문, `expected_version`을 그대로 유지한다. 수정한 Goal·새 관측은 새 요청이다.
- 같은 ID의 다른 본문은 충돌로 거절한다. 중복 시작으로 새 episode가 두 번 생성되면 안 된다.
- 후속 Command는 `session_id`·`episode_id`·version으로 대상 작업에 귀속한다. 목표 교체 직전의 취소 응답이나 늦은 guide를 새 episode에 적용하지 않는다.
- pause/cancel intent를 받은 FE는 로컬 출력부터 중지한다. 서버 요청 실패·미접수이면 “서버에도 취소 완료”로 표시하지 않는다.
- version 충돌 시 최신 상태를 조회한다. 사용자가 중단한 episode와 여전히 같은지 확인하지 않고 취소·교체를 자동 재적용하지 않는다.
- 안전 확인 수락은 해당 confirmation의 ID·version·관측·target·만료 검증을 유지한다. Command Type이나 Goal 확정으로 이를 생략하지 않는다.
- pause 동안 사용자가 직접 바꾼 상태는 resume 시 새 관측으로 판단한다. 과거 expected state를 현재 상태로 복사하지 않는다.
- raw text·Goal 분류 데이터, 실행 관측·온라인 검증, AI-Agent(2)의 사후 라벨은 생산 주체와 목적을 구분한다.

## 11. 현재 코드와 구현 전환

**2026-09-19 기준 v0.2 전환 구현이 연결되었다.** 신규 FE는 typed command endpoint를 사용하고, legacy raw-goal endpoint는 기존 클라이언트 호환을 위해 분리 유지한다.

| 현재 위치 | 현재 동작 | 전환 후 책임 |
| --- | --- | --- |
| `AI-Agent/src/ai_agent/runtime/goal_typing.py` | MVP-01 keyword/slot 기반 `type_goal()` | FE 분류 구현의 참고·이관 대상; Agent(1) 호출 경로에서 제거 |
| `AI-Agent/src/ai_agent/goal_typing/*` | 기존 catalog·normalizer·retriever·slot extractor·typer | FE 소유 입력 처리 참고; Agent(1) 확장 모듈로 유지하지 않음 |
| `AI-Agent/goal_catalog/mvp_goals.json` | 한 개 Goal과 대표 발화·필수 slot 이름 | FE와 Backend가 버전·제약을 공유하는 catalog 자산 |
| `runtime/engine.py` | legacy 요청만 `type_goal()` 사용 | typed 경로는 Backend가 전달한 goal·선택 graph로 시작 |
| `runtime/types.py`, `contracts.py`, `validation.py` | `RawGoalInput`, `TypedGoal`, 첫 turn raw Goal 요구 | Backend → AI1 graph 실행 DTO·명령·상태 정합성 검사 |
| `runtime/tasks/__init__.py` | 한 graph의 task/goal key lookup | DB seed/fixture로 관리; 선택은 Backend DB repository/selector로 이관 |
| `Backend/app/schemas/agent_turn.py` | legacy v1 DTO와 typed v0.2 DTO 병행 | typed DTO가 명령별 goal·관측 규칙 검증 |
| `Backend/app/clients/runtime_agent.py` | HTTP raw text → `RawGoalInput` | Backend가 선택한 graph 본문·reference·goal·복원 상태를 주입 |
| Backend 세션·checkpoint·확인 저장 | 현재 version·소유권·취소·확인 바인딩 | DB graph 고정·복원, PAUSED·episode·교체 원자성·제어 응답 추가 |
| FE `AgentTurnContract.java`·`AgentTurnGateway.java` | v0.2 typed Goal·Command 직렬화 | `/agent/commands`의 제어·안내 응답 처리 |
| FE `VoiceRequestQueue.java`·Overlay Service | 로컬 VIP/NORMAL 큐와 interrupt | 확정 Command 생성, pause/cancel 구분, 응답 상태 동기화 |

권장 전환 순서:

1. FE·Backend가 v0.2 field/enum·오류·catalog schema를 확정하고 계약 fixture를 공유한다.
2. FE가 현재 한 개 Goal부터 분류·slot 검증·Command 구분을 구현한다.
3. Backend typed schema·Command Router·DB graph repository/selector·adapter와 AI1의 graph 입력 validator를 함께 추가한다. raw Goal을 합성해 기존 `type_goal()`을 호출하는 우회 방식은 사용하지 않는다.
4. pause/resume/cancel/replace의 상태·episode·확인 무효화·멱등성과 checkpoint version 전환을 구현한다.
5. raw-goal v1과 typed v0.2의 endpoint/version 협상을 명시한다. 구버전은 지원 기간을 정하고 신규 경로에 legacy typing fallback을 넣지 않는다.
6. FE DTO, Backend OpenAPI, runtime 계약, 예제·tests를 같은 변경으로 맞춘다.
7. 기존 `goal_typing` 호출자와 legacy guidance 경로를 확인한 뒤 이관·제거한다. 문서 분리만으로 실행 파일을 삭제하지 않는다.

기존 checkpoint를 이어갈 경우 저장된 Typed Goal의 canonical 값과 graph 호환성을 검증해 새 Goal DTO로 명시적으로 변환한다. 필요한 값이 없거나 graph 호환성을 보장할 수 없으면 새 session 시작을 안내한다. 원문을 다시 typing해 과거 사용자의 목표를 복원했다고 간주하지 않는다.

신규 제품 경로는 `/api/v1/agent/commands`를 사용한다. `/api/v1/agent/turn`은 legacy 호환 경로이며 신규 FE에서 사용하지 않는다.

## 12. 인수 기준

| 검증 항목 | 기대 결과 |
| --- | --- |
| 현재 등록 Goal + 정확한 slots | Backend가 원문 없이 DB의 `MVP-01` 선택·전달, AI1 실행 |
| 같은 canonical Goal, 다른 선택적 source text | Graph 선택 결과 동일; source text 재분류 없음 |
| `settings.font_size.increase` + `direction=decrease` | slot 충돌 거절; 자동 수정 없음 |
| `goal_type=unknown`, 미등록 감소·밝기 Goal | 실행하지 않음 |
| FE confidence가 높지만 graph 미등록 | 실행하지 않음 |
| 일시정지·취소에 화면 없음 | 소유권·version·episode가 유효하면 제어 처리 |
| 재개에 화면 없음·과거 confirmation | 관측 요청 또는 확인 거절; 옛 좌표 재사용 없음 |
| pause 후 사용자 수동 이동 | 같은 고정 graph에서 새 화면을 기준으로 복구 |
| 진행 중 `start_task` | 현재 작업을 덮어쓰지 않음 |
| 잘못된 새 Goal로 `replace_task` | 기존 episode 보존 |
| 교체 성공 후 이전 episode의 cancel/guide 도착 | 새 작업에 적용하지 않음 |
| 같은 start/replace 요청 재전송 | episode 중복 생성 없음 |
| `observe`에 새 Goal 동봉 | 암묵적 목표 교체 없이 계약 오류 |
| Goal 확인 답변을 action 승인으로 사용 | 허용하지 않음 |
| 종료된 session에 resume | 새 실행 없음 |

FE 품질은 Goal/Command 분류 정확도, slot 정확도, 부정·모호성·제어 오분류, clarification율과 지연으로 평가한다. Backend 품질은 typed 계약·Graph 선택·세션 제어로 평가한다. Agent(1) 품질은 전달 graph 정합성 검사·화면 판단·안내·검증·graph 내부 Re-plan으로 평가한다. Task 성공만으로 FE 분류가 정답이었다고 자동 라벨링하지 않는다.
