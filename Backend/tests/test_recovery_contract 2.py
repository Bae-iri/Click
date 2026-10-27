"""Recoverable navigation survives adapter, privacy filtering and checkpoint restore."""
from dataclasses import replace

from ai_agent.runtime.types import CommandType, SafetyLevel
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.clients.checkpoint import capture, restore, safe_response
from app.schemas.agent_turn import AgentTurnRequest
from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_agent_sessions import payload


def offscreen(index=1):
    body = payload(f"recovery-{index}", "recovering-session")
    tree = body["accessibility_tree"]
    title = dict(tree["nodes"][0], node_id="title", text="설정", clickable=False,
                 bounds_in_screen=[24, 40, 1056, 100], class_name="android.widget.TextView")
    scroll = dict(title, node_id="list", text=None, scrollable=True,
                  bounds_in_screen=[0, 200, 1080, 2200], class_name="androidx.recyclerview.widget.RecyclerView")
    tree.update(nodes=[title, scroll], observation_id=f"o{index}", captured_at_ms=index * 1000)
    return body


def test_recovery_roundtrip_preserves_reason_choices_and_episode_state():
    adapter = RuntimeAgentAdapter()
    request = AgentTurnRequest.model_validate(offscreen())
    first = safe_response(adapter.handle_turn(request))
    assert first.task_status == "IN_PROGRESS"
    recovery = first.guide_command.render_policy["recovery"]
    assert recovery["reason_code"] == "TARGET_NOT_VISIBLE"
    assert recovery["waiting_for"] == "user"
    assert recovery["options"] == ["retry", "manual_navigation", "cancel"]
    checkpoint = capture(adapter.runtime.session_store, request.session_id)
    assert checkpoint["typed_goal"]["source_text"] == ""
    restored = RuntimeAgentAdapter(session_store=restore(checkpoint))
    follow = offscreen(2); follow.pop("raw_goal")
    second = safe_response(restored.handle_turn(AgentTurnRequest.model_validate(follow)))
    assert second.task_status == "IN_PROGRESS"
    assert second.guide_command.render_policy["recovery"] == recovery
    assert restored.runtime.session_store.get(request.session_id).total_failed_attempts == 0
    assert "result_prompt" not in second.metadata


def test_legacy_graph_handoff_is_not_replaced_with_result_confirmation(monkeypatch):
    adapter = RuntimeAgentAdapter()
    request = AgentTurnRequest.model_validate(offscreen())
    original = adapter.runtime.handle_turn
    def blocked(request):
        result = original(request)
        policy = dict(result.guide_command.render_policy)
        policy["backend_handoff"] = {"type": "task_graph_reselection_required", "reason_code": "GRAPH_PATH_UNAVAILABLE"}
        return replace(result, guide_command=replace(result.guide_command,
            command_type=CommandType.BLOCKED, safety_level=SafetyLevel.BLOCKED, render_policy=policy))
    monkeypatch.setattr(adapter.runtime, "handle_turn", blocked)
    response = safe_response(adapter.handle_turn(request))
    assert response.task_status == "IN_PROGRESS"
    assert response.guide_command.command_type == "highlight"
    assert response.guide_command.safety_level == "LOW_RISK"
    assert response.guide_command.render_policy["backend_handoff"]["reason_code"] == "GRAPH_PATH_UNAVAILABLE"


def test_real_safety_block_stays_blocked_instead_of_asking_for_satisfaction(monkeypatch):
    adapter = RuntimeAgentAdapter()
    original = adapter.runtime.handle_turn
    def blocked(request):
        result = original(request)
        return replace(result, guide_command=replace(result.guide_command,
            command_type=CommandType.BLOCKED, safety_level=SafetyLevel.BLOCKED, render_policy={}))
    monkeypatch.setattr(adapter.runtime, "handle_turn", blocked)
    response = adapter.handle_turn(AgentTurnRequest.model_validate(offscreen()))
    assert response.task_status == "BLOCKED"
    assert response.guide_command.command_type == "blocked"


def test_http_recovery_is_cached_and_followup_continues_same_session():
    client = authenticated_client(create_app())
    first = client.post("/api/v1/agent/turn", json=offscreen())
    assert first.status_code == 200, first.text
    body = first.json()
    assert body["task_status"] == "IN_PROGRESS"
    assert body["guide_command"]["render_policy"]["recovery"]["next_action"] == "manual_navigation"
    assert client.post("/api/v1/agent/turn", json=offscreen()).json() == body
    follow = payload("recovery-visible", "recovering-session")
    follow.pop("raw_goal")
    follow["expected_version"] = body["session_version"]
    follow["accessibility_tree"].update(observation_id="visible", captured_at_ms=3000)
    result = client.post("/api/v1/agent/turn", json=follow)
    assert result.status_code == 200, result.text
    assert result.json()["task_status"] == "IN_PROGRESS"
    assert result.json()["guide_command"]["target_node_id"] is not None


def test_typed_commands_keep_goal_episode_and_resume_after_manual_navigation():
    from tests.test_task_outcomes import Flow
    flow = Flow()
    ready = flow.prepare()
    first = flow.send("start_task", preparation_version="goal-inputs.v1",
        goal_resolution_id=ready["preparation"]["id"], goal=ready["goal"], source_text=flow.source_text,
        device_context={"platform": "android", "locale": "ko-KR"},
        accessibility_tree=offscreen()["accessibility_tree"])
    assert first["task_status"] == "IN_PROGRESS"
    assert first["guide_command"]["render_policy"]["recovery"]["next_action"] == "manual_navigation"
    assert not first["metadata"].get("result_prompt")
    tree = payload()["accessibility_tree"]
    tree.update(observation_id="typed-visible", captured_at_ms=3000)
    second = flow.send("observe", accessibility_tree=tree)
    assert second["task_status"] == "IN_PROGRESS"
    assert second["guide_command"]["target_node_id"] is not None
    assert second["episode_id"] == first["episode_id"]
    assert second["accepted_goal_type"] == first["accepted_goal_type"]
