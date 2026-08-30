import unittest

from click_agent.contracts import (
    ACCESSIBILITY_NODE_REQUIRED_FIELDS,
    CONTRACT_VERSION,
    GUIDE_COMMAND_ALLOWED_ACTIONS,
    contract_summary,
)


class ContractTests(unittest.TestCase):
    def test_contract_summary_exposes_version_and_required_fields(self) -> None:
        summary = contract_summary()

        self.assertEqual(summary["contract_version"], CONTRACT_VERSION)
        self.assertIn("request_id", summary["fe_to_agent_required_fields"])
        self.assertIn("guide_command", summary["agent_to_fe_required_fields"])
        self.assertIn("session_state_rules", summary)
        self.assertIn("user_action_event_types", summary)

    def test_accessibility_node_contract_has_android_core_fields(self) -> None:
        self.assertIn("bounds_in_screen", ACCESSIBILITY_NODE_REQUIRED_FIELDS)
        self.assertIn("clickable", ACCESSIBILITY_NODE_REQUIRED_FIELDS)
        self.assertIn("scrollable", ACCESSIBILITY_NODE_REQUIRED_FIELDS)

    def test_scroll_action_is_excluded_for_current_scope(self) -> None:
        self.assertNotIn("scroll", GUIDE_COMMAND_ALLOWED_ACTIONS)


if __name__ == "__main__":
    unittest.main()
