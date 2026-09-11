from fastapi.testclient import TestClient

from app.main import create_app


def create_user(client: TestClient) -> str:
    from tests.helpers import register
    return register(client)["user_id"]


def test_user_profile_lifecycle_and_deletion() -> None:
    client = TestClient(create_app())
    user_id = create_user(client)

    updated = client.patch(
        f"/api/v1/users/{user_id}/profile",
        json={"explanation_depth": "detailed", "prefers_step_by_step": True},
    )
    assert updated.status_code == 200
    assert updated.json()["explanation_depth"] == "detailed"

    deleted = client.delete(f"/api/v1/users/{user_id}")
    assert deleted.status_code == 204
    assert client.get(f"/api/v1/users/{user_id}").status_code == 401


def test_ui_events_require_consent_and_update_profile() -> None:
    client = TestClient(create_app())
    user_id = create_user(client)
    event_url = f"/api/v1/users/{user_id}/events"
    confused = {"event_type": "user_confused", "metadata": {}}

    assert client.post(event_url, json=confused).status_code == 403
    consent = client.post(
        f"/api/v1/users/{user_id}/consents",
        json={
            "purpose": "personalization",
            "consented": True,
            "policy_version": "2026-08-30",
        },
    )
    assert consent.status_code == 201

    for _ in range(3):
        response = client.post(event_url, json=confused)
        assert response.status_code == 201
    assert response.json()["profile"]["comprehension_level"] == "beginner"
    assert response.json()["profile"]["confidence_score"] == -6


def test_profile_personalizes_agent_message_only_after_consent() -> None:
    client = TestClient(create_app())
    user_id = create_user(client)
    client.patch(
        f"/api/v1/users/{user_id}/profile",
        json={"comprehension_level": "beginner"},
    )
    client.post(
        f"/api/v1/users/{user_id}/consents",
        json={"purpose": "personalization", "consented": True, "policy_version": "v1"},
    )
    payload = {
        "request_id": "personalized-1",
        "session_id": "personalized-session",
        "user_id": user_id,
        "raw_goal": "increase font size",
        "accessibility_tree": {
            "package_name": "com.android.settings",
            "screen_width": 1080,
            "screen_height": 2400,
            "nodes": [{"node_id": "display", "text": "Display", "clickable": True}],
        },
    }
    response = client.post("/api/v1/agent/turn", json=payload)
    assert response.status_code == 200
    assert response.json()["guide_command"]["message"].startswith("천천히 한 단계씩")
    assert response.json()["metadata"]["ui_profile"]["comprehension_level"] == "beginner"

    different_user = create_user(client)
    replay = client.post(
        "/api/v1/agent/turn",
        json={**payload, "user_id": different_user},
    )
    assert replay.status_code == 403
