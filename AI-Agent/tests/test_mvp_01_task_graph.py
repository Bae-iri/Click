import unittest

from click_agent.tasks import MVP_01_TASK_GRAPH, TASK_GRAPHS
from click_agent.types import ActionType, SafetyLevel


class Mvp01TaskGraphTests(unittest.TestCase):
    def test_registry_exposes_task_by_task_id_and_goal_type(self) -> None:
        self.assertIs(TASK_GRAPHS["MVP-01"], MVP_01_TASK_GRAPH)
        self.assertIs(
            TASK_GRAPHS["settings.font_size.increase"],
            MVP_01_TASK_GRAPH,
        )

    def test_task_definition_matches_font_size_increase_goal(self) -> None:
        definition = MVP_01_TASK_GRAPH.definition

        self.assertEqual(definition.task_id, "MVP-01")
        self.assertEqual(definition.goal_type, "settings.font_size.increase")
        self.assertEqual(definition.target_app, "android_settings")
        self.assertEqual(
            definition.required_slots,
            {"setting": "font_size", "direction": "increase"},
        )

    def test_states_include_documented_flow(self) -> None:
        self.assertEqual(
            set(MVP_01_TASK_GRAPH.states),
            {
                "S0_UNKNOWN",
                "S1_SETTINGS_HOME",
                "S2_DISPLAY_MENU",
                "S3_FONT_MENU",
                "S4_FONT_SIZE_CONTROL",
                "S5_FONT_SIZE_CHANGED",
                "S_DONE",
            },
        )

    def test_transitions_from_settings_home_prefer_display_then_accessibility(self) -> None:
        transitions = MVP_01_TASK_GRAPH.transitions_from("S1_SETTINGS_HOME")

        self.assertEqual(
            [transition.transition_id for transition in transitions],
            ["S1_TO_S2_DISPLAY_MENU", "S1_TO_S3_ACCESSIBILITY_FALLBACK"],
        )
        self.assertEqual(transitions[0].target_keywords[0], "디스플레이")
        self.assertEqual(transitions[1].target_keywords[0], "접근성")

    def test_font_size_change_requires_confirmation(self) -> None:
        transition = next(
            transition
            for transition in MVP_01_TASK_GRAPH.transitions
            if transition.transition_id == "S4_TO_S5_INCREASE_FONT_SIZE"
        )

        self.assertEqual(transition.action_type, ActionType.DRAG)
        self.assertEqual(transition.safety_level, SafetyLevel.CONFIRM_REQUIRED)
        self.assertIsNotNone(transition.confirmation_message)

    def test_font_states_use_or_keyword_groups(self) -> None:
        font_menu = MVP_01_TASK_GRAPH.state("S3_FONT_MENU")
        font_control = MVP_01_TASK_GRAPH.state("S4_FONT_SIZE_CONTROL")

        self.assertIn(("글자", "글꼴", "폰트", "Font", "text"), font_menu.required_keyword_groups)
        self.assertIn(
            ("글자 크기", "글꼴 크기", "폰트 크기", "Font size", "text size"),
            font_control.required_keyword_groups,
        )

    def test_scroll_is_disabled_in_current_implementation_scope(self) -> None:
        self.assertTrue(
            all(not transition.allow_scroll for transition in MVP_01_TASK_GRAPH.transitions)
        )


if __name__ == "__main__":
    unittest.main()
