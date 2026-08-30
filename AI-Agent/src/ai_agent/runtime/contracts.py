"""FE <-> Agent contract rules for MVP-01.

This file is the shared vocabulary for the first integration pass. Backend API
schemas can import or mirror these names when the HTTP boundary is added.
"""

from __future__ import annotations

from ai_agent.runtime.types import ActionType, CommandType, SafetyLevel


CONTRACT_VERSION = "agent-fe-contract.v0.1"


FE_TO_AGENT_REQUIRED_FIELDS = (
    "request_id",
    "session_id",
    "accessibility_tree",
)

FE_TO_AGENT_OPTIONAL_FIELDS = (
    "raw_goal",
    "previous_state_id",
    "previous_guide_command_id",
    "user_confirmation",
    "user_action_event",
)

AGENT_TO_FE_REQUIRED_FIELDS = (
    "request_id",
    "session_id",
    "current_state_id",
    "guide_command",
)

ACCESSIBILITY_NODE_REQUIRED_FIELDS = (
    "node_id",
    "package_name",
    "class_name",
    "text",
    "content_description",
    "resource_id",
    "bounds_in_screen",
    "clickable",
    "enabled",
    "selected",
    "scrollable",
    "checkable",
)


FE_INPUT_RULES = (
    "FE sends one AgentTurnRequest per observe cycle.",
    "request_id must be unique per Agent call and echoed back by Agent.",
    "session_id must stay stable while the user is pursuing the same goal.",
    "raw_goal is required on the first turn and when the user changes intent.",
    "raw_goal.device_context.platform must be android for MVP-01.",
    "accessibility_tree must contain the currently visible Android Accessibility nodes.",
    "bounds_in_screen uses physical screen pixels as [left, top, right, bottom].",
    "FE must preserve Android node identity when possible by passing stable node_id values.",
    "FE must send a fresh accessibility_tree after every user action.",
    "user_confirmation is only accepted as accepted or rejected.",
    "FE should send user_action_event after tap, drag, back navigation, or manual override.",
)


FE_OUTPUT_RULES = (
    "Agent returns one GuideCommand per response.",
    "Agent never asks FE to auto-tap, auto-drag, submit, consent, purchase, or type.",
    "For tap and drag commands, FE renders guidance only and waits for user action.",
    "GuideCommand.target_bounds is advisory; FE owns final overlay placement.",
    "FE must avoid covering the target node when render_policy.avoid_covering_target is true.",
    "FE should expire stale guidance after ttl_ms unless a newer response arrives.",
    "When command_type is confirmation, FE must collect explicit user confirmation.",
    "When safety_level is BLOCKED, FE must stop task guidance for that action.",
    "For scroll commands, FE executes Accessibility scroll and sends a fresh tree plus user_action_event.",
)


SESSION_STATE_RULES = (
    "Agent state is keyed by session_id.",
    "Backend must preserve session_id for the full task attempt.",
    "Agent stores typed_goal, task_id, current_state_id, pending_transition_id, and last_guide_command_id per session.",
    "Agent also tracks failed_node_ids, transition_attempts, last_target_node_id, last screen snapshot, and last user action event for L3 replanning.",
    "A follow-up turn without raw_goal is valid only when session_id resolves to an existing Agent session.",
    "If the user changes intent, FE must send a new raw_goal; Agent will re-type the goal for the same session.",
)


USER_ACTION_EVENT_TYPES = (
    "tap_completed",
    "drag_completed",
    "back_navigated",
    "manual_override",
    "observe_only",
)


GUIDE_COMMAND_ALLOWED_ACTIONS = (
    ActionType.TAP.value,
    ActionType.DRAG.value,
    ActionType.OBSERVE_ONLY.value,
)

GUIDE_COMMAND_ALLOWED_COMMANDS = (
    CommandType.HIGHLIGHT.value,
    CommandType.CONFIRMATION.value,
    CommandType.DONE.value,
    CommandType.BLOCKED.value,
)

GUIDE_COMMAND_ALLOWED_SAFETY_LEVELS = (
    SafetyLevel.LOW_RISK.value,
    SafetyLevel.CONFIRM_REQUIRED.value,
    SafetyLevel.BLOCKED.value,
)


def contract_summary() -> dict[str, object]:
    return {
        "contract_version": CONTRACT_VERSION,
        "fe_to_agent_required_fields": FE_TO_AGENT_REQUIRED_FIELDS,
        "fe_to_agent_optional_fields": FE_TO_AGENT_OPTIONAL_FIELDS,
        "agent_to_fe_required_fields": AGENT_TO_FE_REQUIRED_FIELDS,
        "accessibility_node_required_fields": ACCESSIBILITY_NODE_REQUIRED_FIELDS,
        "fe_input_rules": FE_INPUT_RULES,
        "fe_output_rules": FE_OUTPUT_RULES,
        "session_state_rules": SESSION_STATE_RULES,
        "user_action_event_types": USER_ACTION_EVENT_TYPES,
        "guide_command_allowed_actions": GUIDE_COMMAND_ALLOWED_ACTIONS,
        "guide_command_allowed_commands": GUIDE_COMMAND_ALLOWED_COMMANDS,
        "guide_command_allowed_safety_levels": GUIDE_COMMAND_ALLOWED_SAFETY_LEVELS,
    }
