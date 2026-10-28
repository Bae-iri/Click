from dataclasses import replace

import pytest

from ai_agent.runtime.guide import build_guide_command
from ai_agent.runtime.tasks.mvp_01_font_size_increase import MVP_01_TASK_GRAPH
from ai_agent.runtime.tasks.mvp_02_font_size_decrease import MVP_02_TASK_GRAPH
from ai_agent.runtime.types import ActionType, MatchResult


def guide(graph, *, accepted=True):
    transition = next(t for t in graph.transitions if t.action_type == ActionType.DRAG)
    match = MatchResult(state_id=transition.from_state, next_state_id=transition.to_state, action_type=ActionType.DRAG,
        target_node_id="slider", target_label="글자 크기", target_bounds=(20, 300, 900, 400),
        match_method="selector", confidence=1.0, transition_id=transition.transition_id)
    return build_guide_command(session_id="direction", graph=graph, match_result=match,
        user_confirmation="accepted" if accepted else None)


@pytest.mark.parametrize("graph,direction", [(MVP_01_TASK_GRAPH, "right"), (MVP_02_TASK_GRAPH, "left")])
def test_approved_font_drag_has_explicit_direction_without_parsing_guide_text(graph, direction):
    graph = replace(graph, transitions=tuple(replace(t, guide_message="Move the control")
        if t.action_type == ActionType.DRAG else t for t in graph.transitions))
    assert guide(graph).render_policy["direction"] == direction
    assert guide(graph).render_policy["automatable_setting"] == "font_size"


def test_confirmation_and_unknown_drag_do_not_assume_a_direction():
    assert "direction" not in guide(MVP_01_TASK_GRAPH, accepted=False).render_policy
    assert "automatable_setting" not in guide(MVP_01_TASK_GRAPH, accepted=False).render_policy
    unknown = replace(MVP_01_TASK_GRAPH, transitions=tuple(
        replace(t, verification_predicate="state_reached") if t.action_type == ActionType.DRAG else t
        for t in MVP_01_TASK_GRAPH.transitions))
    assert "direction" not in guide(unknown).render_policy
    assert "automatable_setting" not in guide(unknown).render_policy
