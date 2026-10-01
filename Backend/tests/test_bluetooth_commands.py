"""Bluetooth backend contracts; execution belongs to Agent 1."""
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
    ("enable", {"setting": "bluetooth", "state": "on"}),
    ("disable", {"setting": "bluetooth", "state": "off"}),
    ("connect", {"setting": "bluetooth", "action": "connect"}),
]


def payload_for(intent, slots, version="2.1.0"):
    payload = start_payload()
    payload["goal"].update(catalog_version=version,
        goal_type="settings.bluetooth." + intent, slots=slots)
    return payload


@pytest.mark.parametrize("intent,slots", CASES)
@pytest.mark.parametrize("version", ["2.0.0", "2.1.0"])
def test_valid_bluetooth_request_cannot_execute_without_graph(intent, slots, version):
    app = create_app()
    client = authenticated_client(app)
    payload = payload_for(intent, slots, version)
    goal = AgentCommandRequest.model_validate(payload).goal
    assert goal.slots == slots
    with pytest.raises(task_graphs.NoCompatibleGraphError, match="NO_COMPATIBLE_GRAPH"):
        app.state.turn_service.graphs.select(goal)
    app.state.turn_service.engine_factory = Mock(side_effect=AssertionError("Do not invoke Agent 1"))
    response = client.post("/api/v1/agent/commands", json=payload)
    assert response.status_code == 422, response.text
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    app.state.turn_service.engine_factory.assert_not_called()


@pytest.mark.parametrize("intent,slots", CASES)
def test_registered_graph_is_deduplicated_selected_and_forwarded(monkeypatch, intent, slots):
    # Synthetic graph only; no production Bluetooth graph or runtime changes.
    graph = replace(MVP_01_TASK_GRAPH, definition=replace(MVP_01_TASK_GRAPH.definition,
        task_id="TEST-BLUETOOTH", goal_type="settings.bluetooth." + intent, required_slots=slots))
    monkeypatch.setattr(task_graphs, "TASK_GRAPHS", {
        graph.definition.task_id: graph, graph.definition.goal_type: graph})
    app = create_app()
    repository = app.state.turn_service.graphs
    repository.seed_mvp()
    request = AgentCommandRequest.model_validate(payload_for(intent, slots))
    selected, reference = repository.select(request.goal)
    assert selected == graph
    assert reference["task_id"] == "TEST-BLUETOOTH"
    with repository.factory() as db:
        rows = list(db.scalars(select(ApprovedTaskGraph).where(
            ApprovedTaskGraph.task_id == "TEST-BLUETOOTH")))
        assert len(rows) == 1
        assert rows[0].graph_hash == reference["sha256"]
    adapter = RuntimeAgentAdapter(task_graph=selected)
    adapter.runtime.handle_turn = Mock(side_effect=RuntimeError("Agent 1 boundary"))
    with pytest.raises(RuntimeError, match="Agent 1 boundary"):
        adapter.handle_turn(request)
    forwarded = adapter.runtime.handle_turn.call_args.args[0].typed_goal
    assert forwarded.task_id == "TEST-BLUETOOTH"
    assert forwarded.goal_type == request.goal.goal_type
    assert forwarded.slots == slots


@pytest.mark.parametrize("intent,slots", [
    ("enable", {"setting": "bluetooth", "state": "off"}),
    ("disable", {"setting": "bluetooth", "state": "on"}),
    ("connect", {"setting": "wifi", "action": "connect"}),
    ("connect", {"setting": "bluetooth"}),
    ("connect", {"setting": "bluetooth", "action": "connect", "pin": "1234"}),
    ("connect", {"setting": "bluetooth", "action": "connect", "device_address": "00:11:22:33:44:55"}),
])
def test_invalid_bluetooth_slots_rejected_at_http_boundary(intent, slots):
    app = create_app()
    client = authenticated_client(app)
    app.state.turn_service.handle_command = Mock(side_effect=AssertionError("Invalid input reached service"))
    assert client.post("/api/v1/agent/commands", json=payload_for(intent, slots)).status_code == 422
    app.state.turn_service.handle_command.assert_not_called()


@pytest.mark.parametrize("intent,slots", CASES)
@pytest.mark.parametrize("version", ["1.0.0", "1.1.0"])
def test_older_catalog_versions_rejected(intent, slots, version):
    with pytest.raises(ValueError, match="2.0.0 or newer"):
        TypedGoalRequest.model_validate(payload_for(intent, slots, version)["goal"])
