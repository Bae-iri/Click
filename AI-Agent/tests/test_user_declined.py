from unittest.mock import Mock
from ai_agent.runtime.types import CommandType, AgentTurnRequest
from test_runtime_reliability import Turns
from test_engine_flow import _font_size_control_tree
from ai_agent.runtime.termination import user_declined


def test_refusal_needs_no_fresh_screen_or_model_and_does_not_spend_failure_budget():
    turns=Turns(); turns.send(_font_size_control_tree(),at=1000)
    before=turns.session
    turns.engine.screen_resolver=Mock(side_effect=AssertionError("No model for a refusal"))
    response=turns.engine.handle_turn(AgentTurnRequest(request_id="decline",session_id="s",raw_goal=None,
        accessibility_tree=(),observation_id="o1",captured_at_ms=500,user_confirmation="rejected"))
    assert response.guide_command.command_type==CommandType.CANCELLED
    assert response.guide_command.target_node_id is None
    after=turns.session
    assert after.total_failed_attempts==before.total_failed_attempts
    assert after.transition_attempts==before.transition_attempts
    assert after.pending_attempt is None and after.pending_confirmation_transition_id is None
    assert after.pending_selection is None
    turns.engine.screen_resolver.assert_not_called()


def test_declining_an_additional_adjustment_does_not_claim_previous_changes_never_happened():
    assert user_declined("brightness",previously_changed=True)["message"]=="추가로 조절하지 않고 안내를 마칠게요."


def test_technical_capture_error_is_not_mistaken_for_consent_denial():
    from types import SimpleNamespace
    from ai_agent.runtime.vision_cycles import decide_vision_cycle
    from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH as graph
    step=graph.transitions[0].transition_id
    request=SimpleNamespace(episode_id="episode",session_id="s",request_id="capture-failed",
        user_confirmation="rejected",vision_failure="capture_failed",accessibility_tree=())
    session=SimpleNamespace(vision_state={"scope":["episode",graph.definition.task_id,step],
        "step_id":step,"status":"retrying","failed_cycles":0})
    match=SimpleNamespace(transition_id=step,state_id="S0_UNKNOWN",target_node_id=None,fallback_reason="no_target")
    cycle=decide_vision_cycle(request=request,session=session,graph=graph,match=match,analysis=None)
    assert cycle.state["status"]=="retrying" and cycle.state["failed_cycles"]==1
    assert cycle.command.command_type==CommandType.CONFIRMATION
    assert "termination" not in cycle.command.render_policy
