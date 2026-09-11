# API Specification

현재 구현의 필드·타입·필수값은 [OpenAPI](openapi.json)가 기준이다. `python scripts/export_openapi.py`로 코드에서 재생성한다. 서버에서는 `/docs`와 `/openapi.json`으로 확인한다.

## 구현된 API

| Method | Path | 성공 | 목적 |
|---|---|---|---|
| GET | /health | 200 | 프로세스 응답 확인 |
| GET | /ready | 200 / 준비 안 됨 503 | DB 연결·테이블 존재 확인 |
| POST | /api/v1/agent/turn | 200 | 목표·관찰 → 다음 안내 |
| POST | /api/v1/auth/register | 201 | 계정·프로필 생성 및 토큰 발급 |
| POST | /api/v1/auth/login | 200 | 로그인·토큰 발급 |
| POST | /api/v1/auth/refresh | 200 | 토큰 쌍 회전 |
| POST | /api/v1/auth/logout | 204 | 현재 로그인 폐기 |
| POST | /api/v1/auth/password | 204 | 비밀번호 변경·모든 로그인 폐기 |
| GET | /api/v1/users/me | 200 | 인증 사용자 조회 |
| GET | /api/v1/users/{user_id} | 200 | 사용자·프로필 조회 |
| PATCH | /api/v1/users/{user_id}/profile | 200 | 안내 선호도 수정 |
| POST | /api/v1/users/{user_id}/consents | 201 | 목적별 동의·철회 이력 추가 |
| POST | /api/v1/users/{user_id}/events | 201 | 동의된 행동 이벤트 기록·프로필 갱신 |
| DELETE | /api/v1/users/{user_id} | 204 | 사용자와 관계 데이터 삭제 |

사용자·안내 API는 Bearer 토큰이 필수다. UUID만으로 인증하지 않는다. 인증·토큰 만료·401/403 처리와 Android 변경 사항은 [2주 차 계약](week2.md)을 따른다.

레거시 `POST /api/v1/sessions`, `GET /api/v1/sessions/{session_id}`, `POST /api/v1/sessions/{session_id}/observations`는 별도 서비스의 호환용 경로다. turn 세션을 이 GET으로 조회할 수 없다.

## Turn 예시

```json
{
  "request_id": "example-turn-001",
  "session_id": "example-session-001",
  "raw_goal": "글자 크기를 키워줘",
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [{
      "node_id": "settings-title",
      "text": "설정",
      "class_name": "android.widget.TextView",
      "bounds_in_screen": [24, 100, 1056, 200]
    }, {
      "node_id": "display",
      "text": "디스플레이",
      "bounds_in_screen": [24, 320, 1056, 408],
      "clickable": true
    }]
  }
}
```

응답은 `request_id`, `session_id`, `task_id`, `step`, `total_steps`, `state_id`, `state_confidence`, `task_status`, `guide_command`, `metadata`를 포함한다. guide의 좌표는 기기 화면의 physical pixel이다. 구체적인 응답은 [샘플 요청](../examples/agent-turn.json)을 서버로 보내 확인한다.

- 첫 turn: `raw_goal`과 tree 또는 vision 필요. 기존 세션의 후속 turn: raw_goal 생략, 같은 session ID, 새 request ID.
- 네트워크 재전송: 원래 ID와 동일 본문 유지. 현재 캐시는 본문 해시를 검사하지 않아 **동일 ID·다른 본문 거절은 향후 작업**이다.
- 확인 응답: `user_confirmation: "accepted"` 또는 `"rejected"`와 새 화면 전송.
- 행동 보고: `user_action_event`에 `action_type`과 관련 guide ID를 전달. 이것만으로 성공을 확정하지 않는다.
- `guide_command.command_type`: highlight / gesture_hint / confirmation / done / blocked.
- `guide_command.action_type`: tap / drag / scroll / observe_only. tap·drag는 안내이며 자동 조작 허가가 아니다.
- 전체 FE 처리 규칙과 vision 예시는 [기존 계약](../../docs/frontend-backend-contract.md) 참조.

## 개인화 예시

`/auth/register`에 email·password와 선택 age_group을 보내 등록하고 토큰을 받는다. 인증 헤더를 넣고 생성된 user_id로 동의를 기록한다.

```json
{"purpose":"personalization","consented":true,"policy_version":"v1"}
```

이벤트 예시는 `{"event_type":"guide_repeated","metadata":{}}`. 철회는 같은 목적에 `consented:false`를 추가한다. 대화 저장·학습 목적의 동의 기록이 존재해도 현재 실제 대화 저장·학습 기능은 없다.

## 설계안: 아직 없는 API

| 후보 API | 입력·출력 방향 | 선행 작업 |
|---|---|---|
| GET /api/v1/agent/sessions/{id} | 인증된 본인의 상태·최근 guide ID·version | 영속 세션·인증 |
| POST /api/v1/agent/sessions/{id}/cancel | 기대 version → 취소 상태; 중복 취소는 같은 결과 | 상태 전이·경쟁 제어 |
| GET /api/v1/users/me/history | cursor·limit → 최소 task 결과·다음 cursor | 보존 정책·소유권 |
| POST /api/v1/agent/sessions/{id}/feedback | 제한된 rating·reason enum → 생성 ID | 최소 정보 스키마·동의 정책 |

위 경로는 아직 호출할 수 없다. 새 상태·인증·오류 envelope 변경은 FE와 함께 적용한다.
