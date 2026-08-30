"""Runtime engine for the Click AI Agent MVP flow."""

from __future__ import annotations

from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.goal_typing import type_goal
from ai_agent.runtime.guide import build_guide_command, clarification_command
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.replanner import decide_replan
from ai_agent.runtime.session import InMemorySessionStore, SessionStore
from ai_agent.runtime.state_manager import build_screen_snapshot, register_attempt_outcome
from ai_agent.runtime.tasks import TASK_GRAPHS
from ai_agent.runtime.types import (
    AgentTurnRequest,
    AgentTurnResponse,
    CommandType,
    TaskGraph,
    TypedGoal,
    VerificationResult,
    VerificationStatus,
)
from ai_agent.runtime.validation import ContractViolationError, validate_agent_turn_request
from ai_agent.runtime.verification import verify_transition
from ai_agent.runtime.similarity import default_similarity_scorer
from ai_agent.learning.trace_logger import TraceLogger


class ClickAgentEngine:
    def __init__(
        self,
        *,
        session_store: SessionStore | None = None,
        task_graphs: dict[str, TaskGraph] | None = None,
        similarity_scorer=None,
        trace_logger: TraceLogger | None = None,
    ) -> None:
        self.session_store = session_store or InMemorySessionStore()
        self.task_graphs = task_graphs or TASK_GRAPHS
        self.similarity_scorer = similarity_scorer or default_similarity_scorer()
        self.trace_logger = trace_logger or TraceLogger()
        self.traces = self.trace_logger.traces

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        existing_session = self.session_store.get(request.session_id)
        validate_agent_turn_request(request, has_session=existing_session is not None)

        typed_goal = self._resolve_typed_goal(
            request,
            existing_session.typed_goal if existing_session else None,
        )
        if typed_goal.needs_clarification or not typed_goal.is_supported:
            guide_command = clarification_command(
                session_id=request.session_id,
                message=typed_goal.clarification_message
                or "어떤 작업을 원하는지 한 번만 더 알려주세요.",
            )
            return AgentTurnResponse(
                request_id=request.request_id,
                session_id=request.session_id,
                task_id=typed_goal.task_id,
                typed_goal=typed_goal,
                current_state_id=existing_session.current_state_id
                if existing_session
                else "S0_UNKNOWN",
                guide_command=guide_command,
                debug={"contract": "first_turn_raw_goal_required"},
            )

        graph = self._task_graph_for(typed_goal)
        previous_state_id = (
            existing_session.current_state_id
            if existing_session
            else request.previous_state_id
        )
        screen_bounds = (
            request.raw_goal.device_context.screen_bounds
            if request.raw_goal
            else None
        )
        normalized_nodes = normalize_accessibility_tree(
            request.accessibility_tree,
            screen_bounds=screen_bounds,
        )
        current_state_id = classify_screen_state(
            normalized_nodes,
            graph,
            device_context=request.raw_goal.device_context if request.raw_goal else None,
            previous_state_id=previous_state_id,
        )
        current_snapshot = build_screen_snapshot(current_state_id, normalized_nodes)
        verification_result = verify_transition(
            graph=graph,
            previous_state_id=previous_state_id,
            pending_transition_id=existing_session.pending_transition_id
            if existing_session
            else None,
            current_state_id=current_state_id,
            previous_snapshot=existing_session.last_snapshot
            if existing_session
            else None,
            current_snapshot=current_snapshot,
        )
        transition_attempts, failed_node_ids = register_attempt_outcome(
            existing_session,
            pending_transition_id=existing_session.pending_transition_id
            if existing_session
            else None,
            verification_result=verification_result,
        )
        replan_decision = decide_replan(
            session=existing_session,
            verification_result=verification_result,
            failed_node_ids=failed_node_ids,
            transition_attempts=transition_attempts,
        )
        effective_state_id = _effective_state_id(current_state_id, verification_result)
        match_result = match_next_ui(
            effective_state_id,
            normalized_nodes,
            graph,
            excluded_node_ids=replan_decision.excluded_node_ids,
            similarity_scorer=self.similarity_scorer,
        )
        guide_command = build_guide_command(
            session_id=request.session_id,
            match_result=match_result,
            graph=graph,
            user_confirmation=request.user_confirmation,
        )
        pending_transition_id = _pending_transition_id(
            guide_command.command_type,
            match_result.transition_id,
        )
        pending_confirmation_transition_id = _pending_confirmation_transition_id(
            guide_command.command_type,
            match_result.transition_id,
        )

        if existing_session:
            self.session_store.update(
                request.session_id,
                task_id=graph.definition.task_id,
                typed_goal=typed_goal,
                current_state_id=effective_state_id,
                pending_transition_id=pending_transition_id,
                pending_confirmation_transition_id=pending_confirmation_transition_id,
                last_guide_command_id=guide_command.guide_command_id,
                last_target_node_id=match_result.target_node_id,
                failed_node_ids=failed_node_ids,
                transition_attempts=transition_attempts,
                last_snapshot=current_snapshot,
                last_user_action_event=request.user_action_event,
            )
        else:
            self.session_store.start(
                session_id=request.session_id,
                task_id=graph.definition.task_id,
                typed_goal=typed_goal,
                current_state_id=effective_state_id,
                pending_transition_id=pending_transition_id,
                pending_confirmation_transition_id=pending_confirmation_transition_id,
                last_guide_command_id=guide_command.guide_command_id,
                last_target_node_id=match_result.target_node_id,
                failed_node_ids=failed_node_ids,
                transition_attempts=transition_attempts,
                last_snapshot=current_snapshot,
                last_user_action_event=request.user_action_event,
            )

        response = AgentTurnResponse(
            request_id=request.request_id,
            session_id=request.session_id,
            task_id=graph.definition.task_id,
            typed_goal=typed_goal,
            current_state_id=effective_state_id,
            guide_command=guide_command,
            match_result=match_result,
            verification_result=verification_result,
            debug={
                "normalized_node_count": len(normalized_nodes),
                "session_stateful": True,
                "similarity_provider": getattr(self.similarity_scorer, "provider", "custom"),
                "replan_decision": replan_decision.to_dict(),
                "failed_node_ids": failed_node_ids,
                "transition_attempts": transition_attempts,
            },
        )
        self.trace_logger.record({
            "request_id": request.request_id,
            "session_id": request.session_id,
            "task_id": graph.definition.task_id,
            "graph_version": "mvp-01.v1",
            "typed_goal": typed_goal.to_dict(),
            "state_id": effective_state_id,
            "match": match_result.to_dict(),
            "guide": guide_command.to_dict(),
            "verification": verification_result.to_dict() if verification_result else None,
            "user_action_event": request.user_action_event.to_dict() if request.user_action_event else None,
            "failure_reason": match_result.fallback_reason,
        })
        return response

    def _resolve_typed_goal(
        self,
        request: AgentTurnRequest,
        existing_typed_goal: TypedGoal | None,
    ) -> TypedGoal:
        if request.raw_goal:
            return type_goal(request.raw_goal)
        if existing_typed_goal:
            return existing_typed_goal
        raise ContractViolationError(
            "Follow-up turn requires an existing session or a new raw_goal."
        )

    def _task_graph_for(self, typed_goal: TypedGoal) -> TaskGraph:
        task_key = typed_goal.task_id or typed_goal.goal_type
        try:
            return self.task_graphs[task_key]
        except KeyError as exc:
            raise ContractViolationError(
                f"No task graph registered for {task_key}."
            ) from exc


def _pending_transition_id(
    command_type: CommandType,
    transition_id: str | None,
) -> str | None:
    if command_type in {
        CommandType.CONFIRMATION,
        CommandType.DONE,
        CommandType.BLOCKED,
    }:
        return None
    return transition_id


def _pending_confirmation_transition_id(
    command_type: CommandType,
    transition_id: str | None,
) -> str | None:
    if command_type == CommandType.CONFIRMATION:
        return transition_id
    return None


def _effective_state_id(
    current_state_id: str,
    verification_result: VerificationResult | None,
) -> str:
    if (
        verification_result
        and getattr(verification_result, "status", None) == VerificationStatus.SUCCESS
        and getattr(verification_result, "expected_state_id", None)
    ):
        return verification_result.expected_state_id
    return current_state_id
