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
)

FE_TO_AGENT_OPTIONAL_FIELDS = (
    "raw_goal",
    "accessibility_tree",
    "vision_snapshot",
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
    "accessibility_tree should contain the currently visible Android Accessibility nodes.",
    "An explicit empty accessibility_tree means Accessibility data was unavailable; Agent returns a fallback command instead of treating it as a successful observation.",
    "If accessibility_tree is unavailable, send an empty tree with package, dimensions and window context to obtain a BE vision challenge.",
    "Every vision capture/analysis requires a new explicit user consent; session-wide boolean consent is not sufficient.",
    "Send vision_snapshot only with respond_confirmation, accepted, confirmation_id and the issued expected_version/episode_id.",
    "Vision observation_id, package, dimensions and active_window_id must match the accompanying fresh tree; a changed screen invalidates consent.",
    "Vision fallback is used only when Accessibility data is empty or target matching fails.",
    "Use on-device OCR and send only allowed labels/bounds; raw screenshots are not accepted for analysis.",
    "bounds_in_screen uses physical screen pixels as [left, top, right, bottom].",
    "FE must preserve Android node identity when possible by passing stable node_id values.",
    "FE must send a fresh accessibility_tree after every user action.",
    "user_confirmation is only accepted as accepted or rejected.",
    "FE should send user_action_event after tap, drag, scroll, back navigation, or manual override.",
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
    "For scroll commands, FE executes Accessibility scroll and sends a fresh tree plus scroll_completed or scroll_failed.",
    "When render_policy has recovery_action, FE should execute or present that recovery path and send a fresh tree plus manual_override.",
    "When render_policy has fallback_action.request_fresh_observation, send a fresh tree without implicitly enabling vision.",
    "When render_policy.vision_fallback requires consent, show a new confirmation before capture. Blocked/no-signal never auto-captures.",
    "metadata.debug.observation shows what the Agent actually received and normalized.",
    "metadata.debug.fallback shows which fallback branch, if any, was selected.",
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
    "scroll_completed",
    "scroll_failed",
    "back_navigated",
    "home_navigated",
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
