"""Shared data contracts for the Click AI Agent.

The module intentionally uses only Python standard-library types so the MVP can
run in tests before Backend, FE, or Gemini similarity integrations are wired in.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any, Literal


Bounds = tuple[int, int, int, int]
JsonDict = dict[str, Any]


class ActionType(str, Enum):
    TAP = "tap"
    DRAG = "drag"
    SCROLL = "scroll"
    OBSERVE_ONLY = "observe_only"


class CommandType(str, Enum):
    HIGHLIGHT = "highlight"
    GESTURE_HINT = "gesture_hint"
    CONFIRMATION = "confirmation"
    DONE = "done"
    BLOCKED = "blocked"


class SafetyLevel(str, Enum):
    LOW_RISK = "LOW_RISK"
    CONFIRM_REQUIRED = "CONFIRM_REQUIRED"
    BLOCKED = "BLOCKED"


class VerificationStatus(str, Enum):
    WAITING_FOR_ACTION = "WAITING_FOR_ACTION"
    WAITING_FOR_OBSERVATION = "WAITING_FOR_OBSERVATION"
    SUCCESS = "SUCCESS"
    DONE = "DONE"
    NO_CHANGE = "NO_CHANGE"
    WRONG_STATE = "WRONG_STATE"
    UNKNOWN = "UNKNOWN"


class TurnPhase(str, Enum):
    FIRST_TURN = "first_turn"
    FOLLOW_UP = "follow_up"


class UserActionType(str, Enum):
    TAP_COMPLETED = "tap_completed"
    DRAG_COMPLETED = "drag_completed"
    SCROLL_COMPLETED = "scroll_completed"
    SCROLL_FAILED = "scroll_failed"
    BACK_NAVIGATED = "back_navigated"
    MANUAL_OVERRIDE = "manual_override"
    OBSERVE_ONLY = "observe_only"


@dataclass(frozen=True, slots=True)
class DeviceContext:
    platform: Literal["android", "ios", "web"]
    foreground_app: str | None = None
    app_package: str | None = None
    manufacturer: str | None = None
    os_version: str | None = None
    screen_bounds: Bounds | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class RawGoalInput:
    source_text: str
    locale: str
    device_context: DeviceContext

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class TypedGoal:
    goal_type: str
    target_app: str
    task_type: str
    slots: dict[str, str]
    source_text: str
    confidence: float
    task_id: str | None = None
    typing_method: str | None = None
    is_supported: bool = False
    required_slots_filled: bool = False
    needs_clarification: bool = False
    clarification_message: str | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class AccessibilityNode:
    node_id: str
    package_name: str | None = None
    class_name: str | None = None
    text: str | None = None
    content_description: str | None = None
    resource_id: str | None = None
    bounds_in_screen: Bounds | None = None
    clickable: bool = False
    enabled: bool = True
    selected: bool = False
    scrollable: bool = False
    checkable: bool = False
    extras: JsonDict = field(default_factory=dict)
    parent_id: str | None = None
    visible_to_user: bool = True
    window_id: int | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class VisionElement:
    element_id: str
    text: str
    bounds_in_screen: Bounds | None = None
    role: str | None = None
    confidence: float | None = None
    actionable: bool = True

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class VisionSnapshot:
    user_consented: bool = False
    # Trusted BE-only binding. Never deserialize this field from an FE payload.
    consent_request_id: str | None = None
    privacy_status: str = "unverified"
    redaction_applied: bool = False
    provider: str | None = None
    screenshot_base64: str | None = None
    mime_type: str = "image/png"
    ocr_text: str | None = None
    elements: tuple[VisionElement, ...] = ()
    package_name: str | None = None
    screen_width: int | None = None
    screen_height: int | None = None
    captured_at_ms: int | None = None
    observation_id: str | None = None
    active_window_id: int | None = None
    processing_time_ms: int | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class NormalizedNode:
    node_id: str
    label: str
    role: str
    semantic_tags: tuple[str, ...] = ()
    bounds: Bounds | None = None
    actionable: bool = False
    clickable: bool = False
    enabled: bool = True
    selected: bool = False
    scrollable: bool = False
    checkable: bool = False
    resource_id: str | None = None
    class_name: str | None = None
    package_name: str | None = None
    position_hint: Literal["top", "middle", "bottom", "unknown"] = "unknown"
    value: float | None = None
    similarity_ref: str | None = None
    window_id: int | None = None
    range_min: float | None = None
    range_max: float | None = None
    label_parts: tuple[str, ...] = ()
    structure_role: str = "unknown"
    source_confidence: float | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class TaskDefinition:
    task_id: str
    goal_type: str
    name: str
    target_app: str
    task_type: str
    start_states: tuple[str, ...]
    done_state: str
    required_slots: dict[str, str]

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class TaskState:
    state_id: str
    name: str
    required_keywords: tuple[str, ...] = ()
    required_keyword_groups: tuple[tuple[str, ...], ...] = ()
    optional_keywords: tuple[str, ...] = ()
    package_candidates: tuple[str, ...] = ()
    role_hints: tuple[str, ...] = ()
    confidence_threshold: float = 0.75
    verification_only: bool = False

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class TargetSelector:
    synonym_keys: tuple[str, ...] = ()
    package_candidates: tuple[str, ...] = ()
    resource_ids: tuple[str, ...] = ()
    context_keywords: tuple[str, ...] = ()
    excluded_keywords: tuple[str, ...] = ()
    allow_unlabelled_in_context: bool = False


@dataclass(frozen=True, slots=True)
class RuntimePolicy:
    retry_limit: int = 2
    settle_ms: int = 500
    observation_timeout_ms: int = 5000
    recovery_limit: int = 3
    max_failed_attempts: int = 6

    def __post_init__(self):
        if any(value <= 0 for value in (self.retry_limit, self.settle_ms,
                self.observation_timeout_ms, self.recovery_limit, self.max_failed_attempts)):
            raise ValueError("Runtime policy limits must be positive")
        if self.observation_timeout_ms < self.settle_ms:
            raise ValueError("Observation timeout must allow the settling interval")


@dataclass(frozen=True, slots=True)
class Transition:
    transition_id: str
    from_state: str
    to_state: str
    action_type: ActionType
    action_name: str
    target_keywords: tuple[str, ...]
    target_role: str | None
    safety_level: SafetyLevel
    guide_message: str
    allow_scroll: bool = False
    requires_user_action: bool = True
    confirmation_message: str | None = None
    selector: TargetSelector = field(default_factory=TargetSelector)
    priority: int = 0
    verification_predicate: str = "state_reached"
    completes_task: bool = False

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class TaskGraph:
    definition: TaskDefinition
    states: dict[str, TaskState]
    transitions: tuple[Transition, ...]
    synonyms: dict[str, tuple[str, ...]]
    policy: RuntimePolicy = field(default_factory=RuntimePolicy)

    def transitions_from(self, state_id: str) -> tuple[Transition, ...]:
        return tuple(sorted((
            transition
            for transition in self.transitions
            if transition.from_state == state_id
        ), key=lambda transition: (transition.priority, transition.transition_id)))

    def state(self, state_id: str) -> TaskState:
        return self.states[state_id]

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class MatchResult:
    state_id: str
    next_state_id: str
    action_type: ActionType
    target_node_id: str | None
    target_label: str | None
    target_bounds: Bounds | None
    match_method: str
    confidence: float
    transition_id: str | None = None
    fallback_reason: str | None = None
    target_key: str | None = None
    score_gap: float | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class GuideCommand:
    guide_command_id: str
    command_type: CommandType
    action_type: ActionType
    message: str
    safety_level: SafetyLevel
    requires_user_action: bool
    target_node_id: str | None = None
    target_label: str | None = None
    target_bounds: Bounds | None = None
    render_policy: JsonDict = field(
        default_factory=lambda: {
            "avoid_covering_target": True,
            "preferred_position": "below_target",
        }
    )
    ttl_ms: int = 10_000

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class VerificationResult:
    status: VerificationStatus
    current_state_id: str
    expected_state_id: str | None = None
    message: str | None = None
    attempt_id: str | None = None
    count_attempt: bool = False
    task_completed: bool = False
    evidence: str | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class UserActionEvent:
    action_type: UserActionType
    guide_command_id: str | None = None
    target_node_id: str | None = None
    target_bounds: Bounds | None = None
    occurred_at_ms: int | None = None
    event_id: str | None = None
    session_id: str | None = None
    episode_id: str | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class AgentTurnRequest:
    request_id: str
    session_id: str
    raw_goal: RawGoalInput | None
    accessibility_tree: tuple[AccessibilityNode, ...]
    typed_goal: TypedGoal | None = None
    device_context: DeviceContext | None = None
    vision_snapshot: VisionSnapshot | None = None
    previous_state_id: str | None = None
    previous_guide_command_id: str | None = None
    user_confirmation: Literal["accepted", "rejected"] | None = None
    user_action_event: UserActionEvent | None = None
    screen_bounds: Bounds | None = None
    observation_id: str | None = None
    captured_at_ms: int | None = None
    active_window_id: int | None = None
    episode_id: str | None = None
    applied_settings: JsonDict = field(default_factory=dict)
    vision_failure: str | None = None

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class ScreenSnapshot:
    state_id: str
    fingerprint: str
    slider_values: dict[str, float] = field(default_factory=dict)
    observation_id: str | None = None
    captured_at_ms: int | None = None
    applied_settings: JsonDict = field(default_factory=dict)

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class ReplanDecision:
    should_replan: bool
    reason: str | None = None
    excluded_node_ids: tuple[str, ...] = ()
    prefer_current_state: bool = True
    strategy: str = "continue"

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class AgentSessionState:
    session_id: str
    task_id: str
    typed_goal: TypedGoal
    current_state_id: str
    pending_transition_id: str | None = None
    pending_confirmation_transition_id: str | None = None
    last_guide_command_id: str | None = None
    last_target_node_id: str | None = None
    failed_node_ids: tuple[str, ...] = ()
    transition_attempts: dict[str, int] = field(default_factory=dict)
    last_snapshot: ScreenSnapshot | None = None
    last_user_action_event: UserActionEvent | None = None
    pending_attempt: JsonDict | None = None
    failed_target_keys: dict[str, tuple[str, ...]] = field(default_factory=dict)
    processed_event_keys: tuple[str, ...] = ()
    recovery_count: int = 0
    total_failed_attempts: int = 0
    task_completed: bool = False
    observed_path: tuple[str, ...] = ()
    technical_outcome: JsonDict = field(default_factory=dict)
    progress_steps: tuple[JsonDict, ...] = ()
    recovery_state: JsonDict = field(default_factory=dict)
    state_candidates: tuple[JsonDict, ...] = ()
    vision_state: JsonDict = field(default_factory=dict)

    def to_dict(self) -> JsonDict:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class AgentTurnResponse:
    request_id: str
    session_id: str
    task_id: str | None
    typed_goal: TypedGoal | None
    current_state_id: str
    guide_command: GuideCommand
    match_result: MatchResult | None = None
    verification_result: VerificationResult | None = None
    debug: JsonDict = field(default_factory=dict)

    def to_dict(self) -> JsonDict:
        return asdict(self)
