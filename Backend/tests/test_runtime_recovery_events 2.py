import json
import time
import pytest
from sqlalchemy import select, func

from app.main import create_app
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.models.agent_session import AgentCheckpoint, AgentSession
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.runtime_events import cleanup
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


class Handoff(RuntimeAgentAdapter):
    failures = 0
    def handle_turn(self, request):
        response = super().handle_turn(request)
        if request.command_type == "observe":
            self.runtime.session_store.update(request.session_id, total_failed_attempts=self.failures,
                failed_target_keys={"S1_TO_S2_DISPLAY_MENU": ("failed-target",)}, processed_event_keys=("old-event",))
            response.task_status = "BLOCKED"
            guide = response.guide_command
            guide.command_type = "blocked"
            guide.action_type = "observe_only"
            guide.safety_level = "BLOCKED"
            guide.target_node_id = guide.target_bounds = None
            guide.requires_user_action = False
            guide.render_policy = {"backend_handoff": {"type": "task_graph_reselection_required",
                "reason": "observation_recovery_exhausted"}}
        return response


def follow(body):
    request = start_payload()
    return dict(contract_version=request["contract_version"], command_type="observe",
        request_id="observe-" + str(body["session_version"]), session_id=body["session_id"],
        episode_id=body["episode_id"], expected_version=body["session_version"],
        accessibility_tree=request["accessibility_tree"])


def setup(alternatives=1):
    app = create_app()
    service = app.state.turn_service
    app.state.turn_service.engine_factory = Handoff
    for index in range(alternatives):
        from app.repositories.task_graphs import TASK_GRAPHS
        graph = json.loads(json.dumps(TASK_GRAPHS["settings.font_size.increase"].to_dict()))
        graph["definition"]["name"] += f" alternative {index}"
        service.graphs.register(graph, version=f"2.{index}.0", approved=True, priority=50-index)
    client = authenticated_client(app)
    payload = start_payload()
    payload["accessibility_tree"]["nodes"].extend([
        {"node_id": "title", "text": "설정", "bounds_in_screen": [20, 0, 900, 80]},
        {"node_id": "notifications", "text": "알림", "clickable": True, "bounds_in_screen": [20, 220, 900, 320]}])
    started = client.post("/api/v1/agent/commands", json=payload)
    assert started.status_code == 200, started.text
    return app, client, started.json()


def test_reselection_is_atomic_preserves_exclusions_and_fences_old_episode():
    app, client, started = setup()
    from app.models.lifecycle import SafetyConfirmation
    approval_id = "00000000-0000-4000-8000-000000000001"
    with app.state.session_factory() as db:
        db.add(SafetyConfirmation(session_id=started["session_id"], id=approval_id,
            version=started["session_version"], expires_at=int(time.time()) + 120,
            screen_hash="0" * 64, command={}))
        db.commit()
    request = follow(started)
    response = client.post("/api/v1/agent/commands", json=request)
    assert response.status_code == 200, response.text
    changed = response.json()
    assert changed["task_status"] == "IN_PROGRESS"
    assert changed["episode_id"] != started["episode_id"]
    assert changed["graph_reference"]["sha256"] != started["graph_reference"]["sha256"]
    assert changed["metadata"]["reselection"]["status"] == "restarted"
    assert client.post("/api/v1/agent/commands", json=request).json() == changed
    with app.state.session_factory() as db:
        checkpoint = db.get(AgentCheckpoint, changed["session_id"]).state
        assert checkpoint["pending_attempt"] is None
        assert checkpoint["failed_target_keys"]["S1_TO_S2_DISPLAY_MENU"] == ["failed-target"]
        assert checkpoint["processed_event_keys"] == ["old-event"]
        assert db.get(SafetyConfirmation, changed["session_id"]) is None
        events = list(db.scalars(select(RuntimeEvent).order_by(RuntimeEvent.sequence)))
        assert [item.sequence for item in events] == [1, 2]
        assert events[1].episode_id == started["episode_id"]
        assert events[1].graph_hash == started["graph_reference"]["sha256"]
    late = {**follow(changed), "episode_id": started["episode_id"]}
    assert client.post("/api/v1/agent/commands", json=late).status_code == 409
    late_approval = {**follow(changed), "command_type": "respond_confirmation",
        "request_id": "late-approval", "user_confirmation": "accepted", "confirmation_id": approval_id}
    assert client.post("/api/v1/agent/commands", json=late_approval).status_code == 409


@pytest.mark.parametrize("alternatives,reason", [(0, "no_compatible_alternative"), (3, "reselection_budget_exhausted")])
def test_reselection_stops_instead_of_looping(alternatives, reason):
    app, client, body = setup(alternatives)
    for _ in range(4):
        response = client.post("/api/v1/agent/commands", json=follow(body))
        assert response.status_code == 200, response.text
        body = response.json()
        if body["metadata"].get("reselection", {}).get("status") == "stopped":
            break
    assert body["task_status"] == "IN_PROGRESS"
    assert body["guide_command"]["render_policy"]["recovery"]["waiting_for"] == "user"
    assert body["metadata"]["reselection"]["reason_code"] == reason
    assert "backend_handoff" in body["guide_command"]["render_policy"]


def test_failure_budget_survives_graph_replacement():
    class FailedHandoff(Handoff):
        failures = 6
    app, client, started = setup(3)
    app.state.turn_service.engine_factory = FailedHandoff
    first = client.post("/api/v1/agent/commands", json=follow(started)).json()
    second = client.post("/api/v1/agent/commands", json=follow(first)).json()
    assert second["task_status"] == "IN_PROGRESS"
    assert second["guide_command"]["render_policy"]["recovery"]["waiting_for"] == "user"
    assert second["metadata"]["reselection"]["reason_code"] == "failure_budget_exhausted"


def test_event_failure_rolls_back_response_and_checkpoint(monkeypatch):
    from app.repositories import runtime_events
    app, client, started = setup()
    def failed(*args, **kwargs):
        raise RuntimeError("event storage unavailable")
    monkeypatch.setattr(runtime_events, "append", failed)
    assert client.post("/api/v1/agent/commands", json=follow(started)).status_code == 500
    with app.state.session_factory() as db:
        row = db.get(AgentSession, started["session_id"])
        assert row.version == 1 and row.episode_id == started["episode_id"]
        assert db.scalar(select(func.count()).select_from(RuntimeEvent)) == 1


def test_verification_event_retains_numeric_evidence_and_offline_label():
    from tests.test_interactive_workflow import waiting_result
    from ai_agent.graph_proposal.ingestion.runtime_events import label_event
    app = create_app()
    client = authenticated_client(app)
    waiting_result(client)
    with app.state.session_factory() as db:
        event = db.scalar(select(RuntimeEvent).order_by(RuntimeEvent.sequence.desc()))
        runtime = event.payload["runtime"]
        assert list(runtime["before_values"].values()) == [3]
        assert list(runtime["after_values"].values()) == [4]
        assert runtime["reason_code"] == "target_value_increased"
        assert runtime["attempt_hash"] and runtime["before_observation_hash"] and runtime["after_observation_hash"]
        assert label_event(event.payload)["label"] == "verified_success"


def test_manual_navigation_and_waiting_are_not_training_success():
    from ai_agent.graph_proposal.ingestion.runtime_events import label_event
    from ai_agent.telemetry.execution_events import SCHEMA
    assert label_event({"runtime": {"schema_version": SCHEMA, "verification_status": "SUCCESS",
        "count_attempt": False}})["label"] == "observed_progress"
    assert label_event({"runtime": {"schema_version": SCHEMA,
        "verification_status": "WAITING_FOR_ACTION"}})["label"] == "waiting"


def test_events_are_private_and_expire_with_receipts():
    app, client, started = setup()
    with app.state.session_factory() as db:
        event = db.scalar(select(RuntimeEvent))
        serialized = json.dumps(event.payload, ensure_ascii=False)
        assert "디스플레이" not in serialized
        assert "source_text" not in serialized and "ocr_text" not in serialized
        assert event.payload["runtime"]["target"]["node_hash"]
        expiry = event.expires_at
    assert cleanup(app.state.session_factory, now=expiry) == 1


def test_offline_consumer_is_idempotent_and_respects_consent():
    from scripts.consume_runtime_events import consume
    app, client, started = setup()
    assert consume(app.state.session_factory) == 1
    assert consume(app.state.session_factory) == 0
    with app.state.session_factory() as db:
        assert db.scalar(select(RuntimeEventConsumption)).result == {"label": "not_eligible"}
    user = client.get("/api/v1/users/me").json()["id"]
    grant = client.post(f"/api/v1/users/{user}/consents", json={"purpose": "model_training", "consented": True,
                                                            "policy_version": "1"})
    assert grant.status_code == 201, grant.text
    app.state.turn_service.repository.clock = lambda: int(time.time()) + 2
    assert client.post("/api/v1/agent/commands", json=follow(started)).status_code == 200
    assert consume(app.state.session_factory) == 1
    with app.state.session_factory() as db:
        results = [row.result for row in db.scalars(select(RuntimeEventConsumption))]
        assert any(row.get("policy_version") == "runtime-event-label.v1" for row in results)
    client.post(f"/api/v1/users/{user}/consents", json={"purpose": "model_training", "consented": False, "policy_version": "1"})
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEventConsumption)) == 0
    assert client.delete(f"/api/v1/users/{user}").status_code == 204
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEvent)) == 0
