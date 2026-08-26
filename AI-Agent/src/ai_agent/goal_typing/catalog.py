"""Goal catalog loading and lookup."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class GoalCatalogEntry:
    goal_type: str
    target_app: str
    task_type: str
    required_slots: tuple[str, ...]
    utterances: tuple[str, ...]
    synonyms: dict[str, tuple[str, ...]]


class GoalCatalog:
    def __init__(self, entries: list[GoalCatalogEntry]) -> None:
        self.entries = entries

    def find_by_goal_type(self, goal_type: str) -> GoalCatalogEntry | None:
        for entry in self.entries:
            if entry.goal_type == goal_type:
                return entry
        return None
