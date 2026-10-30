"""Contract validation for FE -> Agent requests."""

from __future__ import annotations

from ai_agent.runtime.types import AgentTurnRequest, TurnPhase


class ContractViolationError(ValueError):
    """Raised when FE sends a request that violates the shared contract."""


def determine_turn_phase(request: AgentTurnRequest, has_session: bool) -> TurnPhase:
    if has_session or request.previous_state_id or request.previous_guide_command_id:
        return TurnPhase.FOLLOW_UP
    return TurnPhase.FIRST_TURN


def validate_agent_turn_request(
    request: AgentTurnRequest,
    *,
    has_session: bool = False,
) -> TurnPhase:
    if not request.request_id:
        raise ContractViolationError("request_id is required.")
    if not request.session_id:
        raise ContractViolationError("session_id is required.")
    phase = determine_turn_phase(request, has_session)
    if phase == TurnPhase.FIRST_TURN and request.raw_goal is None and request.typed_goal is None:
        raise ContractViolationError("typed_goal is required on a typed first turn.")

    if request.raw_goal is not None and request.typed_goal is not None:
        raise ContractViolationError("raw_goal and typed_goal are mutually exclusive.")

    if request.typed_goal is not None:
        goal = request.typed_goal
        supported = {f"settings.{setting}.{direction}": {"setting": setting, "direction": direction}
                     for setting in ("font_size", "brightness", "volume")
                     for direction in ("increase", "decrease")}
        if goal.goal_type not in supported:
            raise ContractViolationError("Unsupported typed goal.")
        if goal.target_app != "android_settings":
            raise ContractViolationError("typed_goal.target_app must be android_settings.")
        if goal.slots != supported[goal.goal_type]:
            raise ContractViolationError("typed_goal.slots do not match the approved graph.")
        if request.device_context is None or request.device_context.platform != "android":
            raise ContractViolationError("device_context.platform must be android.")

    if request.raw_goal is not None:
        if not request.raw_goal.source_text.strip():
            raise ContractViolationError("raw_goal.source_text is required.")
        if request.raw_goal.device_context.platform != "android":
            raise ContractViolationError(
                "raw_goal.device_context.platform must be android for MVP-01."
            )

    return phase
