from fastapi.testclient import TestClient
from sqlalchemy import select

from app.main import create_app
from app.models.personalization import UIEvent


def test_state_difficulties_recovery_isolation_and_revocation():
    from ai_agent.user_modeling import guidance_context
    client = TestClient(create_app())
    user_id = create_user(client)
    base = f"/api/v1/users/{user_id}"
    client.post(base + "/consents", json={"purpose": "personalization",
        "consented": True, "policy_version": "v1"})
    for _ in range(2):
        result = client.post(base + "/events", json={"event_type": "guide_repeated",
            "screen_name": "S1_SETTINGS_HOME"})
        assert result.status_code == 201
    evidence = result.json()["profile"]["state_difficulties"]
    assert guidance_context(evidence, "S1_SETTINGS_HOME")["needs_extra_guidance"]
    assert not guidance_context(evidence, "S2_DISPLAY_SETTINGS")["needs_extra_guidance"]
    payload = {"request_id": "difficulty-1", "session_id": "difficulty-session",
        "user_id": user_id, "raw_goal": "increase font size",
        "accessibility_tree": {"package_name": "com.android.settings",
            "screen_width": 1080, "screen_height": 2400,
            "nodes": [{"node_id": "title", "text": "Settings", "package_name": "com.android.settings"},
                {"node_id": "display", "text": "Display", "clickable": True,
                 "package_name": "com.android.settings", "class_name": "android.widget.TextView",
                 "bounds_in_screen": [24, 320, 1056, 408]}]}}
    guided = client.post("/api/v1/agent/turn", json=payload)
    assert guided.status_code == 200
    assert guided.json()["metadata"]["ui_profile"]["current_state_difficulty"]["needs_extra_guidance"]
    assert "이름과 위치" in guided.json()["guide_command"]["message"]
    recovered = client.post(base + "/events", json={"event_type": "guide_step_completed",
        "screen_name": "S1_SETTINGS_HOME"}).json()["profile"]["state_difficulties"]
    assert recovered["S1_SETTINGS_HOME"]["completion_count"] == 1
    assert not guidance_context(recovered, "S1_SETTINGS_HOME")["needs_extra_guidance"]
    client.post(base + "/consents", json={"purpose": "personalization",
        "consented": False, "policy_version": "v1"})
    assert client.get(base).json()["profile"]["state_difficulties"] == {}
    retry = client.post("/api/v1/agent/turn", json=payload)
    assert retry.status_code == 200
    assert "ui_profile" not in retry.json()["metadata"]
    other = create_user(client)
    assert client.get(f"/api/v1/users/{other}").json()["profile"]["state_difficulties"] == {}


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


def test_guide_tap_duration_requires_consent_and_does_not_score_comprehension() -> None:
    app = create_app()
    client = TestClient(app)
    user_id = create_user(client)
    event_url = f"/api/v1/users/{user_id}/events"
    event = {"event_type": "guide_action_observed", "screen_name": "S1_SETTINGS_HOME",
             "metadata": {"step": 1, "duration_ms": 4200}}
    assert client.post(event_url, json=event).status_code == 403
    assert client.post(f"/api/v1/users/{user_id}/consents", json={
        "purpose": "personalization", "consented": True, "policy_version": "v1"}).status_code == 201
    result = client.post(event_url, json=event)
    assert result.status_code == 201
    assert result.json()["profile"]["confidence_score"] == 0
    assert result.json()["profile"]["comprehension_level"] == "unknown"
    assert result.json()["profile"]["evidence_count"] == 0
    with app.state.session_factory() as db:
        saved = db.scalars(select(UIEvent).where(UIEvent.user_id == user_id)).one()
        assert saved.screen_name == "S1_SETTINGS_HOME"
        assert saved.event_metadata == {"step": 1, "duration_ms": 4200}
    assert client.post(event_url, json={**event, "metadata": {"duration_ms": 86_400_001}}).status_code == 422
    for _ in range(2):
        assert client.post(event_url, json=event).json()["profile"]["comprehension_level"] == "unknown"
    assert client.post(f"/api/v1/users/{user_id}/consents", json={
        "purpose": "personalization", "consented": False, "policy_version": "v1"}).status_code == 201
    with app.state.session_factory() as db:
        assert db.scalars(select(UIEvent).where(UIEvent.user_id == user_id)).all() == []


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


def test_navigation_context_never_counts_as_comprehension_difficulty():
    app=create_app();client=TestClient(app);user_id=create_user(client)
    base=f"/api/v1/users/{user_id}"
    client.post(base+'/consents',json={'purpose':'personalization','consented':True,'policy_version':'v1'})
    for action in ['back','home','home']:
        result=client.post(base+'/events',json={'event_type':'navigation_recovery','screen_name':'S1_SETTINGS_HOME',
            'metadata':{'navigation_action':action,'duration_ms':120000}})
        assert result.status_code==201
        profile=result.json()['profile']
        assert profile['confidence_score']==0
        assert profile['evidence_count']==0
        assert profile['comprehension_level']=='unknown'
        assert not profile['state_difficulties']
