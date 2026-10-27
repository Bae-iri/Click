# FE/BE Agent 연동 계약

**현재 신규 계약:** FE가 Goal Type·Command Type을 확정하고 Backend가 DB에서 Task Graph를 선택·전달하며 Agent(1)은 전달 graph를 실행한다. 공개 endpoint는 `POST /api/v1/agent/commands`이고 상세 형식은 [FE Goal·Command 전달 계약](./fe-goal-command-contract.md)을 따른다. 아래 `/agent/turn` 본문은 전환 기간에만 유지하는 legacy 계약이다.

**Current contract:** [Backend API](../Backend/docs/README.md#API): accepted confirmations require confirmation_id, expected_version and an unchanged fresh observation; raw_goal is omitted. Legacy /api/v1/sessions is disabled by default. History, feedback, account verification/recovery and consent APIs are ready for app integration.

**Privacy contract:** [Backend privacy](../Backend/docs/README.md#개인정보) takes precedence over older examples: graph-derived step/total_steps, reduced debug fields, no raw-image external processing, event metadata allowlist, and 503/504 retry handling. Use accessibility data or consented on-device OCR elements.

## 세션·재전송

신규 turn 세션은 DB에 저장한다. 첫 turn 응답의 `session_version`을 보관하고 이후 요청에 `expected_version`으로 보낸다. 정확한 재전송은 이전 ID·본문·version을 그대로 유지한다. 409 응답은 `X-Conflict-Code`를 보고 처리 중 재시도와 오래된 화면 재수집을 구분한다. 조회 `GET /api/v1/agent/sessions/{id}`, 취소 `POST /api/v1/agent/sessions/{id}/cancel`이 추가됐다. 취소 이후 또는 최신 version보다 오래된 안내 응답은 적용하지 않는다.

`source_text`, UI label 샘플, target_label 및 일부 진단 필드는 저장·응답에서 제거한다. 표시할 안내 문구와 target_bounds를 사용한다. [현재 세션 계약](../Backend/docs/README.md#API)을 따른다. 아래 본문 예시는 최초 요청 중심이며 후속 요청에는 expected_version을 추가해야 한다.

## 인증

사용자·Agent·레거시 세션 API에 `Authorization: Bearer <access_token>`이 필수다.
`/api/v1/auth/register` 또는 `/login`으로 받은 토큰을 사용한다. 토큰이 없거나 만료되면 401을 반환하므로 갱신·재로그인 흐름을 처리한다.
토큰 갱신·로그아웃·공통 오류 규약은 [서버 인증 계약](../Backend/docs/README.md#API)을 따른다. 아래 turn 본문 규격은 유지하며 user_id는 서버가 인증 사용자로 제한한다.

legacy FE 연동은 `POST /api/v1/agent/turn`을 사용한다. 신규 FE는 `/api/v1/agent/commands`를 사용한다. 기존
`/api/v1/sessions` 계열은 이전 버전 호환을 위해 남겨 두었으며 신규 코드에서는
사용하지 않는다.

## 요청 흐름

첫 요청은 FE가 생성한 `request_id`, `session_id`, 사용자의 `raw_goal`과 현재
화면 관찰 정보를 함께 보낸다. 기본 관찰 정보는 Accessibility Tree이고, Tree를
받지 못하는 화면에서는 빈 tree로 일회성 Vision 동의를 요청한다. 사용자 허용 뒤 새 AX 문맥과 `vision_snapshot`을 함께 보낸다.

```json
{
  "request_id": "req-001",
  "session_id": "session-001",
  "user_id": "2d17b7d8-dbb6-41c2-89d7-e7789d52ee31",
  "raw_goal": "글자 크기를 키우고 싶어",
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "captured_at": "2026-08-30T10:00:00Z",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [
      {
        "node_id": "display-title",
        "text": "디스플레이",
        "content_description": null,
        "resource_id": "com.android.settings:id/title",
        "class_name": "android.widget.TextView",
        "role": null,
        "bounds_in_screen": [24, 320, 1056, 408],
        "clickable": true,
        "enabled": true,
        "selected": false,
        "scrollable": false,
        "checkable": false,
        "extras": {}
      }
    ]
  }
}
```

- `request_id`: 매 API 호출마다 새로운 값
- `session_id`: 같은 작업이 진행되는 동안 동일한 값
- `user_id`: 개인화를 사용하는 경우 전달하는 사용자 UUID
- `raw_goal`: 첫 요청에서 필수, 후속 요청에서는 생략
- `accessibility_tree`: 기본 화면 관찰 값. `vision_snapshot`이 없으면 필수
- `vision_snapshot`: Accessibility Tree를 못 받거나 target을 못 찾은 경우의 fallback
- `captured_at`: ISO 8601 UTC 권장
- `bounds_in_screen`: `[left, top, right, bottom]`, physical pixel 기준
- `bounds`: `bounds_in_screen`의 입력 alias로도 허용하지만 FE DTO는 한 이름으로
  통일하기 위해 `bounds_in_screen`을 권장

사용자가 화면을 조작한 후에는 Accessibility 이벤트를 기다려 새 Tree를 캡처하고,
새 `request_id`와 기존 `session_id`로 다시 호출한다.

조작 결과는 가능하면 `user_action_event`로 함께 보낸다.

```json
{
  "user_action_event": {
    "action_type": "tap_completed",
    "guide_command_id": "guide-session-001-step-1",
    "target_node_id": "display-title",
    "target_bounds": [24, 320, 1056, 408]
  }
}
```

허용 값은 `tap_completed`, `drag_completed`, `scroll_completed`,
`scroll_failed`, `back_navigated`, `manual_override`, `observe_only`다.

## Vision fallback

AX가 부족하거나 target을 찾지 못하면 매 분석 1회의 사용자 확인을 받는다. 최초에는 이미지 없이 빈 AX와 package/window/화면 크기를 보내고, BE가 `purpose=vision`인 일회성 동의 ID를 발급한다. 허용한 뒤에만 기기 내 캡처·OCR을 수행하고 `respond_confirmation`에 동의 ID, session/episode/version, 새 AX와 `vision_snapshot`을 함께 보낸다. `user_consented=true`만으로는 승인되지 않는다.

FE는 입력 필드를 마스킹하고 허용된 설정 메뉴 텍스트와 실제 bounds만 보낸다. BE도 재검사하며 원본 screenshot은 거절한다. 마스킹 실패 시 데이터 없이 `rejected` + `vision_failure=masking_failed`를 보내고 안내를 중단한다. 재분석에는 새로운 사용자 확인이 필요하다.

필드 정의, 요청/응답 예시, 개인정보 마스킹 및 실패 대응은 [Vision fallback 실행 계약](./ai-agent-vision-fallback.md)을 따른다. `metadata.debug.vision_fallback.status`는 `not_requested`, `consent_required`, `used`, `not_configured`, `provider_error`, `no_signal`, `masking_failed` 중 해당 상태를 전달한다.

## Confirmation

응답의 `guide_command.command_type`이 `confirmation`이면 FE가 확인 UI만 표시한다.
사용자가 승인하거나 거절한 후 fresh Accessibility Tree와 다음 값을 보낸다.

```json
{
  "user_confirmation": "accepted"
}
```

또는:

```json
{
  "user_confirmation": "rejected"
}
```

`confirmed: true`는 `/agent/turn` 계약이 아니다.

## Slider 값 검증

SeekBar가 제공하는 값 중 가능한 값을 `extras`에 넣는다. 현재 BE는
`progress`, `value`, `current`, `range_current`, `percent` 순으로 숫자를 찾는다.

```json
{
  "node_id": "font-slider",
  "text": "글자 크기",
  "class_name": "android.widget.SeekBar",
  "role": "slider",
  "bounds_in_screen": [160, 430, 920, 480],
  "extras": {
    "progress": 3
  }
}
```

BE는 같은 `node_id`의 이전 값과 현재 값을 비교한다. 값이 실제로 증가하기 전에는
task를 완료 처리하지 않는다. 검증 결과는 응답의
`metadata.verification.slider_value_increased`에서 확인할 수 있다.

## 응답 처리

FE는 `guide_command`를 렌더링한다.

- `action_type: tap`: target highlight만 표시
- `action_type: drag`: 방향 cue만 표시
- `action_type: scroll`: 스크롤 안내 표시
- `command_type: confirmation`: 확인 UI 표시
- `command_type: done`: 완료 UI 표시
- `command_type: blocked`: 중단 사유 표시

`tap`과 `drag`는 자동 조작 권한이 아니다. 실제 조작은 사용자가 수행한다.
`scroll`만 FE Accessibility Service가 실행하며, 실행 후 fresh tree와 결과 이벤트를
다시 전송한다.

현재 화면에서 설정 앱 아이콘을 찾지 못하면 Agent는 target 없는 `observe_only`
command와 함께 `render_policy.recovery_action`을 보낼 수 있다. FE는 이 값을 보고
사용자 활성화가 필요한 버튼 또는 안내를 표시한다. recovery 실행 뒤에는 fresh
Accessibility Tree와 `user_action_event.action_type: manual_override`를 다시 보낸다.

```json
{
  "guide_command": {
    "command_type": "highlight",
    "action_type": "observe_only",
    "message": "설정 앱을 현재 화면에서 찾지 못했어요. 설정 앱을 열 수 있는 화면으로 이동해 주세요.",
    "target_node_id": null,
    "requires_user_action": true,
    "render_policy": {
      "recovery_action": {
        "type": "open_android_settings_or_show_app_drawer",
        "target_app": "android_settings",
        "android_intent_action": "android.settings.SETTINGS",
        "fallback_screen": "launcher_or_app_drawer",
        "requires_user_activation": true,
        "reason": "settings_app_icon_not_visible"
      },
      "expected_next_observation": {
        "state_id": "S1_SETTINGS_HOME",
        "package_candidates": [
          "com.android.settings",
          "com.samsung.android.settings"
        ],
        "send_fresh_accessibility_tree": true,
        "send_user_action_event": "manual_override"
      }
    }
  }
}
```

지원 패키지는 현재 다음 두 개다.

- `com.android.settings`
- `com.samsung.android.settings`

일반 target match 실패에서는 `render_policy.fallback_action`이 내려간다.

```json
{
  "fallback_action": {
    "type": "request_fresh_observation",
    "reason": "No matching target node found in current viewport.",
    "expected_next_observation": {
      "send_fresh_accessibility_tree": true,
      "send_vision_snapshot_if_target_missing": true
    }
  }
}
```

FE 연동 디버깅은 응답의 `metadata.debug`를 우선 본다.

- `metadata.debug.observation`: Agent가 실제로 받은 tree/vision 수, package,
  normalized node 수, visible role/tag, vision synthetic node 수
- `metadata.debug.fallback`: 선택된 fallback type, reason, recovery_action,
  fallback_action, expected_next_observation
- `metadata.debug.agent_chain`: Goal Typing, Screen Classifier, Matcher,
  Verification, Replanner, Guide Builder 연결 상태
