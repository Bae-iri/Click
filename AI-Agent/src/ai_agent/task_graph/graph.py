"""In-memory Task Graph structures."""

from __future__ import annotations

from dataclasses import dataclass

from ai_agent.schemas import TaskDefinition, TransitionDefinition


@dataclass(frozen=True)
class TaskGraph:
    task: TaskDefinition
    states: dict[str, dict]
    transitions: dict[str, TransitionDefinition]
    scroll_policy: dict[str, dict]

    def outgoing(self, state_id: str) -> list[TransitionDefinition]:
        return [transition for transition in self.transitions.values() if transition.from_state == state_id]
