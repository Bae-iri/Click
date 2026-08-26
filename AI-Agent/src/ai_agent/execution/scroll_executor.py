"""Boundary for BE Accessibility scroll execution."""

from __future__ import annotations

from ai_agent.schemas import ScrollCommand


class ScrollExecutor:
    def execute(self, command: ScrollCommand) -> dict:
        """Execute a LOW_RISK scroll through the BE accessibility layer."""
        return {
            "scroll_command_id": command.scroll_command_id,
            "success": False,
            "moved": False,
            "end_of_list": False,
            "accessibility_tree_ref": None,
        }
