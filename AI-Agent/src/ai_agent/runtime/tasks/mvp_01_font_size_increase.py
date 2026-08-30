"""MVP-01: Android Settings font-size increase task graph."""

from __future__ import annotations

from ai_agent.runtime.types import (
    ActionType,
    SafetyLevel,
    TaskDefinition,
    TaskGraph,
    TaskState,
    Transition,
)


TASK_ID = "MVP-01"
GOAL_TYPE = "settings.font_size.increase"


MVP_01_TASK_DEFINITION = TaskDefinition(
    task_id=TASK_ID,
    goal_type=GOAL_TYPE,
    name="Android 설정 앱에서 글자 크기 확대",
    target_app="android_settings",
    task_type="settings_change",
    start_states=("S0_UNKNOWN", "S1_SETTINGS_HOME"),
    done_state="S_DONE",
    required_slots={"setting": "font_size", "direction": "increase"},
)


MVP_01_STATES = {
    "S0_UNKNOWN": TaskState(
        state_id="S0_UNKNOWN",
        name="알 수 없는 화면",
        confidence_threshold=0.0,
    ),
    "S1_SETTINGS_HOME": TaskState(
        state_id="S1_SETTINGS_HOME",
        name="설정 홈",
        required_keywords=("설정",),
        optional_keywords=("디스플레이", "접근성", "연결", "알림", "배터리"),
        package_candidates=("com.android.settings",),
        role_hints=("menu_item", "list"),
        confidence_threshold=0.75,
    ),
    "S2_DISPLAY_MENU": TaskState(
        state_id="S2_DISPLAY_MENU",
        name="디스플레이 메뉴",
        required_keywords=("디스플레이",),
        optional_keywords=("밝기", "글자 크기", "화면 크기", "다크 모드"),
        package_candidates=("com.android.settings",),
        role_hints=("menu_item", "slider"),
        confidence_threshold=0.75,
    ),
    "S3_FONT_MENU": TaskState(
        state_id="S3_FONT_MENU",
        name="글자 관련 메뉴",
        required_keyword_groups=(("글자", "글꼴", "폰트", "Font", "text"),),
        optional_keywords=("스타일", "크기"),
        package_candidates=("com.android.settings",),
        role_hints=("menu_item",),
        confidence_threshold=0.75,
    ),
    "S4_FONT_SIZE_CONTROL": TaskState(
        state_id="S4_FONT_SIZE_CONTROL",
        name="글자 크기 조절 화면",
        required_keyword_groups=(("글자 크기", "글꼴 크기", "폰트 크기", "Font size", "text size"),),
        optional_keywords=("미리보기", "작게", "크게"),
        package_candidates=("com.android.settings",),
        role_hints=("slider", "radio", "menu_item"),
        confidence_threshold=0.78,
    ),
    "S5_FONT_SIZE_CHANGED": TaskState(
        state_id="S5_FONT_SIZE_CHANGED",
        name="글자 크기 변경됨",
        required_keyword_groups=(("글자 크기", "글꼴 크기", "폰트 크기", "Font size", "text size"),),
        optional_keywords=("크게", "미리보기"),
        package_candidates=("com.android.settings",),
        role_hints=("slider", "radio"),
        confidence_threshold=0.8,
    ),
    "S_DONE": TaskState(
        state_id="S_DONE",
        name="완료",
        confidence_threshold=1.0,
    ),
}


MVP_01_TRANSITIONS = (
    Transition(
        transition_id="S0_TO_S1_OPEN_SETTINGS",
        from_state="S0_UNKNOWN",
        to_state="S1_SETTINGS_HOME",
        action_type=ActionType.TAP,
        action_name="설정 앱 열기 안내",
        target_keywords=("설정", "Settings"),
        target_role="app_icon",
        safety_level=SafetyLevel.LOW_RISK,
        guide_message="설정 앱을 열어주세요.",
        allow_scroll=False,
    ),
    Transition(
        transition_id="S1_TO_S2_DISPLAY_MENU",
        from_state="S1_SETTINGS_HOME",
        to_state="S2_DISPLAY_MENU",
        action_type=ActionType.TAP,
        action_name="디스플레이 메뉴 진입",
        target_keywords=("디스플레이", "화면", "Display"),
        target_role="menu_item",
        safety_level=SafetyLevel.LOW_RISK,
        guide_message="디스플레이를 눌러주세요.",
        allow_scroll=False,
    ),
    Transition(
        transition_id="S1_TO_S3_ACCESSIBILITY_FALLBACK",
        from_state="S1_SETTINGS_HOME",
        to_state="S3_FONT_MENU",
        action_type=ActionType.TAP,
        action_name="접근성 대체 경로 진입",
        target_keywords=("접근성", "시인성 향상", "Accessibility", "visibility"),
        target_role="menu_item",
        safety_level=SafetyLevel.LOW_RISK,
        guide_message="접근성을 눌러주세요.",
        allow_scroll=False,
    ),
    Transition(
        transition_id="S2_TO_S3_FONT_MENU",
        from_state="S2_DISPLAY_MENU",
        to_state="S3_FONT_MENU",
        action_type=ActionType.TAP,
        action_name="글자 크기 메뉴 진입",
        target_keywords=("글자 크기와 스타일", "글꼴 크기", "Font size"),
        target_role="menu_item",
        safety_level=SafetyLevel.LOW_RISK,
        guide_message="글자 크기와 스타일을 눌러주세요.",
        allow_scroll=False,
    ),
    Transition(
        transition_id="S3_TO_S4_FONT_SIZE_CONTROL",
        from_state="S3_FONT_MENU",
        to_state="S4_FONT_SIZE_CONTROL",
        action_type=ActionType.TAP,
        action_name="조절 화면 진입",
        target_keywords=("글자 크기", "글꼴 크기", "Font size"),
        target_role="menu_item",
        safety_level=SafetyLevel.LOW_RISK,
        guide_message="글자 크기를 눌러주세요.",
        allow_scroll=False,
    ),
    Transition(
        transition_id="S4_TO_S5_INCREASE_FONT_SIZE",
        from_state="S4_FONT_SIZE_CONTROL",
        to_state="S5_FONT_SIZE_CHANGED",
        action_type=ActionType.DRAG,
        action_name="확대 방향 조작 안내",
        target_keywords=("슬라이더", "크게", "오른쪽", "큰 글자"),
        target_role="slider",
        safety_level=SafetyLevel.CONFIRM_REQUIRED,
        guide_message="글자를 크게 하려면 슬라이더를 오른쪽으로 움직여주세요.",
        allow_scroll=False,
        confirmation_message="글자 크기 설정이 바뀔 수 있어요. 계속 크게 조정할까요?",
    ),
    Transition(
        transition_id="S5_TO_DONE_VERIFY_CHANGE",
        from_state="S5_FONT_SIZE_CHANGED",
        to_state="S_DONE",
        action_type=ActionType.OBSERVE_ONLY,
        action_name="변경 확인",
        target_keywords=("글자 크기", "글꼴 크기", "미리보기", "Font size"),
        target_role=None,
        safety_level=SafetyLevel.LOW_RISK,
        guide_message="글자 크기가 더 크게 변경되었어요.",
        allow_scroll=False,
        requires_user_action=False,
    ),
)


MVP_01_SYNONYMS = {
    "display": ("디스플레이", "화면", "Display", "screen"),
    "accessibility": ("접근성", "시인성", "보기 편하게", "visibility"),
    "font_size": ("글자 크기", "글꼴 크기", "폰트 크기", "Font size", "text size"),
    "increase": ("크게", "확대", "키우기", "오른쪽", "큰 글자"),
}


MVP_01_TASK_GRAPH = TaskGraph(
    definition=MVP_01_TASK_DEFINITION,
    states=MVP_01_STATES,
    transitions=MVP_01_TRANSITIONS,
    synonyms=MVP_01_SYNONYMS,
)
