# FE 구현 계약: agent-fe-contract.v0.3

2026-10-10 FE 요청·결과 확인 흐름 통합. 모든 명령은 로그인 토큰을 붙여 `POST /api/v1/agent/commands`로 보낸다. 기존 v0.2 명령과 아래의 기존 v0.3 accepted/rejected 계약은 계속 지원한다. 실제 기기·운영 서버 적용 검증은 별도로 필요하다.

## 새 앱의 결과 확인 흐름

FE는 OpenAPI에서 v0.3와 `workflow_version`을 확인한 경우에만 `start_task`에
`workflow_version: "interactive.v1"`을 보낸다. 이 옵션이 없는 기존 클라이언트는 기존 완료 흐름을 유지한다.
새 옵션 없이 시작한 작업에 새 결과 응답을 보내면 409로 거절한다.

- runtime의 실제 완료 검증 뒤에만 `WAITING_RESULT`와 `metadata.result_prompt`를 반환한다.
  `blocked`·관측 부족·안전 차단·Backend 경로 재선택 요청은 이 흐름에 포함되지 않는다.
- prompt에는 `id`, `message`, `expires_at`(UTC epoch seconds, 120초)이 있다.
  `respond_result`는 `user_result`와 `result_prompt_id`를 보내고 `result_response`와 혼용하지 않는다.
  기존 session/episode/expected_version 및 request_id 재시도 규칙을 적용한다.
- `satisfied` → COMPLETED, `stopped` → CANCELLED, 만료 후 `no_response` → EXPIRED.
  만료 전 no_response나 만료 후 satisfied/needs_help/stopped는 409다.
- 글자 크기의 `needs_help` → WAITING_ADJUSTMENT. `metadata.follow_up.choices`의
  `{id, label}`을 표시하고 `adjust_task`에 `adjustment_reason: too_small | too_large`를 보낸다.
  Backend가 저장된 목표에서 increase/decrease를 만들고 승인 graph를 검증한 뒤 새 episode를 생성한다.
  이 명령에는 화면이나 임의 goal을 넣지 않는다. 응답 후 최신 화면을 observe로 전달한다.
- `other`/`unsure`는 자동으로 설정을 변경하지 않고 추가 확인을 유지한다.
  자동 추가 조절을 지원하지 않는 설정의 needs_help는 현재 요청을 CANCELLED로 닫고 새 요청을 받는다.
- 새 episode는 이전 checkpoint·안내·위험 action 승인을 재사용하지 않는다.
  새 화면을 관측하고 새 승인을 받은 후 조작한다.
- GET `/agent/sessions/{id}`의 `result_prompt`/`follow_up`으로 대기 상태를 복원한다.
  pause/resume도 결과·조절 대기 상태를 보존하며 queue는 이 상태를 실행 중으로 취급한다.

배포 전에 Alembic `20261010_11` 적용이 필요하다. 신규 `agent_sessions.workflow` JSON에는
결과 prompt, 사용자 답변 코드와 조절 상태만 저장하며 화면 원문을 저장하지 않는다.
이 변경은 자동 graph 재선택이나 전체 실행 이벤트 저장 기능을 추가하지 않는다.

## 공통 규칙

- `contract_version`: `agent-fe-contract.v0.3`
- `request_id`: 요청별 UUID. 네트워크 재시도에는 같은 ID와 같은 body를 사용한다.
- `session_id`: queue claim의 `running.agent_session_id`.
- `expected_version`: 마지막 서버 응답의 `session_version`. claim 직후에는 0.
- 시작 후 `episode_id`는 서버 응답값을 사용한다. 조절로 episode가 바뀌면 이전 안내·관측을 폐기한다.
- 409 충돌이면 상태를 다시 조회한다. 오래된 화면·episode로 자동 재전송하지 않는다.
- 목표 확인, 위험 action 확인, 결과 확인은 서로 다른 단계다.

## 1. 입력 → 대기열 → 목표 확인

기존 enqueue body는 `{"id":"UUID","raw_goal":"글자크기 키우고싶어","priority":"NORMAL"}`. 이어 `/api/v1/voice-requests/next/claim`을 호출한다. 음성·텍스트 모두 이 흐름을 사용한다.

claim의 세션에 다음을 보낸다. 이 명령은 작업을 실행하거나 세션 버전을 증가시키지 않는다. claim되지 않은 임의의 새 세션 ID에는 404를 반환한다.

```json
{
  "contract_version": "agent-fe-contract.v0.3",
  "command_type": "resolve_goal",
  "request_id": "새 UUID",
  "session_id": "claim에서 받은 agent_session_id",
  "expected_version": 0,
  "source_text": "글자크기 키우고싶어",
  "device_context": {"platform": "android", "locale": "ko-KR"}
}
```

응답은 `response_type=goal_resolution`이며 `episode_id`·`guide_command`가 없다. FE 응답 파서는 이 유형을 기존 guidance/control보다 먼저 분기해야 한다.

```json
{
  "contract_version": "agent-fe-contract.v0.3",
  "request_id": "요청 UUID",
  "session_id": "요청 session_id",
  "session_version": 0,
  "response_type": "goal_resolution",
  "resolution_status": "confirmation_required",
  "goal": {
    "catalog_version": "2.1.0",
    "goal_type": "settings.font_size.increase",
    "target_app": "android_settings",
    "slots": {"setting": "font_size", "direction": "increase"}
  },
  "display_name": "글자 크기 키우기",
  "message": "글자 크기 키우기 안내를 시작할까요?"
}
```

`confirmation_required`이면 message를 표시하고 사용자의 명시적 확인을 받는다. 확인 후 반환된 goal과 현재 device_context·accessibility_tree를 기존 start_task 요청에 담아 전송한다. start_task에는 episode_id를 넣지 않는다. 반대 방향·다른 목표로 바꾸면 새 목표를 다시 확인한다. 거절 시 `/api/v1/agent/sessions/{session_id}/cancel`에 `{"expected_version":0}`을 보내고 queue를 다시 조회한다.

`needs_clarification`이면 goal=null이며 구체적인 요청을 질문한다. `unsupported`이면 승인 실행 graph가 없어 시작할 수 없다. 부정 표현·동점 후보는 자동 실행하지 않는다. 현재 승인 graph는 글자 크기 확대·축소다. 분류는 Backend 입력 해석 계층에서 수행하며 Agent 화면 실행 루프에 자연어 분류를 넣지 않는다.

## 2. 실행 중 확인

기존 `respond_confirmation`을 사용한다. `user_confirmation`, 서버 발급 `confirmation_id`, 현재 화면, episode_id, expected_version을 함께 보낸다. resolve_goal의 목표 확인이나 결과 만족 응답을 이 명령으로 보내지 않는다.

## 3. 진행 중 같은 설정 조절

`adjust_task`는 IN_PROGRESS / WAITING_CONFIRMATION / PAUSED 세션에서 같은 설정의 새 typed goal로 실행 episode를 교체한다. 새 episode로 현재 화면부터 재평가하며 이전 위험 action 승인은 폐기한다. 다른 설정으로 바꾸는 것은 기존 replace_task를 사용한다. 완료된 세션은 재활용하지 않는다.

```json
{
  "contract_version": "agent-fe-contract.v0.3",
  "command_type": "adjust_task",
  "request_id": "새 UUID",
  "session_id": "현재 session_id",
  "expected_version": 3,
  "episode_id": "현재 episode_id",
  "goal": {
    "catalog_version": "2.1.0",
    "goal_type": "settings.font_size.decrease",
    "target_app": "android_settings",
    "slots": {"setting": "font_size", "direction": "decrease"}
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

예시의 빈 nodes는 요청 구조 설명용이며 실제 구현은 최신 접근성 관측을 보낸다. 응답은 기존 guidance 형식, `command_result.command_type=adjust_task`, 새로운 episode_id와 session_version이다. 사용자가 방향을 확인한 뒤 요청한다.

## 4. 검증 완료 후 결과 확인

runtime의 task_status=COMPLETED 응답을 받은 뒤 “원하는 크기가 되었나요?”를 표시한다. 그 전에는 respond_result가 409로 거절된다. 사용자가 만족했다고 응답하는 것으로 runtime 검증을 대체하지 않는다.

```json
{
  "contract_version": "agent-fe-contract.v0.3",
  "command_type": "respond_result",
  "request_id": "새 UUID",
  "session_id": "완료된 session_id",
  "expected_version": 4,
  "episode_id": "완료된 episode_id",
  "result_response": "accepted"
}
```

만족은 accepted, 불만족은 rejected. 화면·goal·user_confirmation·confirmation_id는 보내지 않는다. 기존 control 형식으로 응답하고 metadata.result_response에 결과를 반환한다. 결과를 한 번만 기록하며 session_version이 1 증가한다. 같은 request_id/body 재시도는 같은 응답을 반환한다. 다른 ID로 중복 기록하거나 episode/version이 다르면 409다. session 조회에도 result_response가 포함된다. task_status는 COMPLETED로 유지한다.

만족 여부와 무관하게 완료된 queue 항목은 기존 `/voice-requests/{id}/complete`로 정리한다. 추가 조절을 원하면 새 텍스트 요청을 enqueue → claim → resolve_goal → 확인 → start_task로 시작한다. 서버의 queue claim도 완료 세션을 자동 정리하므로 FE는 claim 응답의 running/id를 다시 확인한다. 완료된 세션에 adjust_task를 보내지 않는다.

## 배포와 검증

새 컬럼 agent_sessions.result_response를 위한 Alembic revision `20261009_10`이 필요하다. Render pre-deploy에서 `python scripts/render_start.py --migrate`를 실행한 뒤 서버를 시작한다. 마이그레이션 전 새 서버의 /ready는 schema_missing으로 응답할 수 있다.

FE는 v0.3 API가 배포된 뒤 위 흐름을 사용한다. OpenAPI는 Backend/docs/openapi.json을 참고한다. 현재 설치된 앱 및 원격 서버가 이 로컬 변경을 이미 사용한다고 가정하지 않는다.
