from fastapi.testclient import TestClient

from app.main import create_app


def tree() -> dict:
    return {
        "screen_width": 1080,
        "screen_height": 2400,
        "nodes": [
            {
                "node_id": "display",
                "package_name": "com.android.settings",
                "class_name": "android.widget.TextView",
                "text": "Display",
                "content_description": None,
                "resource_id": "android:id/title",
                "bounds_in_screen": [24, 320, 1056, 408],
                "clickable": True,
                "enabled": True,
                "selected": False,
                "scrollable": False,
                "checkable": False,
            }
        ],
    }


def test_first_and_follow_up_turn_use_client_session_id() -> None:
    client = TestClient(create_app())
    first_payload = {
        "request_id": "request-1",
        "session_id": "session-from-fe",
        "raw_goal": "increase font size",
        "accessibility_tree": tree(),
    }
    first = client.post("/api/v1/agent/turn", json=first_payload)
    assert first.status_code == 200
    assert first.json()["request_id"] == "request-1"
    assert first.json()["session_id"] == "session-from-fe"
    assert "guide_command" in first.json()

    follow_up = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "request-2",
            "session_id": "session-from-fe",
            "accessibility_tree": tree(),
        },
    )
    assert follow_up.status_code == 200


def test_retried_request_id_returns_same_response() -> None:
    client = TestClient(create_app())
    payload = {
        "request_id": "retry-me",
        "session_id": "retry-session",
        "raw_goal": "increase font size",
        "accessibility_tree": tree(),
    }
    first = client.post("/api/v1/agent/turn", json=payload)
    retried = client.post("/api/v1/agent/turn", json=payload)
    assert retried.status_code == 200
    assert retried.json() == first.json()


def test_unknown_session_requires_raw_goal() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "request-without-goal",
            "session_id": "unknown-session",
            "accessibility_tree": tree(),
        },
    )
    assert response.status_code == 404


def test_request_id_cannot_be_reused_across_sessions() -> None:
    client = TestClient(create_app())
    first = {
        "request_id": "shared-request-id",
        "session_id": "session-a",
        "raw_goal": "increase font size",
        "accessibility_tree": tree(),
    }
    assert client.post("/api/v1/agent/turn", json=first).status_code == 200
    second = {**first, "session_id": "session-b"}
    assert client.post("/api/v1/agent/turn", json=second).status_code == 409


def test_rejected_confirmation_blocks_without_automatic_action() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "rejected-request",
            "session_id": "rejected-session",
            "raw_goal": "increase font size",
            "accessibility_tree": tree(),
            "user_confirmation": "rejected",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["task_status"] == "BLOCKED"
    assert body["guide_command"]["command_type"] == "blocked"
    assert body["guide_command"]["action_type"] == "observe_only"
