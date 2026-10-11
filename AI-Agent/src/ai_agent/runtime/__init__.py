"""Click AI Agent core package.

Keep the engine import lazy so low-level runtime types can be imported by
fallback providers without creating an engine -> fallback -> runtime cycle.
"""

from typing import TYPE_CHECKING

from .types import (
    AccessibilityNode,
    ActionType,
    AgentTurnRequest,
    AgentTurnResponse,
    AgentSessionState,
    CommandType,
    DeviceContext,
    GuideCommand,
    MatchResult,
    NormalizedNode,
    RawGoalInput,
    ReplanDecision,
    SafetyLevel,
    ScreenSnapshot,
    TaskDefinition,
    TaskGraph,
    TaskState,
    Transition,
    TurnPhase,
    TypedGoal,
    UserActionEvent,
    UserActionType,
    VerificationResult,
    VerificationStatus,
    VisionElement,
    VisionSnapshot,
)
from .session import InMemorySessionStore, SessionStore
from .validation import ContractViolationError

if TYPE_CHECKING:
    from .engine import ClickAgentEngine


def __getattr__(name: str):
    if name == "ClickAgentEngine":
        from .engine import ClickAgentEngine

        return ClickAgentEngine
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")

__all__ = [
    "AccessibilityNode",
    "ActionType",
    "AgentTurnRequest",
    "AgentTurnResponse",
    "AgentSessionState",
    "CommandType",
    "DeviceContext",
    "GuideCommand",
    "MatchResult",
    "NormalizedNode",
    "RawGoalInput",
    "ReplanDecision",
    "SafetyLevel",
    "ScreenSnapshot",
    "TaskDefinition",
    "TaskGraph",
    "TaskState",
    "Transition",
    "TurnPhase",
    "TypedGoal",
    "UserActionEvent",
    "UserActionType",
    "VerificationResult",
    "VerificationStatus",
    "VisionElement",
    "VisionSnapshot",
    "ClickAgentEngine",
    "ContractViolationError",
    "InMemorySessionStore",
    "SessionStore",
]
