"""Shared observation-summary contracts, not Agent(2) outcome labels.

RuntimeTrace is the existing offline summary format. TraceLogger emits filtered
DIAGNOSTIC dicts, not this dataclass; an evidence-preserving adapter is still
needed before runtime data can be used by the offline pipeline.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

RuntimeVerificationStatus = Literal["SUCCESS", "DONE", "NO_CHANGE", "WRONG_STATE", "UNKNOWN"]


@dataclass(frozen=True)
class RuntimeTrace:
    trace_id: str
    task_id: str
    graph_version: str
    goal_type: str
    state_sequence: list[str]
    transition_sequence: list[str]
    result: RuntimeVerificationStatus
    failure_reason: str | None
    user_corrected_path: bool
