"""Read-only access to the shared Goal Type ID catalog."""

from __future__ import annotations

from functools import lru_cache
import json
from pathlib import Path
from typing import Any


CATALOG_PATH = Path(__file__).resolve().parents[2] / "goal_catalog" / "mvp_goals.json"


@lru_cache(maxsize=1)
def load_goal_catalog() -> dict[str, Any]:
    return json.loads(CATALOG_PATH.read_text(encoding="utf-8"))


def active_goal(goal_type_id: str) -> dict[str, Any] | None:
    return next((
        goal for goal in load_goal_catalog()["goals"]
        if goal["goal_type_id"] == goal_type_id and goal.get("status") == "active"
    ), None)


def active_goal_type_ids() -> frozenset[str]:
    return frozenset(
        goal["goal_type_id"] for goal in load_goal_catalog()["goals"]
        if goal.get("status") == "active"
    )


def missing_required_slots(goal: dict[str, Any], slots: dict[str, Any]) -> tuple[str, ...]:
    schema = goal.get("slot_schema", {})
    return tuple(key for key in schema.get("required", ()) if slots.get(key) in (None, ""))
