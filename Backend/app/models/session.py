from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass
class GuidanceSession:
    session_id: str
    task_id: str
    goal: str
    goal_type: str
    graph_version: str
    status: str = "WAITING_FOR_SCREEN"
    current_state: str | None = None
    step: int = 0
    slider_values: dict[str, float] = field(default_factory=dict)
    created_at: datetime = field(default_factory=utc_now)
    updated_at: datetime = field(default_factory=utc_now)
