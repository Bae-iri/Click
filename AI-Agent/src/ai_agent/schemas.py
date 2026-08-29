"""Shared data contracts for the AI Agent.

These dataclasses mirror docs/ai-agent-architecture.md. They are intentionally
lightweight so each module can evolve without committing to storage or API
framework choices too early.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal


SafetyLevel = Literal["LOW_RISK", "CONFIRM_REQUIRED", "BLOCKED"]
ActionType = Literal["tap", "drag", "scroll", "observe_only"]
GuideCommandType = Literal["highlight", "gesture_hint", "confirmation", "done", "blocked"]
VerificationResult = Literal["SUCCESS", "DONE", "NO_CHANGE", "WRONG_STATE", "UNKNOWN"]


@dataclass(frozen=True)
class RawGoalInput:
    source_text: str
    locale: str = "ko-KR"
    device_context: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class TypedGoal:
    goal_type: str
    target_app: str
    task_type: str
    slots: dict[str, Any]
    source_text: str
    confidence: float
    top_candidates: list[dict[str, Any]] = field(default_factory=list)


@dataclass(frozen=True)
class NormalizedNode:
    node_id: str
    label: str
    role: str
    bounds: tuple[int, int, int, int]
    clickable: bool
    enabled: bool
    semantic_tags: tuple[str, ...] = ()
    resource_id: str | None = None
    class_name: str | None = None
    scrollable: bool = False


@dataclass(frozen=True)
class TransitionDefinition:
    transition_id: str
    from_state: str
    to_state: str
    action_type: ActionType
    target_keywords: tuple[str, ...]
    target_role: str | None
    safety_level: SafetyLevel
    allow_scroll: bool = False


@dataclass(frozen=True)
class TaskDefinition:
    task_id: str
    goal_type: str
    name: str
    target_app: str
    task_type: str
    start_states: tuple[str, ...]
    done_state: str
    graph_version: str


@dataclass(frozen=True)
class TargetMatch:
    action_type: ActionType
    target_node_id: str | None
    target_label: str | None
    confidence: float
    match_method: str
    score_breakdown: dict[str, float] = field(default_factory=dict)


@dataclass(frozen=True)
class GuideCommand:
    guide_command_id: str
    command_type: GuideCommandType
    action_type: ActionType
    message: str
    safety_level: SafetyLevel
    requires_user_action: bool
    target_node_id: str | None = None
    target_bounds: tuple[int, int, int, int] | None = None
    render_policy: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ScrollCommand:
    scroll_command_id: str
    task_id: str
    state_id: str
    transition_id: str
    direction: Literal["down", "up"]
    scroll_amount: Literal["page", "half_page"] | float
    max_attempts: int
    stop_condition: tuple[str, ...]
    scrollable_node_id: str | None = None
    target_hint: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RuntimeTrace:
    trace_id: str
    task_id: str
    graph_version: str
    goal_type: str
    state_sequence: list[str]
    transition_sequence: list[str]
    result: VerificationResult
    failure_reason: str | None
    user_corrected_path: bool


@dataclass(frozen=True)
class GraphUpdateProposal:
    proposal_id: str
    task_id: str
    base_graph_version: str
    proposal_type: str
    change: dict[str, Any]
    evidence: dict[str, Any]
    proposal_confidence: float
    requires_human_review: bool
