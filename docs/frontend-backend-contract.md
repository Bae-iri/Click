# FE/BE Agent 연동 계약

신규 FE 연동은 `POST /api/v1/agent/turn`만 사용한다. 기존
`/api/v1/sessions` 계열은 이전 버전 호환을 위해 남겨 두었으며 신규 코드에서는
사용하지 않는다.

## 요청 흐름

첫 요청은 FE가 생성한 `request_id`, `session_id`, 사용자의 `raw_goal`과 현재
Accessibility Tree를 함께 보낸다.

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
- `captured_at`: ISO 8601 UTC 권장
- `bounds_in_screen`: `[left, top, right, bottom]`, physical pixel 기준
- `bounds`: `bounds_in_screen`의 입력 alias로도 허용하지만 FE DTO는 한 이름으로
  통일하기 위해 `bounds_in_screen`을 권장

사용자가 화면을 조작한 후에는 Accessibility 이벤트를 기다려 새 Tree를 캡처하고,
새 `request_id`와 기존 `session_id`로 다시 호출한다.

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

지원 패키지는 현재 다음 두 개다.

- `com.android.settings`
- `com.samsung.android.settings`
