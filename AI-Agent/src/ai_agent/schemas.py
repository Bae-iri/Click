"""Agent(1) component contracts and shared declarative graph definitions.

The active turn runtime uses ai_agent.runtime.types. These lightweight contracts
also support the existing Backend guidance adapter. Offline graph proposals
belong exclusively to ai_agent.graph_proposal.schemas; observation summaries
belong to ai_agent.telemetry.schemas.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal

SafetyLevel = Literal["LOW_RISK", "CONFIRM_REQUIRED", "BLOCKED"]
ActionType = Literal["tap", "drag", "scroll", "observe_only"]
GuideCommandType = Literal["highlight", "gesture_hint", "confirmation", "done", "blocked"]


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
