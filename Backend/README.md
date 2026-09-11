# Click Backend

## 백엔드 본개발 시작

전체 기능·설계·주차별 작업은 [개발 현황 문서](docs/README.md)를 기준으로 관리한다.
재현 가능한 현재 실행 방법은 [로컬 실행 가이드](docs/runbook.md)를 따른다.
아래 내용은 기존 글자 크기 시범 구현과 개인화 API의 참고 문서다.

```powershell
python -m pip install -e ".[test]"
.\scripts\dev.ps1
```

`/health`는 서버 생존, `/ready`는 DB 연결과 필수 테이블 존재를 확인한다.

신규 Android FE 연동 계약은
[`docs/frontend-backend-contract.md`](../docs/frontend-backend-contract.md)를 따른다.

`POST /api/v1/agent/turn`은 단일 L3 런타임인 `ai_agent.runtime`을 사용한다.
이전 `click_agent` 패키지는 제거되었으며 Goal Typing, 상태 검증, re-plan,
행동 이벤트, trace, scroll 안내가 같은 런타임에서 처리된다. `GEMINI_API_KEY`가
없거나 호출이 실패하면 로컬 keyword scorer로 자동 복귀한다.

## PostgreSQL and personalization

The backend stores user UI profiles, consent history, and UI behavior events in
PostgreSQL. Exact birth dates and raw conversation history are intentionally not
required. Without `DATABASE_URL`, the app uses an in-memory SQLite database for
local tests only.

```powershell
cd C:\click_server\Click
Copy-Item .env.example .env
docker compose up -d postgres

cd Backend
$env:DATABASE_URL="postgresql+psycopg://click:replace-me@localhost:5432/click"
python -m alembic upgrade head
python -m uvicorn app.main:app --reload
```

Personalization flow:

1. `POST /api/v1/users` creates a pseudonymous user and UI profile.
2. `POST /api/v1/users/{id}/consents` records purpose-specific consent.
3. `POST /api/v1/users/{id}/events` records allowed behavior evidence and updates the profile.
4. Add `user_id` to `POST /api/v1/agent/turn`; consented profile context adjusts the guide message.
5. `DELETE /api/v1/users/{id}` deletes the profile, consents, and events together.

Use separate consent purposes for `personalization`, `conversation_storage`, and
`model_training`. This implementation does not store raw conversations or train a
model; both should remain disabled until retention, redaction, and deletion policies
are defined.

## Agent turn API

FE should use `POST /api/v1/agent/turn` for the turn-based integration.

First turn:

```json
{
  "request_id": "req-001",
  "session_id": "session-001",
  "raw_goal": "글자 크기를 키워줘",
  "accessibility_tree": {
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [
      {
        "node_id": "node-12",
        "package_name": "com.android.settings",
        "class_name": "android.widget.TextView",
        "text": "디스플레이",
        "content_description": null,
        "resource_id": "android:id/title",
        "bounds_in_screen": [24, 320, 1056, 408],
        "clickable": true,
        "enabled": true,
        "selected": false,
        "scrollable": false,
        "checkable": false
      }
    ]
  }
}
```

On later turns, keep `session_id`, create a new `request_id`, send a fresh
`accessibility_tree`, and omit `raw_goal`. When responding to a confirmation,
also send `user_confirmation` as `"accepted"` or `"rejected"`.

If the Accessibility Tree is unavailable, FE can send `vision_snapshot` instead.
Use it only after user consent, and prefer redacted/on-device OCR elements over a
raw screenshot.

```json
{
  "request_id": "req-vision-001",
  "session_id": "session-001",
  "raw_goal": "글자 크기를 키워줘",
  "vision_snapshot": {
    "user_consented": true,
    "redaction_applied": true,
    "provider": "frontend_ocr",
    "package_name": "com.android.settings",
    "screen_width": 1080,
    "screen_height": 2400,
    "elements": [
      {
        "element_id": "display",
        "text": "디스플레이",
        "bounds_in_screen": [24, 320, 1056, 408],
        "actionable": true
      }
    ]
  }
}
```

The response contains a `guide_command`. FE renders the overlay using
`target_bounds`; `tap`, `drag`, and `scroll` are guidance types and must not be
treated as permission to operate the UI automatically. A `confirmation`
command must be shown to the user before sending the next turn.

Sessions and request-id responses are currently stored in process memory. They
are cleared on restart and are not shared between server processes; use Redis
or a database before multi-process deployment.

Android 설정 앱에서 글자 크기를 키우는 과정을 오버레이로 안내하는 MVP API다. 세션은 현재 프로세스 메모리에 저장되며 서버 재시작 시 초기화된다.

## 실행

PowerShell에서 다음 명령을 실행한다.

```powershell
cd C:\click_server\Click\Backend
.\click\Scripts\Activate.ps1
python -m pip install -e ".[test]"
python -m uvicorn app.main:app --reload
```

- API 문서: `http://127.0.0.1:8000/docs`
- 상태 확인: `GET http://127.0.0.1:8000/health`

## API 흐름

1. `POST /api/v1/sessions`로 목표를 전달한다.
2. 프론트가 설정 앱의 Accessibility Tree를 `POST /api/v1/sessions/{session_id}/observations`로 반복 전달한다.
3. 응답의 `guide.target_bounds`, `message`, `action_type`을 사용해 오버레이를 렌더링한다.
4. `CONFIRM_REQUIRED` 응답에 사용자가 동의하면 같은 화면을 `confirmed: true`와 함께 다시 전달한다.
5. `GET /api/v1/sessions/{session_id}`로 현재 세션 상태를 조회한다.

### 세션 생성 예시

```json
{
  "goal": "휴대폰 글자를 크게 해줘",
  "locale": "ko-KR",
  "device_context": {
    "manufacturer": "Samsung",
    "android_version": "16"
  }
}
```

### 화면 전달 예시

```json
{
  "snapshot": {
    "package_name": "com.android.settings",
    "captured_at": "2026-08-29T00:00:00Z",
    "screen_width": 1080,
    "screen_height": 2400,
    "nodes": [
      {
        "node_id": "node_12",
        "text": "디스플레이",
        "bounds": [24, 320, 1056, 408],
        "clickable": true,
        "enabled": true,
        "role": "menu_item"
      }
    ]
  },
  "confirmed": false
}
```

## 테스트

```powershell
python -m pytest
```
