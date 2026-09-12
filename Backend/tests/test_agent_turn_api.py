import pytest
from tests.helpers import authenticated_client as TestClient

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
            "expected_version": first.json()["session_version"],
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


def slider_tree(progress: int) -> dict:
    return {
        "package_name": "com.android.settings",
        "captured_at": "2026-08-30T10:00:00Z",
        "screen_width": 1080,
        "screen_height": 2400,
        "nodes": [
            {
                "node_id": "font-slider",
                "text": "Font size",
                "class_name": "android.widget.SeekBar",
                "role": "slider",
                "bounds": [160, 430, 920, 480],
                "clickable": True,
                "extras": {"progress": progress},
            }
        ],
    }


def test_slider_progress_must_increase_before_task_completes() -> None:
    client = TestClient(create_app())
    first = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "slider-1",
            "session_id": "slider-session",
            "raw_goal": "increase font size",
            "accessibility_tree": slider_tree(3),
        },
    )
    assert first.status_code == 200
    assert first.json()["guide_command"]["command_type"] == "confirmation"
    assert first.json()["metadata"]["verification"]["slider_values"] == {
        "font-slider": 3.0
    }

    accepted = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "slider-2",
            "session_id": "slider-session",
            "expected_version": first.json()["session_version"],
            "accessibility_tree": slider_tree(3),
            "user_confirmation": "accepted",
            "confirmation_id": first.json()["metadata"]["confirmation"]["id"],
        },
    )
    assert accepted.json()["guide_command"]["action_type"] == "drag"
    assert accepted.json()["task_status"] == "IN_PROGRESS"

    unchanged = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "slider-3",
            "session_id": "slider-session",
            "expected_version": accepted.json()["session_version"],
            "accessibility_tree": slider_tree(3),
        },
    )
    assert unchanged.json()["task_status"] != "COMPLETED"

    completed = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "slider-4",
            "session_id": "slider-session",
            "expected_version": unchanged.json()["session_version"],
            "accessibility_tree": {**slider_tree(4), "captured_at": "2026-08-30T10:00:01Z"},
            "user_action_event": {
                "action_type": "drag_completed",
                "guide_command_id": accepted.json()["guide_command"]["guide_command_id"],
                "target_node_id": "font-slider",
                "target_bounds": [160, 430, 920, 480],
            },
        },
    )
    assert completed.status_code == 200
    assert completed.json()["task_status"] == "COMPLETED"
    assert completed.json()["metadata"]["verification"]["slider_value_increased"] is True
    assert completed.json()["metadata"]["runtime"] == "ai_agent.runtime"
    assert completed.json()["metadata"]["trace_count"] >= 1


def test_vision_snapshot_can_be_sent_without_accessibility_tree() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "vision-only-1",
            "session_id": "vision-only-session",
            "raw_goal": "increase font size",
            "vision_snapshot": {
                "user_consented": True,
                "redaction_applied": True,
                "provider": "frontend_ocr",
                "package_name": "com.android.settings",
                "screen_width": 1080,
                "screen_height": 2400,
                "elements": [
                    {
                        "element_id": "title",
                        "text": "Settings",
                        "bounds_in_screen": [24, 40, 1056, 96],
                        "actionable": False,
                    },
                    {
                        "element_id": "display",
                        "text": "Display",
                        "bounds_in_screen": [24, 320, 1056, 408],
                    },
                ],
            },
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["state_id"] == "S1_SETTINGS_HOME"
    assert body["guide_command"]["target_node_id"] == "vision:display"
    assert body["metadata"]["debug"]["vision_fallback"]["status"] == "used"


@pytest.mark.parametrize("action_type", ["scroll_completed", "scroll_failed"])
def test_scroll_action_events_are_accepted(action_type: str) -> None:
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": f"{action_type}-request",
            "session_id": f"{action_type}-session",
            "raw_goal": "increase font size",
            "accessibility_tree": tree(),
            "user_action_event": {"action_type": action_type},
        },
    )

    assert response.status_code == 200


def test_empty_accessibility_tree_returns_runtime_fallback_contract() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "empty-tree-request",
            "session_id": "empty-tree-session",
            "raw_goal": "increase font size",
            "accessibility_tree": {
                "screen_width": 1080,
                "screen_height": 2400,
                "nodes": [],
            },
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["metadata"]["debug"]["observation"]["accessibility_node_count"] == 0
    assert body["metadata"]["debug"]["fallback"]["type"] == "vision_fallback"
    assert body["guide_command"]["render_policy"] == {
        "runtime_strategy": "reobserve", "attempt_id": None, "step_id": None,
        "avoid_covering_target": False,
        "preferred_position": "center",
        "vision_fallback": {
            "requires_user_consent": False,
            "accepted_payloads": [
                "vision_snapshot.elements",
                "vision_snapshot.ocr_text",
                "vision_snapshot.screenshot_base64",
            ],
            "send_fresh_accessibility_tree": True,
        },
    }


def test_screen_observation_is_still_required() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/agent/turn",
        json={
            "request_id": "missing-observation-request",
            "session_id": "missing-observation-session",
            "raw_goal": "increase font size",
        },
    )

    assert response.status_code == 422
