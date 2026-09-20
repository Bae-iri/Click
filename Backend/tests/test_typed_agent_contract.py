from tests.helpers import authenticated_client
from app.main import create_app


def tree():
    return {"package_name": "com.android.settings", "screen_width": 1080,
        "screen_height": 2400, "nodes": [{"node_id": "display", "text": "디스플레이",
        "package_name": "com.android.settings", "clickable": True,
        "bounds_in_screen": [20, 100, 900, 200]}]}


def start_payload():
    return {"contract_version": "agent-fe-contract.v0.2", "request_id": "typed-start",
        "session_id": "typed-session", "expected_version": 0, "command_type": "start_task",
        "goal": {"catalog_version": "1.0.0", "goal_type": "settings.font_size.increase",
            "target_app": "android_settings", "slots": {"setting": "font_size", "direction": "increase"}},
        "device_context": {"platform": "android", "locale": "ko-KR",
            "foreground_package": "com.android.settings"}, "accessibility_tree": tree()}


def test_typed_start_uses_goal_without_raw_goal():
    client = authenticated_client(create_app())
    response = client.post("/api/v1/agent/commands", json=start_payload())
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["accepted_goal_type"] == "settings.font_size.increase"
    assert body["task_id"] == "MVP-01"
    assert body["episode_id"]
    assert body["graph_reference"]["sha256"]


def test_typed_font_size_decrease_selects_mvp_02():
    client = authenticated_client(create_app())
    payload = start_payload()
    payload.update(request_id="decrease-start", session_id="decrease-session")
    payload["goal"]["goal_type"] = "settings.font_size.decrease"
    payload["goal"]["slots"]["direction"] = "decrease"
    payload["goal"]["catalog_version"] = "1.1.0"
    response = client.post("/api/v1/agent/commands", json=payload)
    assert response.status_code == 200, response.text
    assert response.json()["task_id"] == "MVP-02"
    assert response.json()["accepted_goal_type"] == "settings.font_size.decrease"


def test_slot_conflict_is_rejected_not_corrected():
    client = authenticated_client(create_app())
    payload = start_payload()
    payload["goal"]["slots"]["direction"] = "decrease"
    response = client.post("/api/v1/agent/commands", json=payload)
    assert response.status_code == 422


def test_pause_without_screen_and_episode_fencing():
    client = authenticated_client(create_app())
    started = client.post("/api/v1/agent/commands", json=start_payload()).json()
    pause = {"contract_version": "agent-fe-contract.v0.2", "request_id": "pause-1",
        "session_id": "typed-session", "expected_version": started["session_version"],
        "episode_id": started["episode_id"], "command_type": "pause_task"}
    response = client.post("/api/v1/agent/commands", json=pause)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "PAUSED"
    assert client.post("/api/v1/agent/commands", json=pause).json() == response.json()
    wrong = {**pause, "request_id": "pause-wrong", "expected_version": response.json()["session_version"],
        "episode_id": "old-episode"}
    rejected = client.post("/api/v1/agent/commands", json=wrong)
    assert rejected.status_code == 409
    assert rejected.headers["X-Conflict-Code"] == "EPISODE_MISMATCH"


def test_start_retry_does_not_create_second_episode():
    client = authenticated_client(create_app())
    payload = start_payload()
    first = client.post("/api/v1/agent/commands", json=payload)
    second = client.post("/api/v1/agent/commands", json=payload)
    assert second.status_code == 200, second.text
    assert second.json() == first.json()


def test_observe_cannot_include_goal():
    client = authenticated_client(create_app())
    payload = start_payload()
    payload.update(command_type="observe", episode_id="episode", request_id="observe")
    response = client.post("/api/v1/agent/commands", json=payload)
    assert response.status_code == 422


def test_replace_is_idempotent_and_fences_old_episode():
    client = authenticated_client(create_app())
    started = client.post("/api/v1/agent/commands", json=start_payload()).json()
    replace = start_payload()
    replace.update(request_id="replace-1", command_type="replace_task",
        expected_version=started["session_version"], episode_id=started["episode_id"])
    first = client.post("/api/v1/agent/commands", json=replace)
    assert first.status_code == 200, first.text
    assert first.json()["episode_id"] != started["episode_id"]
    retry = client.post("/api/v1/agent/commands", json=replace)
    assert retry.status_code == 200, retry.text
    assert retry.json() == first.json()
    stale_cancel = {"contract_version": "agent-fe-contract.v0.2", "request_id": "stale-cancel",
        "session_id": "typed-session", "expected_version": first.json()["session_version"],
        "episode_id": started["episode_id"], "command_type": "cancel_task"}
    assert client.post("/api/v1/agent/commands", json=stale_cancel).status_code == 409
