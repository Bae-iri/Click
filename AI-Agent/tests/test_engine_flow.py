import unittest

from ai_agent.runtime import (
    AccessibilityNode,
    ClickAgentEngine,
    CommandType,
    ContractViolationError,
    DeviceContext,
    RawGoalInput,
    SafetyLevel,
    UserActionEvent,
    UserActionType,
    VerificationStatus,
    VisionElement,
    VisionSnapshot,
)
from ai_agent.runtime.types import AgentTurnRequest


SETTINGS_PACKAGE = "com.android.settings"


class EngineFlowTests(unittest.TestCase):
    def test_first_turn_requires_raw_goal(self) -> None:
        engine = ClickAgentEngine()

        with self.assertRaises(ContractViolationError):
            engine.handle_turn(
                AgentTurnRequest(
                    request_id="req-1",
                    session_id="session-1",
                    raw_goal=None,
                    accessibility_tree=(),
                )
            )

    def test_first_turn_guides_display_menu_from_settings_home(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-1",
                session_id="session-1",
                raw_goal=_raw_goal("글자 크기 키우고 싶어"),
                accessibility_tree=_settings_home_tree(),
            )
        )

        self.assertEqual(response.current_state_id, "S1_SETTINGS_HOME")
        self.assertEqual(response.task_id, "MVP-01")
        self.assertEqual(response.typed_goal.goal_type, "settings.font_size.increase")
        self.assertEqual(response.guide_command.command_type, CommandType.HIGHLIGHT)
        self.assertEqual(response.guide_command.target_label, "디스플레이")
        self.assertEqual(response.guide_command.message, "디스플레이를 눌러주세요.")

    def test_follow_up_uses_session_and_verifies_expected_transition(self) -> None:
        engine = ClickAgentEngine()
        engine.handle_turn(
            AgentTurnRequest(
                request_id="req-1",
                session_id="session-1",
                raw_goal=_raw_goal("글자 크기 키우고 싶어"),
                accessibility_tree=_settings_home_tree(),
            )
        )

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-2",
                session_id="session-1",
                raw_goal=None,
                accessibility_tree=_display_menu_tree(),
            )
        )

        self.assertEqual(response.current_state_id, "S2_DISPLAY_MENU")
        self.assertEqual(response.verification_result.status, VerificationStatus.SUCCESS)
        self.assertEqual(response.guide_command.target_label, "글자 크기와 스타일")
        self.assertEqual(response.guide_command.message, "글자 크기와 스타일을 눌러주세요.")

    def test_font_size_control_requires_confirmation_before_drag_guide(self) -> None:
        engine = ClickAgentEngine()
        engine.handle_turn(
            AgentTurnRequest(
                request_id="req-1",
                session_id="session-1",
                raw_goal=_raw_goal("글자 크기 키우고 싶어"),
                accessibility_tree=_font_size_control_tree(),
            )
        )

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-2",
                session_id="session-1",
                raw_goal=None,
                accessibility_tree=_font_size_control_tree(),
            )
        )

        self.assertEqual(response.current_state_id, "S4_FONT_SIZE_CONTROL")
        self.assertEqual(response.guide_command.command_type, CommandType.CONFIRMATION)
        self.assertEqual(response.guide_command.safety_level, SafetyLevel.CONFIRM_REQUIRED)

        accepted_response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-3",
                session_id="session-1",
                raw_goal=None,
                accessibility_tree=_font_size_control_tree(),
                user_confirmation="accepted",
            )
        )

        self.assertEqual(accepted_response.guide_command.command_type, CommandType.HIGHLIGHT)
        self.assertEqual(
            accepted_response.guide_command.message,
            "글자를 크게 하려면 슬라이더를 오른쪽으로 움직여주세요.",
        )

    def test_font_synonyms_are_canonicalized_to_text_keyword(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-1",
                session_id="session-1",
                raw_goal=_raw_goal("폰트 크게 하고 싶어"),
                accessibility_tree=_font_menu_tree_with_font_word(),
            )
        )

        self.assertEqual(response.current_state_id, "S3_FONT_MENU")
        self.assertEqual(response.guide_command.target_label, "글자 크기")

    def test_casual_increase_goal_phrase_is_supported(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-casual-goal",
                session_id="session-casual-goal",
                raw_goal=_raw_goal("글자 크기 키워줘"),
                accessibility_tree=_settings_home_tree(),
            )
        )

        self.assertEqual(response.task_id, "MVP-01")
        self.assertEqual(response.typed_goal.slots["direction"], "increase")
        self.assertEqual(response.guide_command.target_label, "디스플레이")

    def test_unknown_state_does_not_target_settings_menu_inside_other_app(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-other-app-settings",
                session_id="session-other-app-settings",
                raw_goal=_raw_goal(
                    "글자 크기 키워줘",
                    device_context=DeviceContext(
                        platform="android",
                        app_package="com.some.otherapp",
                    ),
                ),
                accessibility_tree=(
                    AccessibilityNode(
                        node_id="other-app-settings",
                        package_name="com.some.otherapp",
                        class_name="android.widget.TextView",
                        text="설정",
                        bounds_in_screen=(24, 200, 400, 280),
                        clickable=True,
                        enabled=True,
                    ),
                ),
            )
        )

        self.assertEqual(response.current_state_id, "S0_UNKNOWN")
        self.assertIsNone(response.guide_command.target_node_id)
        self.assertEqual(response.guide_command.action_type.value, "observe_only")

    def test_unknown_state_can_target_settings_icon_on_launcher(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-launcher-settings",
                session_id="session-launcher-settings",
                raw_goal=_raw_goal(
                    "글자 크기 키워줘",
                    device_context=DeviceContext(
                        platform="android",
                        app_package="com.google.android.apps.nexuslauncher",
                    ),
                ),
                accessibility_tree=(
                    AccessibilityNode(
                        node_id="launcher-settings",
                        package_name="com.google.android.apps.nexuslauncher",
                        class_name="android.widget.TextView",
                        text="설정",
                        bounds_in_screen=(24, 200, 220, 360),
                        clickable=True,
                        enabled=True,
                    ),
                ),
            )
        )

        self.assertEqual(response.current_state_id, "S0_UNKNOWN")
        self.assertEqual(response.guide_command.target_node_id, "launcher-settings")
        self.assertEqual(response.guide_command.action_type.value, "tap")

    def test_replanner_excludes_failed_node_after_repeated_no_change(self) -> None:
        engine = ClickAgentEngine()
        first_response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-1",
                session_id="session-1",
                raw_goal=_raw_goal("글자 크기 키우고 싶어"),
                accessibility_tree=_settings_home_tree_with_two_display_candidates(),
            )
        )

        self.assertEqual(first_response.guide_command.target_node_id, "display-primary")

        engine.handle_turn(
            AgentTurnRequest(
                request_id="req-2",
                session_id="session-1",
                raw_goal=None,
                accessibility_tree=_settings_home_tree_with_two_display_candidates(),
                user_action_event=UserActionEvent(
                    action_type=UserActionType.TAP_COMPLETED,
                    guide_command_id=first_response.guide_command.guide_command_id,
                    target_node_id="display-primary",
                ),
            )
        )
        second_retry = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-3",
                session_id="session-1",
                raw_goal=None,
                accessibility_tree=_settings_home_tree_with_two_display_candidates(),
                user_action_event=UserActionEvent(
                    action_type=UserActionType.TAP_COMPLETED,
                    guide_command_id=first_response.guide_command.guide_command_id,
                    target_node_id="display-primary",
                ),
            )
        )

        self.assertEqual(second_retry.verification_result.status, VerificationStatus.NO_CHANGE)
        self.assertEqual(second_retry.guide_command.target_node_id, "display-secondary")
        self.assertIn("display-primary", second_retry.debug["failed_node_ids"])

    def test_slider_value_increase_flows_to_done_guide(self) -> None:
        engine = ClickAgentEngine()
        engine.handle_turn(
            AgentTurnRequest(
                request_id="req-1",
                session_id="session-1",
                raw_goal=_raw_goal("글자 크기 키우고 싶어"),
                accessibility_tree=_font_size_control_tree(slider_value=2),
            )
        )
        engine.handle_turn(
            AgentTurnRequest(
                request_id="req-2",
                session_id="session-1",
                raw_goal=None,
                accessibility_tree=_font_size_control_tree(slider_value=2),
                user_confirmation="accepted",
            )
        )

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-3",
                session_id="session-1",
                raw_goal=None,
                accessibility_tree=_font_size_control_tree(slider_value=3),
                user_action_event=UserActionEvent(
                    action_type=UserActionType.DRAG_COMPLETED,
                    target_node_id="font-slider",
                ),
            )
        )

        self.assertEqual(response.current_state_id, "S5_FONT_SIZE_CHANGED")
        self.assertEqual(response.verification_result.status, VerificationStatus.SUCCESS)
        self.assertEqual(response.guide_command.command_type, CommandType.DONE)

    def test_vision_snapshot_can_replace_empty_accessibility_tree(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-vision-1",
                session_id="session-vision",
                raw_goal=_raw_goal(
                    "글자 크기 키우고 싶어",
                    device_context=DeviceContext(
                        platform="android",
                        app_package=SETTINGS_PACKAGE,
                        screen_bounds=(0, 0, 1080, 2400),
                    ),
                ),
                accessibility_tree=(),
                vision_snapshot=VisionSnapshot(
                    user_consented=True,
                    redaction_applied=True,
                    provider="frontend_ocr",
                    package_name=SETTINGS_PACKAGE,
                    screen_width=1080,
                    screen_height=2400,
                    elements=(
                        VisionElement(
                            element_id="title",
                            text="설정",
                            bounds_in_screen=(24, 40, 1056, 96),
                            actionable=False,
                        ),
                        VisionElement(
                            element_id="display",
                            text="디스플레이",
                            bounds_in_screen=(24, 240, 1056, 320),
                        ),
                    ),
                ),
            )
        )

        self.assertEqual(response.current_state_id, "S1_SETTINGS_HOME")
        self.assertEqual(response.guide_command.target_node_id, "vision:display")
        self.assertEqual(response.guide_command.message, "디스플레이를 눌러주세요.")
        self.assertEqual(response.debug["vision_fallback"]["status"], "used")

    def test_vision_snapshot_augments_tree_when_accessibility_target_is_missing(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-vision-augment",
                session_id="session-vision-augment",
                raw_goal=_raw_goal(
                    "글자 크기 키워줘",
                    device_context=DeviceContext(
                        platform="android",
                        app_package=SETTINGS_PACKAGE,
                        screen_bounds=(0, 0, 1080, 2400),
                    ),
                ),
                accessibility_tree=(
                    _node("title", "설정", clickable=False, bounds=(24, 40, 1056, 96)),
                ),
                vision_snapshot=VisionSnapshot(
                    user_consented=True,
                    redaction_applied=True,
                    provider="frontend_ocr",
                    package_name=SETTINGS_PACKAGE,
                    elements=(
                        VisionElement(
                            element_id="display",
                            text="디스플레이",
                            bounds_in_screen=(24, 240, 1056, 320),
                        ),
                    ),
                ),
            )
        )

        self.assertEqual(response.current_state_id, "S1_SETTINGS_HOME")
        self.assertEqual(response.guide_command.target_node_id, "vision:display")
        self.assertEqual(response.debug["vision_fallback"]["status"], "used")

    def test_vision_snapshot_requires_consent_when_tree_is_empty(self) -> None:
        engine = ClickAgentEngine()

        response = engine.handle_turn(
            AgentTurnRequest(
                request_id="req-vision-consent",
                session_id="session-vision-consent",
                raw_goal=_raw_goal("글자 크기 키우고 싶어"),
                accessibility_tree=(),
                vision_snapshot=VisionSnapshot(
                    user_consented=False,
                    screenshot_base64="redacted",
                ),
            )
        )

        self.assertEqual(response.current_state_id, "S0_UNKNOWN")
        self.assertEqual(response.guide_command.command_type, CommandType.CONFIRMATION)
        self.assertIn("화면 이미지 분석", response.guide_command.message)
        self.assertEqual(response.debug["vision_fallback"]["status"], "consent_required")


def _raw_goal(
    source_text: str,
    *,
    device_context: DeviceContext | None = None,
) -> RawGoalInput:
    return RawGoalInput(
        source_text=source_text,
        locale="ko-KR",
        device_context=device_context or DeviceContext(platform="android"),
    )


def _node(
    node_id: str,
    text: str | None,
    *,
    class_name: str = "android.widget.TextView",
    clickable: bool = True,
    bounds: tuple[int, int, int, int] = (24, 100, 1056, 180),
) -> AccessibilityNode:
    return AccessibilityNode(
        node_id=node_id,
        package_name=SETTINGS_PACKAGE,
        class_name=class_name,
        text=text,
        bounds_in_screen=bounds,
        clickable=clickable,
        enabled=True,
    )


def _settings_home_tree() -> tuple[AccessibilityNode, ...]:
    return (
        _node("title", "설정", clickable=False, bounds=(24, 40, 1056, 96)),
        _node("display", "디스플레이", bounds=(24, 240, 1056, 320)),
        _node("accessibility", "접근성", bounds=(24, 340, 1056, 420)),
    )


def _display_menu_tree() -> tuple[AccessibilityNode, ...]:
    return (
        _node("title", "디스플레이", clickable=False, bounds=(24, 40, 1056, 96)),
        _node("brightness", "밝기", bounds=(24, 160, 1056, 240)),
        _node("font-style", "글자 크기와 스타일", bounds=(24, 260, 1056, 340)),
    )


def _font_menu_tree_with_font_word() -> tuple[AccessibilityNode, ...]:
    return (
        _node("title", "폰트", clickable=False, bounds=(24, 40, 1056, 96)),
        _node("font-size", "글꼴 크기", bounds=(24, 180, 1056, 260)),
    )


def _font_size_control_tree(slider_value: int | None = None) -> tuple[AccessibilityNode, ...]:
    return (
        _node("title", "글자 크기", clickable=False, bounds=(24, 40, 1056, 96)),
        _node("small", "작게", clickable=False, bounds=(24, 420, 160, 500)),
        _node("large", "크게", clickable=False, bounds=(920, 420, 1056, 500)),
        AccessibilityNode(
            node_id="font-slider",
            package_name=SETTINGS_PACKAGE,
            class_name="android.widget.SeekBar",
            text=None,
            bounds_in_screen=(160, 430, 920, 480),
            clickable=False,
            enabled=True,
            extras={"progress": slider_value} if slider_value is not None else {},
        ),
    )


def _settings_home_tree_with_two_display_candidates() -> tuple[AccessibilityNode, ...]:
    return (
        _node("title", "설정", clickable=False, bounds=(24, 40, 1056, 96)),
        _node("display-primary", "디스플레이", bounds=(24, 240, 1056, 320)),
        _node("display-secondary", "화면", bounds=(24, 340, 1056, 420)),
        _node("accessibility", "접근성", bounds=(24, 440, 1056, 520)),
    )


if __name__ == "__main__":
    unittest.main()
