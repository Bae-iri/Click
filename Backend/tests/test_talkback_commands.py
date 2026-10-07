"""TalkBack backend contracts; execution belongs to Agent 1."""
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
    ("settings.talkback.enable", {"setting": "talkback", "state": "on"}),
    ("settings.talkback.disable", {"setting": "talkback", "state": "off"}),
]


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
    # Synthetic graph only; no production biometric or app management graph or runtime changes.
    graph = replace(MVP_01_TASK_GRAPH, definition=replace(MVP_01_TASK_GRAPH.definition,
        task_id="TEST-TALKBACK", goal_type=goal_type, required_slots=slots))
    monkeypatch.setattr(task_graphs, "TASK_GRAPHS", {
        graph.definition.task_id: graph, graph.definition.goal_type: graph})
    app = create_app()
    repository = app.state.turn_service.graphs
    repository.seed_mvp()
    request = AgentCommandRequest.model_validate(payload_for(goal_type, slots))
    selected, reference = repository.select(request.goal)
    assert selected == graph
    assert reference["task_id"] == "TEST-TALKBACK"
    with repository.factory() as db:
        rows = list(db.scalars(select(ApprovedTaskGraph).where(
            ApprovedTaskGraph.task_id == "TEST-TALKBACK")))
        assert len(rows) == 1
        assert rows[0].graph_hash == reference["sha256"]
    adapter = RuntimeAgentAdapter(task_graph=selected)
    adapter.runtime.handle_turn = Mock(side_effect=RuntimeError("Agent 1 boundary"))
    with pytest.raises(RuntimeError, match="Agent 1 boundary"):
        adapter.handle_turn(request)
    forwarded = adapter.runtime.handle_turn.call_args.args[0].typed_goal
    assert forwarded.task_id == "TEST-TALKBACK"
    assert forwarded.goal_type == request.goal.goal_type
    assert forwarded.slots == slots


@pytest.mark.parametrize("goal_type,slots", CASES)
@pytest.mark.parametrize("invalid_kind", ["missing_setting", "missing_state", "wrong_setting", "wrong_state", "password", "pin", "biometric_data", "package_name", "permission"])
def test_invalid_slots_rejected_at_http_boundary(goal_type, slots, invalid_kind):
    app = create_app()
    client = authenticated_client(app)
    payload = payload_for(goal_type, slots)
    invalid = dict(slots)
    if invalid_kind.startswith("missing_"):
        del invalid[invalid_kind.removeprefix("missing_")]
    elif invalid_kind == "wrong_setting":
        invalid["setting"] = "wifi"
    elif invalid_kind == "wrong_state":
        invalid["state"] = "enabled"
    else:
        invalid[invalid_kind] = "not-allowed"
    payload["goal"]["slots"] = invalid
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
