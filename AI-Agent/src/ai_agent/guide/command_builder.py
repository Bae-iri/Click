"""Build FE-renderable Guide Commands."""

from __future__ import annotations

from uuid import uuid4

from ai_agent.schemas import GuideCommand, NormalizedNode, SafetyLevel


class GuideCommandBuilder:
    def build_tap_highlight(self, target: NormalizedNode, message: str, safety_level: SafetyLevel) -> GuideCommand:
        return GuideCommand(
            guide_command_id=f"guide_{uuid4().hex}",
            command_type="highlight",
            action_type="tap",
            target_node_id=target.node_id,
            target_bounds=target.bounds,
            message=message,
            safety_level=safety_level,
            requires_user_action=True,
            render_policy={"avoid_covering_target": True, "preferred_position": "below_target"},
        )

    def build_scroll_hint(self, direction: str, message: str, safety_level: SafetyLevel) -> GuideCommand:
        return GuideCommand(
            guide_command_id=f"guide_{uuid4().hex}",
            command_type="gesture_hint",
            action_type="scroll",
            message=message,
            safety_level=safety_level,
            requires_user_action=False,
            render_policy={"direction": direction, "manual_override_allowed": True},
        )
