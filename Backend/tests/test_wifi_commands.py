"""Backend contract tests; Wi-Fi execution remains owned by Agent 1."""
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


WIFI_CASES = [
    ("enable", {"setting": "wifi", "state": "on"}),
    ("disable", {"setting": "wifi", "state": "off"}),
    ("connect", {"setting": "wifi", "action": "connect"}),
]


@pytest.mark.parametrize("intent,slots", WIFI_CASES)
def test_wifi_contract_is_valid_but_missing_execution_graph_is_rejected(intent, slots):
    app = create_app()
    client = authenticated_client(app)
    payload = start_payload()
    payload["goal"].update(catalog_version="2.1.0", goal_type="settings.wifi." + intent, slots=slots)
    goal = AgentCommandRequest.model_validate(payload).goal
    assert goal.slots == slots
    with pytest.raises(task_graphs.NoCompatibleGraphError, match="NO_COMPATIBLE_GRAPH"):
        app.state.turn_service.graphs.select(goal)
    app.state.turn_service.engine_factory = Mock(side_effect=AssertionError("Agent must not be invoked"))
    response = client.post("/api/v1/agent/commands", json=payload)
    assert response.status_code == 422, response.text
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    app.state.turn_service.engine_factory.assert_not_called()


def test_wifi_rejects_conflicting_slots_and_password():
    client = authenticated_client(create_app())
    payload = start_payload()
    payload["goal"].update(catalog_version="2.1.0", goal_type="settings.wifi.enable",
                           slots={"setting": "wifi", "state": "off"})
    assert client.post("/api/v1/agent/commands", json=payload).status_code == 422
    payload["goal"].update(goal_type="settings.wifi.connect",
                           slots={"setting": "wifi", "action": "connect", "password": "secret"})
    assert client.post("/api/v1/agent/commands", json=payload).status_code == 422


@pytest.mark.parametrize("intent,slots", WIFI_CASES)
def test_registry_graph_is_seeded_once_selected_and_forwarded_without_font_assumptions(monkeypatch, intent, slots):
    # Synthetic fixture only: does not implement real Wi-Fi execution.
    graph = replace(MVP_01_TASK_GRAPH, definition=replace(MVP_01_TASK_GRAPH.definition,
        task_id="TEST-WIFI", goal_type="settings.wifi." + intent, required_slots=slots))
    monkeypatch.setattr(task_graphs, "TASK_GRAPHS", {
        graph.definition.task_id: graph, graph.definition.goal_type: graph})
    app = create_app()
    repository = app.state.turn_service.graphs
    repository.seed_mvp()
    goal = TypedGoalRequest(catalog_version="2.1.0", goal_type=graph.definition.goal_type,
                           target_app="android_settings", slots=slots)
    selected, reference = repository.select(goal)
    assert selected == graph
    assert reference["task_id"] == "TEST-WIFI"
    with repository.factory() as db:
        rows = list(db.scalars(select(ApprovedTaskGraph).where(ApprovedTaskGraph.task_id == "TEST-WIFI")))
        assert len(rows) == 1

    adapter = RuntimeAgentAdapter(task_graph=selected)
    adapter.runtime.handle_turn = Mock(side_effect=RuntimeError("Stop at Agent 1 boundary"))
    payload = start_payload()
    payload["goal"] = goal.model_dump()
    with pytest.raises(RuntimeError, match="Stop at Agent 1 boundary"):
        adapter.handle_turn(AgentCommandRequest.model_validate(payload))
    forwarded = adapter.runtime.handle_turn.call_args.args[0].typed_goal
    assert forwarded.task_id == "TEST-WIFI"
    assert forwarded.goal_type == graph.definition.goal_type
    assert forwarded.slots == slots


@pytest.mark.parametrize("version", ["1.0.0", "1.1.0"])
def test_wifi_rejects_catalog_versions_without_wifi(version):
    with pytest.raises(ValueError, match="2.0.0 or newer"):
        TypedGoalRequest(catalog_version=version, goal_type="settings.wifi.enable",
                         target_app="android_settings", slots={"setting": "wifi", "state": "on"})
