from copy import deepcopy
from uuid import uuid4
import pytest

from fastapi.testclient import TestClient

from app.main import create_app
from app.models.agent_session import AgentSession
from tests.helpers import register
from tests.test_typed_agent_contract import start_payload
from tests.test_agent_turn_api import slider_tree


VERSION = "agent-fe-contract.v0.3"


def resolution(client, text="글자크기 키우고싶어"):
    response = client.post("/api/v1/voice-requests/enqueue", json={"id": str(uuid4()), "raw_goal": text})
    assert response.status_code == 200, response.text
    claimed = client.post("/api/v1/voice-requests/next/claim").json()["running"]
    request = dict(contract_version=VERSION, command_type="resolve_goal", request_id=str(uuid4()),
        session_id=claimed["agent_session_id"], expected_version=0, source_text=text,
        device_context={"platform": "android", "locale": "ko-KR"})
    return request, client.post("/api/v1/agent/commands", json=request)


def test_resolution_does_not_execute_then_confirmed_goal_starts():
    app = create_app()
    with TestClient(app) as client:
        register(client)
        request, response = resolution(client)
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["resolution_status"] == "confirmation_required"
        assert body["goal"]["goal_type"] == "settings.font_size.increase"
        assert client.post("/api/v1/agent/commands", json=request).json() == body
        with app.state.session_factory() as db:
            row = db.get(AgentSession, request["session_id"])
            assert row.version == 0 and row.episode_id is None
        start = start_payload()
        start.update(contract_version=VERSION, session_id=request["session_id"], goal=body["goal"])
        response = client.post("/api/v1/agent/commands", json=start)
        assert response.status_code == 200, response.text
        assert response.json()["contract_version"] == VERSION
        assert response.json()["guide_command"]


def test_resolution_negation_and_unapproved_goal_do_not_start():
    for text, expected in [("글자 크기 키우지 마", "needs_clarification"), ("와이파이 켜줘", "unsupported")]:
        with TestClient(create_app()) as client:
            register(client)
            _, response = resolution(client, text)
            assert response.status_code == 200, response.text
            assert response.json()["resolution_status"] == expected
            assert response.json()["goal"] is None


def test_adjustment_restarts_same_setting_with_new_episode_and_retries():
    with TestClient(create_app()) as client:
        register(client)
        payload = start_payload()
        initial = client.post("/api/v1/agent/commands", json=payload).json()
        payload.update(contract_version=VERSION, command_type="adjust_task", request_id="adjust-1",
            expected_version=initial["session_version"], episode_id=initial["episode_id"])
        payload["goal"]["catalog_version"] = "2.1.0"
        payload["goal"]["goal_type"] = "settings.font_size.decrease"
        payload["goal"]["slots"]["direction"] = "decrease"
        response = client.post("/api/v1/agent/commands", json=payload)
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["episode_id"] != initial["episode_id"]
        assert body["task_id"] == "MVP-02"
        assert body["command_result"]["command_type"] == "adjust_task"
        assert client.post("/api/v1/agent/commands", json=payload).json() == body
        reused = deepcopy(payload)
        reused["command_type"] = "replace_task"
        assert client.post("/api/v1/agent/commands", json=reused).status_code == 409


@pytest.mark.parametrize("outcome", ["accepted", "rejected"])
def test_result_acknowledgement_requires_completed_episode_and_is_persisted(outcome):
    app = create_app()
    with TestClient(app) as client:
        register(client)
        start = start_payload()
        start["accessibility_tree"] = slider_tree(3)
        started = client.post("/api/v1/agent/commands", json=start).json()
        request = dict(contract_version=VERSION, command_type="respond_result", request_id="result-1",
            session_id=started["session_id"], expected_version=started["session_version"],
            episode_id=started["episode_id"], result_response=outcome)
        assert client.post("/api/v1/agent/commands", json=request).status_code == 409
        confirm = dict(contract_version=VERSION, command_type="respond_confirmation", request_id="confirm-1",
            session_id=started["session_id"], expected_version=started["session_version"],
            episode_id=started["episode_id"], user_confirmation="accepted",
            confirmation_id=started["metadata"]["confirmation"]["id"], accessibility_tree=slider_tree(3))
        confirmed = client.post("/api/v1/agent/commands", json=confirm)
        assert confirmed.status_code == 200, confirmed.text
        observe = dict(contract_version=VERSION, command_type="observe", request_id="complete-1",
            session_id=started["session_id"], expected_version=confirmed.json()["session_version"],
            episode_id=started["episode_id"], accessibility_tree={**slider_tree(4), "captured_at": "2026-10-09T10:00:01Z"},
            user_action_event={"event_id": "drag-1", "action_type": "drag_completed",
                "guide_command_id": confirmed.json()["guide_command"]["guide_command_id"], "target_node_id": "font-slider"})
        completed = client.post("/api/v1/agent/commands", json=observe)
        assert completed.status_code == 200, completed.text
        assert completed.json()["task_status"] == "COMPLETED"
        request["expected_version"] = completed.json()["session_version"]
        result = client.post("/api/v1/agent/commands", json=request)
        assert result.status_code == 200, result.text
        assert result.json()["session_version"] == completed.json()["session_version"] + 1
        assert client.post("/api/v1/agent/commands", json=request).json() == result.json()
        with app.state.session_factory() as db:
            row = db.get(AgentSession, started["session_id"])
            assert row.result_response == outcome and row.status == "COMPLETED"
        assert client.get("/api/v1/agent/sessions/" + started["session_id"]).json()["result_response"] == outcome
        request["result_response"] = "rejected" if outcome == "accepted" else "accepted"
        assert client.post("/api/v1/agent/commands", json=request).status_code == 409
        register(client)
        assert client.post("/api/v1/agent/commands", json=request).status_code == 403


def test_backend_catalog_matches_shared_fe_catalog():
    import json
    from pathlib import Path
    root = Path(__file__).parents[2]
    assert json.loads((root / "Backend/app/task_graphs/goal_catalog.json").read_text(encoding="utf-8")) == json.loads(
        (root / "AI-Agent/goal_catalog/mvp_goals.json").read_text(encoding="utf-8"))


def test_new_commands_require_new_contract_and_command_specific_fields():
    with TestClient(create_app()) as client:
        register(client)
        request, _ = resolution(client)
        request["contract_version"] = "agent-fe-contract.v0.2"
        assert client.post("/api/v1/agent/commands", json=request).status_code == 422
        request.update(contract_version=VERSION, result_response="accepted")
        assert client.post("/api/v1/agent/commands", json=request).status_code == 422
