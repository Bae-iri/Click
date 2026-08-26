"""Boundary for dispatching a scroll command to the FE accessibility service."""

from __future__ import annotations

from ai_agent.schemas import ScrollCommand


class ScrollExecutor:
    def execute(self, command: ScrollCommand) -> dict:
        """Dispatch a LOW_RISK scroll and return the FE execution result shape."""
        return {
            "scroll_command_id": command.scroll_command_id,
            "success": False,
            "moved": False,
            "end_of_list": False,
            "accessibility_tree_ref": None,
        }
