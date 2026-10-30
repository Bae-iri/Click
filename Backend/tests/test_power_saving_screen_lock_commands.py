"""Power saving and screen lock backend contracts; execution belongs to Agent 1."""
from dataclasses import replace
from unittest.mock import Mock

import pytest
from sqlalchemy import select

from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.main import create_app
from app.models.task_graph import ApprovedTaskGraph
from app.repositories import task_graphs
from app.schemas.agent_turn import AgentCommandRequest, TypedGoalRequest
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


CASES = [
    ("settings.screen_lock.change", {"setting": "screen_lock", "action": "change"}),
    ("settings.power_saving.enable", {"setting": "power_saving", "state": "on"}),
    ("settings.power_saving.disable", {"setting": "power_saving", "state": "off"}),
]

INVALID_SLOTS = {
    "action": [{"action": "mute"}, {"setting": "wifi", "action": "change"},
               {}, {"action": "change", "password": "secret"}, {"action": "change", "pin": "1234"}],
    "state": [{"state": "off"}, {"setting": "wifi", "state": "on"},
              {}, {"state": "on", "password": "secret"}, {"state": "on", "ssid": "example"}],
}


def payload_for(goal_type, slots, version="2.1.0"):
    payload = start_payload()
    payload["goal"].update(catalog_version=version, goal_type=goal_type, slots=slots)
    return payload


@pytest.mark.parametrize("goal_type,slots", CASES)
@pytest.mark.parametrize("version", ["2.0.0", "2.1.0"])
def test_valid_settings_request_cannot_execute_without_graph(goal_type, slots, version):
    app = create_app()
    client = authenticated_client(app)
    payload = payload_for(goal_type, slots, version)
    goal = AgentCommandRequest.model_validate(payload).goal
    assert goal.slots == slots
    with pytest.raises(task_graphs.NoCompatibleGraphError, match="NO_COMPATIBLE_GRAPH"):
        app.state.turn_service.graphs.select(goal)
    app.state.turn_service.engine_factory = Mock(side_effect=AssertionError("Do not invoke Agent 1"))
    response = client.post("/api/v1/agent/commands", json=payload)
    assert response.status_code == 422, response.text
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    app.state.turn_service.engine_factory.assert_not_called()


@pytest.mark.parametrize("goal_type,slots", CASES)
def test_registered_graph_is_deduplicated_selected_and_forwarded(monkeypatch, goal_type, slots):
    # Synthetic graph only; no production power saving or screen lock graph or runtime changes.
    graph = replace(MVP_01_TASK_GRAPH, definition=replace(MVP_01_TASK_GRAPH.definition,
        task_id="TEST-POWER-LOCK-SETTING", goal_type=goal_type, required_slots=slots))
    monkeypatch.setattr(task_graphs, "releases", lambda: [
        ({"version": "1.0.0", "approved": True, "priority": 100}, graph)])
    app = create_app()
    repository = app.state.turn_service.graphs
    repository.seed_mvp()
    request = AgentCommandRequest.model_validate(payload_for(goal_type, slots))
    selected, reference = repository.select(request.goal)
    assert selected == graph
    assert reference["task_id"] == "TEST-POWER-LOCK-SETTING"
    with repository.factory() as db:
        rows = list(db.scalars(select(ApprovedTaskGraph).where(
            ApprovedTaskGraph.task_id == "TEST-POWER-LOCK-SETTING")))
        assert len(rows) == 1
        assert rows[0].graph_hash == reference["sha256"]
    adapter = RuntimeAgentAdapter(task_graph=selected)
    adapter.runtime.handle_turn = Mock(side_effect=RuntimeError("Agent 1 boundary"))
    with pytest.raises(RuntimeError, match="Agent 1 boundary"):
        adapter.handle_turn(request)
    forwarded = adapter.runtime.handle_turn.call_args.args[0].typed_goal
    assert forwarded.task_id == "TEST-POWER-LOCK-SETTING"
    assert forwarded.goal_type == request.goal.goal_type
    assert forwarded.slots == slots


@pytest.mark.parametrize("setting,key,goal_suffix,valid_value", [
    ("screen_lock", "action", "change", "change"),
    ("power_saving", "state", "enable", "on"),
])
@pytest.mark.parametrize("invalid_index", range(5))
def test_invalid_slots_rejected_at_http_boundary(setting, key, goal_suffix, valid_value, invalid_index):
    app = create_app()
    client = authenticated_client(app)
    goal_type = "settings." + setting + "." + goal_suffix
    payload = payload_for(goal_type, {"setting": setting, key: valid_value})
    payload["goal"]["slots"] = {"setting": setting, **INVALID_SLOTS[key][invalid_index]}
    app.state.turn_service.handle_command = Mock(side_effect=AssertionError("Invalid input reached service"))
    assert client.post("/api/v1/agent/commands", json=payload).status_code == 422
    app.state.turn_service.handle_command.assert_not_called()


@pytest.mark.parametrize("goal_type,slots", CASES)
@pytest.mark.parametrize("version", ["1.0.0", "1.1.0"])
def test_older_catalog_versions_rejected(goal_type, slots, version):
    with pytest.raises(ValueError, match="2.0.0 or newer"):
        TypedGoalRequest.model_validate(payload_for(goal_type, slots, version)["goal"])


@pytest.mark.parametrize("goal_type,slots", CASES)
def test_contract_matches_shared_catalog(goal_type, slots):
    import json
    from pathlib import Path

    catalog = json.loads((Path(__file__).parents[2] / "AI-Agent" / "goal_catalog" / "mvp_goals.json").read_text(encoding="utf-8"))
    entry = next(item for item in catalog["goals"] if item["goal_type"] == goal_type)
    request = AgentCommandRequest.model_validate(payload_for(goal_type, slots, catalog["version"]))
    assert request.goal.slots == entry["slots"]
    assert request.goal.target_app == entry["target_app"]
    assert entry["execution_status"] == "catalog_only"
