"""Contract validation for FE -> Agent requests."""

from __future__ import annotations

from ai_agent.runtime.types import AgentTurnRequest, TurnPhase
from functools import lru_cache
import json
from pathlib import Path


@lru_cache(maxsize=1)
def _approved_settings_slots():
    catalog = json.loads((Path(__file__).resolve().parents[3] / 'goal_catalog/mvp_goals.json').read_text())
    return {entry['goal_type']: entry['slots'] for entry in catalog['goals']
        if entry.get('execution_status') == 'approved'}


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
    from ai_agent.runtime.execution_contract import validate_context
    try:
        validate_context(request.execution_context,session_id=request.session_id,request_id=request.request_id,
            episode_id=request.episode_id,observation_id=request.observation_id)
    except ValueError as invalid:
        raise ContractViolationError(str(invalid)) from invalid
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
        supported = _approved_settings_slots()
        if goal.goal_type not in supported:
            raise ContractViolationError("Unsupported typed goal.")
        expected_app = 'coupang' if goal.goal_type.startswith('coupang.') else 'android_settings'
        if goal.target_app != expected_app:
            raise ContractViolationError('typed_goal.target_app does not match the goal.')
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
