from ai_agent.runtime.tasks import MVP_02_TASK_GRAPH, TASK_GRAPHS
from ai_agent.runtime.types import SafetyLevel


def test_decrease_graph_is_registered_with_directional_verification():
    assert TASK_GRAPHS["MVP-02"] is MVP_02_TASK_GRAPH
    assert TASK_GRAPHS["settings.font_size.decrease"] is MVP_02_TASK_GRAPH
    assert MVP_02_TASK_GRAPH.definition.required_slots == {
        "setting": "font_size", "direction": "decrease"}
    transition = next(item for item in MVP_02_TASK_GRAPH.transitions
        if item.transition_id == "S4_TO_S5_DECREASE_FONT_SIZE")
    assert transition.verification_predicate == "target_value_decreased"
    assert transition.safety_level == SafetyLevel.CONFIRM_REQUIRED
    assert "왼쪽" in transition.guide_message
