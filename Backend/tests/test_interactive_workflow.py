import pytest

from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload
from tests.test_agent_turn_api import slider_tree
from tests.test_task_outcomes import screen


def command(previous, kind, **fields):
    return dict(contract_version="agent-fe-contract.v0.3", command_type=kind,
                request_id=kind + "-" + str(previous["session_version"]),
                session_id=previous["session_id"], expected_version=previous["session_version"],
                episode_id=previous["episode_id"], **fields)


def waiting_result(client, *, queued=False):
    start = start_payload()
    start.update(contract_version="agent-fe-contract.v0.3", workflow_version="interactive.v1",
                 accessibility_tree=screen(3, 1000, 1.0))
    if queued:
        response = client.post("/api/v1/voice-requests/enqueue", json={"id": "00000000-0000-4000-8000-000000000001", "raw_goal": "increase font size"})
        assert response.status_code == 200, response.text
        running = client.post("/api/v1/voice-requests/next/claim").json()["running"]
        start["session_id"] = running["agent_session_id"]
    response = client.post("/api/v1/agent/commands", json=start)
    assert response.status_code == 200, response.text
    started = response.json()
    confirm = command(started, "respond_confirmation", user_confirmation="accepted",
                      confirmation_id=started["metadata"]["confirmation"]["id"],
                      accessibility_tree=screen(3, 1100, 1.0))
    response = client.post("/api/v1/agent/commands", json=confirm)
    assert response.status_code == 200, response.text
    confirmed = response.json()
    observe = command(confirmed, "observe", accessibility_tree=screen(4, 1800, 1.15),
                      user_action_event={"session_id": confirmed["session_id"], "episode_id": confirmed["episode_id"], "event_id": "drag-1", "action_type": "drag_completed", "occurred_at_ms": 1200,
                          "guide_command_id": confirmed["guide_command"]["guide_command_id"], "target_node_id": "font-slider"})
    response = client.post("/api/v1/agent/commands", json=observe)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "WAITING_RESULT"
    assert client.post("/api/v1/agent/commands", json=observe).json() == response.json()
    return response.json()


@pytest.mark.parametrize("outcome,status", [("satisfied", "COMPLETED"), ("needs_help", "WAITING_ADJUSTMENT"),
                                          ("stopped", "CANCELLED")])
def test_result_prompt_fencing_restore_and_idempotency(outcome, status):
    client = authenticated_client(create_app())
    previous = waiting_result(client)
    prompt = previous["metadata"]["result_prompt"]
    state = client.get("/api/v1/agent/sessions/" + previous["session_id"]).json()
    assert state["result_prompt"] == prompt
    request = command(previous, "respond_result", user_result=outcome, result_prompt_id=prompt["id"])
    wrong = {**request, "result_prompt_id": "wrong"}
    assert client.post("/api/v1/agent/commands", json=wrong).status_code == 409
    wrong = {**request, "episode_id": "old"}
    assert client.post("/api/v1/agent/commands", json=wrong).status_code == 409
    response = client.post("/api/v1/agent/commands", json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == status
    assert client.post("/api/v1/agent/commands", json=request).json() == response.json()
    duplicate = {**request, "request_id": "duplicate"}
    assert client.post("/api/v1/agent/commands", json=duplicate).status_code == 409


def test_adjustment_restarts_without_reusing_previous_approval_or_guide():
    client = authenticated_client(create_app())
    previous = waiting_result(client)
    request = command(previous, "respond_result", user_result="needs_help",
                      result_prompt_id=previous["metadata"]["result_prompt"]["id"])
    response = client.post("/api/v1/agent/commands", json=request).json()
    state = client.get("/api/v1/agent/sessions/" + previous["session_id"]).json()
    assert state["follow_up"] == response["metadata"]["follow_up"]
    adjust = command(response, "adjust_task", adjustment_reason="too_large")
    adjusted = client.post("/api/v1/agent/commands", json=adjust)
    assert adjusted.status_code == 200, adjusted.text
    changed = adjusted.json()
    assert changed["episode_id"] != previous["episode_id"]
    assert changed["task_status"] == "IN_PROGRESS"
    assert changed["accepted_goal_type"] == "settings.font_size.decrease"
    assert client.post("/api/v1/agent/commands", json=adjust).json() == changed
    observe = command(changed, "observe", accessibility_tree=slider_tree(4))
    restarted = client.post("/api/v1/agent/commands", json=observe)
    assert restarted.status_code == 200, restarted.text
    assert restarted.json()["task_id"] == "MVP-02"
    assert restarted.json()["guide_command"]["command_type"] == "confirmation"
    old = {**observe, "request_id": "late", "episode_id": previous["episode_id"]}
    assert client.post("/api/v1/agent/commands", json=old).status_code == 409


def test_timeout_does_not_record_satisfaction():
    app = create_app()
    client = authenticated_client(app)
    previous = waiting_result(client)
    request = command(previous, "respond_result", user_result="no_response",
                      result_prompt_id=previous["metadata"]["result_prompt"]["id"])
    assert client.post("/api/v1/agent/commands", json=request).status_code == 409
    app.state.turn_service.repository.clock = lambda: previous["metadata"]["result_prompt"]["expires_at"] + 1
    late = {**request, "user_result": "satisfied"}
    assert client.post("/api/v1/agent/commands", json=late).status_code == 409
    # Expiry is now recorded atomically when the late answer is rejected.
    response = client.post("/api/v1/agent/commands", json=request)
    assert response.status_code == 409, response.text
    state = client.get("/api/v1/agent/sessions/" + previous["session_id"]).json()
    assert state["status"] == "EXPIRED"
    assert state["task_outcome"]["user"]["status"] == "no_response"


def test_pause_resume_restores_result_question_instead_of_runtime():
    app = create_app()
    client = authenticated_client(app)
    repository = app.state.turn_service.repository
    clock = [int(repository.clock())]
    repository.clock = lambda: clock[0]
    previous = waiting_result(client)
    clock[0] += 10
    paused = client.post("/api/v1/agent/commands", json=command(previous, "pause_task")).json()
    assert paused["task_status"] == "PAUSED"
    clock[0] += 20
    resumed = client.post("/api/v1/agent/commands", json=command(paused, "resume_task", accessibility_tree=slider_tree(4)))
    assert resumed.status_code == 200, resumed.text
    assert resumed.json()["task_status"] == "WAITING_RESULT"
    # Resume preserves the same question and renews its existing 180-second answer window.
    # Comparing the old timestamp only passed when every request landed in one wall-clock second.
    expected = {**previous["metadata"]["result_prompt"], "expires_at": clock[0] + 180}
    assert resumed.json()["metadata"]["result_prompt"] == expected
    assert expected["expires_at"] > previous["metadata"]["result_prompt"]["expires_at"]


def test_queue_keeps_result_question_running():
    client = authenticated_client(create_app())
    previous = waiting_result(client, queued=True)
    running = client.post("/api/v1/voice-requests/next/claim").json()["running"]
    assert running["agent_session_id"] == previous["session_id"]
    assert client.post("/api/v1/voice-requests/00000000-0000-4000-8000-000000000001/complete").status_code == 409


def test_result_prompt_saved_before_outcome_migration_remains_answerable():
    from app.models.agent_session import AgentSession
    app = create_app()
    client = authenticated_client(app)
    previous = waiting_result(client)
    with app.state.session_factory() as db:
        row = db.get(AgentSession, previous["session_id"])
        row.task_outcome = None
        db.commit()
    request = command(previous, "respond_result", user_result="satisfied",
                      result_prompt_id=previous["metadata"]["result_prompt"]["id"])
    response = client.post("/api/v1/agent/commands", json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "COMPLETED"
    assert client.post("/api/v1/agent/commands", json=request).json() == response.json()


def test_blocked_handoff_is_not_converted_to_result_question():
    from app.clients.runtime_agent import RuntimeAgentAdapter
    app = create_app()
    class BlockedAdapter(RuntimeAgentAdapter):
        def handle_turn(self, request):
            response = super().handle_turn(request)
            response.task_status = "BLOCKED"
            response.guide_command.command_type = "blocked"
            response.guide_command.action_type = "observe_only"
            response.guide_command.safety_level = "BLOCKED"
            response.guide_command.requires_user_action = False
            response.guide_command.target_node_id = None
            response.guide_command.target_bounds = None
            response.guide_command.render_policy = {"backend_handoff": {"type": "task_graph_reselection_required"}}
            return response
    app.state.turn_service.engine_factory = BlockedAdapter
    client = authenticated_client(app)
    start = start_payload()
    start.update(contract_version="agent-fe-contract.v0.3", workflow_version="interactive.v1")
    response = client.post("/api/v1/agent/commands", json=start)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "IN_PROGRESS"
    assert response.json()["guide_command"]["render_policy"]["recovery"]["waiting_for"] == "user"
    assert "result_prompt" not in response.json()["metadata"]
    assert response.json()["guide_command"]["render_policy"]["backend_handoff"]
