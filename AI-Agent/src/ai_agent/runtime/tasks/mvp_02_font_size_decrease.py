"""MVP-02: Android Settings font-size decrease task graph."""
from dataclasses import replace

from ai_agent.runtime.types import TaskGraph
from .mvp_01_font_size_increase import MVP_01_STATES, MVP_01_SYNONYMS, MVP_01_TRANSITIONS, MVP_01_TASK_DEFINITION


TASK_ID = "MVP-02"
GOAL_TYPE = "settings.font_size.decrease"

MVP_02_TASK_DEFINITION = replace(
    MVP_01_TASK_DEFINITION,
    task_id=TASK_ID,
    name="Android 설정 앱에서 글자 크기 축소",
    capability=replace(MVP_01_TASK_DEFINITION.capability, operations=("decrease",)),
    required_slots={"setting": "font_size", "direction": "decrease"},
)

MVP_02_TRANSITIONS = tuple(
    replace(
        transition,
        transition_id="S4_TO_S5_DECREASE_FONT_SIZE",
        action_name="축소 방향 조작 안내",
        verification_predicate="target_value_decreased",
        guide_message="글자를 작게 하려면 슬라이더를 왼쪽으로 움직여주세요.",
        confirmation_message="글자 크기 설정이 바뀔 수 있어요. 계속 작게 조정할까요?",
    ) if transition.transition_id == "S4_TO_S5_INCREASE_FONT_SIZE" else
    replace(
        transition,
        guide_message="글자 크기가 더 작게 변경되었어요.",
    ) if transition.transition_id == "S5_TO_DONE_VERIFY_CHANGE" else transition
    for transition in MVP_01_TRANSITIONS
)

MVP_02_SYNONYMS = {
    **MVP_01_SYNONYMS,
    "decrease": ("작게", "축소", "줄이기", "왼쪽", "작은 글자"),
}

MVP_02_TASK_GRAPH = TaskGraph(
    definition=MVP_02_TASK_DEFINITION,
    states=MVP_01_STATES,
    transitions=MVP_02_TRANSITIONS,
    synonyms=MVP_02_SYNONYMS,
)
