# Click Backend

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

