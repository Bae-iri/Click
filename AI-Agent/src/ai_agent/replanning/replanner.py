"""Re-plan after failed or unexpected transitions."""

from __future__ import annotations

from .state_manager import StateManager


class Replanner:
    def replan(self, state_manager: StateManager, current_state: str):
        return state_manager.next_transitions(current_state)
