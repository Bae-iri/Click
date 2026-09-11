from datetime import datetime, timezone

from tests.helpers import authenticated_client as TestClient

from app.main import create_app


client = TestClient(create_app())


def node(
    node_id: str,
    text: str,
    bounds: list[int],
    *,
    clickable: bool = True,
    scrollable: bool = False,
    class_name: str = "android.widget.TextView",
    role: str = "menu_item",
) -> dict:
    return {
        "node_id": node_id,
        "text": text,
        "bounds": bounds,
        "clickable": clickable,
        "enabled": True,
        "scrollable": scrollable,
        "class_name": class_name,
        "role": role,
    }


def snapshot(nodes: list[dict], package_name: str = "com.android.settings") -> dict:
    return {
        "snapshot": {
            "package_name": package_name,
            "captured_at": datetime.now(timezone.utc).isoformat(),
            "screen_width": 1080,
            "screen_height": 2400,
            "nodes": nodes,
        }
    }


def create_session() -> str:
    response = client.post("/api/v1/sessions", json={"goal": "휴대폰 글자를 크게 해줘"})
    assert response.status_code == 201
    return response.json()["session_id"]


def test_health() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_rejects_unsupported_goal() -> None:
    response = client.post("/api/v1/sessions", json={"goal": "와이파이를 켜줘"})
    assert response.status_code == 422


def test_settings_home_returns_display_highlight() -> None:
    session_id = create_session()
    response = client.post(
        f"/api/v1/sessions/{session_id}/observations",
        json=snapshot(
            [
                node("title", "설정", [0, 0, 1080, 120], clickable=False, role="heading"),
                node("display", "디스플레이", [24, 320, 1056, 408]),
                node("accessibility", "접근성", [24, 420, 1056, 508]),
            ]
        ),
    )
    body = response.json()
    assert response.status_code == 200
    assert body["state_id"] == "S1_SETTINGS_HOME"
    assert body["guide"]["command_type"] == "highlight"
    assert body["guide"]["target_node_id"] == "display"
    assert body["guide"]["target_bounds"] == [24, 320, 1056, 408]


def test_full_font_size_guidance_flow() -> None:
    session_id = create_session()

    home = snapshot(
        [
            node("title", "설정", [0, 0, 1080, 120], clickable=False, role="heading"),
            node("display", "디스플레이", [24, 320, 1056, 408]),
        ]
    )
    assert client.post(f"/api/v1/sessions/{session_id}/observations", json=home).json()["guide"]["target_node_id"] == "display"

    display = snapshot(
        [
            node("title", "디스플레이", [0, 0, 1080, 120], clickable=False, role="heading"),
            node("font", "글자 크기와 스타일", [24, 520, 1056, 608]),
            node("brightness", "밝기", [24, 220, 1056, 308]),
        ]
    )
    display_result = client.post(f"/api/v1/sessions/{session_id}/observations", json=display).json()
    assert display_result["state_id"] == "S2_DISPLAY_MENU"
    assert display_result["guide"]["target_node_id"] == "font"

    font_menu = snapshot(
        [
            node("title", "글자 및 화면 크기", [0, 0, 1080, 120], clickable=False, role="heading"),
            node("font-size", "글자 크기", [24, 420, 1056, 508]),
            node("font-style", "글꼴 스타일", [24, 520, 1056, 608]),
        ]
    )
    font_result = client.post(f"/api/v1/sessions/{session_id}/observations", json=font_menu).json()
    assert font_result["state_id"] == "S3_FONT_MENU"
    assert font_result["guide"]["target_node_id"] == "font-size"

    control = snapshot(
        [
            node("title", "글자 크기", [0, 0, 1080, 120], clickable=False, role="heading"),
            node(
                "slider",
                "글자 크기 슬라이더",
                [100, 900, 980, 1020],
                class_name="android.widget.SeekBar",
                role="slider",
            ),
        ]
    )
    confirmation = client.post(f"/api/v1/sessions/{session_id}/observations", json=control).json()
    assert confirmation["task_status"] == "WAITING_CONFIRMATION"
    assert confirmation["guide"]["command_type"] == "confirmation"

    control["confirmed"] = True
    drag = client.post(f"/api/v1/sessions/{session_id}/observations", json=control).json()
    assert drag["guide"]["action_type"] == "drag"
    assert drag["guide"]["render_policy"]["direction"] == "right"

    completed = client.post(f"/api/v1/sessions/{session_id}/observations", json=snapshot(control["snapshot"]["nodes"])).json()
    assert completed["task_status"] == "COMPLETED"
    assert completed["guide"]["command_type"] == "done"


def test_scroll_hint_when_target_is_off_screen() -> None:
    session_id = create_session()
    payload = snapshot(
        [
            node("title", "설정", [0, 0, 1080, 120], clickable=False, role="heading"),
            node("list", "설정 목록", [0, 120, 1080, 2200], clickable=False, scrollable=True, role="list"),
        ]
    )
    result = client.post(f"/api/v1/sessions/{session_id}/observations", json=payload).json()
    assert result["guide"]["action_type"] == "scroll"
    assert result["guide"]["render_policy"]["direction"] == "down"

