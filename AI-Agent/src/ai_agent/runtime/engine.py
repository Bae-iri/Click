"""Runtime engine for the Click AI Agent MVP flow."""

from __future__ import annotations

from dataclasses import replace
from uuid import uuid4
from hashlib import sha256
import json

from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.goal_typing import type_goal
from ai_agent.runtime.guide import (
    build_guide_command,
    clarification_command,
    vision_fallback_command,
)
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.replanner import decide_replan
from ai_agent.runtime.session import InMemorySessionStore, SessionStore
from ai_agent.runtime.state_manager import build_screen_snapshot, register_attempt_outcome
from ai_agent.runtime.tasks import TASK_GRAPHS
from ai_agent.runtime.types import (
    AccessibilityNode,
    ActionType,
    AgentTurnRequest,
    AgentTurnResponse,
    Bounds,
    CommandType,
    GuideCommand,
    JsonDict,
    MatchResult,
    NormalizedNode,
    SafetyLevel,
    TaskGraph,
    TypedGoal,
    VerificationResult,
    VerificationStatus,
)
from ai_agent.runtime.validation import ContractViolationError, validate_agent_turn_request
from ai_agent.runtime.verification import correlate_action, verify_transition
from ai_agent.runtime.similarity import default_similarity_scorer
from ai_agent.fallback.llm_vlm_fallback import (
    LLMVLMFallback,
    VisionFallbackAnalysis,
    vision_elements_to_accessibility_nodes,
)
from ai_agent.telemetry.trace_logger import TraceLogger
from ai_agent.runtime.privacy import sanitize_request


class ClickAgentEngine:
    def __init__(
        self,
        *,
        session_store: SessionStore | None = None,
        task_graphs: dict[str, TaskGraph] | None = None,
        similarity_scorer=None,
        vision_fallback: LLMVLMFallback | None = None,
        trace_logger: TraceLogger | None = None,
    ) -> None:
        self.session_store = session_store or InMemorySessionStore()
        self.task_graphs = task_graphs or TASK_GRAPHS
        self.similarity_scorer = similarity_scorer or default_similarity_scorer()
        self.vision_fallback = vision_fallback or LLMVLMFallback()
        self.trace_logger = trace_logger or TraceLogger()

    @property
    def traces(self):
        return self.trace_logger.traces

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        request = sanitize_request(request)
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
                debug={
                    "contract": "unsupported_or_ambiguous_goal",
                    "observation": _raw_observation_debug(request),
                    "agent_chain": _agent_chain_debug(
                        typed_goal=typed_goal,
                        vision_analysis=None,
                        match_result=None,
                        guide_command=guide_command,
                    ),
                },
            )

        graph = self._task_graph_for(typed_goal)
        previous_state_id = (
            existing_session.current_state_id
            if existing_session
            else request.previous_state_id
        )
        screen_bounds = _screen_bounds(request)
        accessibility_nodes = request.accessibility_tree
        vision_analysis: VisionFallbackAnalysis | None = None
        if not accessibility_nodes and request.vision_snapshot:
            vision_analysis = self.vision_fallback.analyze(
                request.vision_snapshot,
                reason="empty_accessibility_tree",
            )
            accessibility_nodes = _append_vision_nodes(
                accessibility_nodes,
                vision_analysis,
                package_name=_vision_package_name(request),
            )
        elif not accessibility_nodes:
            vision_analysis = VisionFallbackAnalysis(
                status="not_requested",
                reason="empty_accessibility_tree_without_vision_snapshot",
                provider=getattr(self.vision_fallback, "provider", "vision-fallback"),
            )

        normalized_nodes = normalize_accessibility_tree(
            accessibility_nodes,
            screen_bounds=screen_bounds,
            active_window_id=request.active_window_id,
        )
        current_state_id = classify_screen_state(
            normalized_nodes,
            graph,
            device_context=request.raw_goal.device_context if request.raw_goal else None,
            previous_state_id=previous_state_id,
        )
        # Finish observation acquisition before verification/accounting, including vision.
        previous_exclusions = existing_session.failed_target_keys if existing_session else {}
        preliminary = match_next_ui(current_state_id, normalized_nodes, graph,
            excluded_target_keys=previous_exclusions, similarity_scorer=self.similarity_scorer)
        if preliminary.fallback_reason and request.vision_snapshot:
            retry_nodes, vision_analysis = self._retry_with_vision_nodes(
                request=request, existing_nodes=accessibility_nodes,
                current_analysis=vision_analysis, graph=graph, previous_state_id=previous_state_id,
                excluded_node_ids=(), screen_bounds=screen_bounds)
            if retry_nodes:
                normalized_nodes = retry_nodes
        state_evidence = {}
        current_state_id = classify_screen_state(normalized_nodes, graph,
            device_context=request.raw_goal.device_context if request.raw_goal else None,
            previous_state_id=previous_state_id, evidence=state_evidence)
        current_snapshot = build_screen_snapshot(current_state_id, normalized_nodes,
            observation_id=request.observation_id or request.request_id,
            captured_at_ms=request.captured_at_ms)
        previous_transition = next((t for t in graph.transitions if existing_session
            and t.transition_id == existing_session.pending_transition_id), None)
        attempt, processed_events = correlate_action(existing_session, request, previous_transition)
        verified_attempt = attempt
        verification_result = verify_transition(
            graph=graph, previous_state_id=previous_state_id,
            pending_transition_id=existing_session.pending_transition_id if existing_session else None,
            current_state_id=current_state_id,
            previous_snapshot=existing_session.last_snapshot if existing_session else None,
            current_snapshot=current_snapshot, attempt=attempt, nodes=normalized_nodes)
        transition_attempts, failed_node_ids = register_attempt_outcome(
            existing_session,
            pending_transition_id=existing_session.pending_transition_id if existing_session else None,
            verification_result=verification_result, retry_limit=graph.policy.retry_limit)
        failed_target_keys = dict(previous_exclusions)
        failed_attempt = bool(verification_result and verification_result.count_attempt
            and verification_result.status in {VerificationStatus.NO_CHANGE, VerificationStatus.WRONG_STATE})
        if failed_attempt and attempt and previous_transition:
            counter_key = previous_transition.transition_id + ':' + attempt['target_key']
            if transition_attempts.get(counter_key, 0) >= graph.policy.retry_limit:
                failed_target_keys[previous_transition.transition_id] = tuple(sorted({
                    *failed_target_keys.get(previous_transition.transition_id, ()), attempt['target_key']}))
        total_failed_attempts = (existing_session.total_failed_attempts if existing_session else 0) + int(failed_attempt)
        task_completed = bool((existing_session and existing_session.task_completed)
                              or (verification_result and verification_result.task_completed))
        effective_state_id = _effective_state_id(current_state_id, verification_result)
        if existing_session and existing_session.task_completed:
            effective_state_id = existing_session.current_state_id
        match_result = match_next_ui(effective_state_id, normalized_nodes, graph,
            excluded_target_keys=failed_target_keys, similarity_scorer=self.similarity_scorer)
        awaiting_observation = bool(attempt and attempt.get('event_key') and verification_result
            and verification_result.status in {VerificationStatus.WAITING_FOR_OBSERVATION, VerificationStatus.UNKNOWN})
        action_time = attempt.get('action_at_ms') if attempt else None
        within_settling_interval = bool(awaiting_observation and action_time is not None
            and request.captured_at_ms is not None
            and 0 <= request.captured_at_ms - action_time < graph.policy.settle_ms)
        needs_recovery = (not task_completed and not within_settling_interval
                          and (awaiting_observation or bool(match_result.fallback_reason)))
        recovery_count = (existing_session.recovery_count if existing_session else 0) + 1 if needs_recovery else 0
        replan_decision = decide_replan(session=existing_session, verification_result=verification_result,
            failed_node_ids=failed_node_ids, recovery_count=recovery_count, recovery_limit=graph.policy.recovery_limit,
            total_failed_attempts=total_failed_attempts, max_failed_attempts=graph.policy.max_failed_attempts,
            match_result=match_result)
        matched_target = next((node for node in normalized_nodes if node.node_id == match_result.target_node_id), None)
        same_binding = bool(attempt and match_result.target_key == attempt.get('target_key')
            and match_result.transition_id == attempt.get('transition_id')
            and match_result.target_node_id == attempt.get('target_node_id')
            and matched_target and matched_target.window_id == attempt.get('target_window_id')
            and match_result.target_bounds == tuple(attempt.get('target_bounds', ()))
            and (attempt.get('event_key') or matched_target.value == attempt.get('baseline_value')))
        keep_attempt = bool(attempt and not failed_attempt and not task_completed and
            (awaiting_observation or (same_binding and verification_result
             and verification_result.status == VerificationStatus.WAITING_FOR_ACTION)))
        confirmation = request.user_confirmation
        if keep_attempt and same_binding:
            # Already approved this exact pending operation; do not ask again while waiting.
            confirmation = 'accepted'
        guide_command = build_guide_command(session_id=request.session_id, match_result=match_result,
            graph=graph, user_confirmation=confirmation, task_completed=task_completed)
        if match_result.fallback_reason and vision_analysis and not awaiting_observation:
            vision_command = _command_for_vision_analysis(request.session_id, vision_analysis,
                tree_was_empty=not request.accessibility_tree)
            if vision_command:
                guide_command = vision_command
        if awaiting_observation:
            guide_command = GuideCommand(
                guide_command_id=attempt['guide_command_id'], command_type=CommandType.HIGHLIGHT,
                action_type=ActionType.OBSERVE_ONLY, message='조작 결과를 확인하고 있어요.',
                safety_level=SafetyLevel.LOW_RISK, requires_user_action=False,
                render_policy={'fallback_action': {'type': 'request_fresh_observation',
                    'reason': verification_result.evidence,
                    'expected_next_observation': {'send_fresh_accessibility_tree': True}}})
        if replan_decision.strategy == 'backend_reselection' and not task_completed:
            guide_command = GuideCommand(
                guide_command_id='guide_' + uuid4().hex, command_type=CommandType.BLOCKED,
                action_type=ActionType.OBSERVE_ONLY,
                message='현재 작업 경로로 진행할 수 없어 경로를 다시 확인해야 해요.',
                safety_level=SafetyLevel.BLOCKED, requires_user_action=False,
                render_policy={'backend_handoff': {'type': 'task_graph_reselection_required',
                    'reason': replan_decision.reason, 'task_id': graph.definition.task_id}})
            keep_attempt = False
        if request.user_confirmation == 'rejected':
            guide_command = build_guide_command(session_id=request.session_id,
                match_result=match_result, graph=graph, user_confirmation='rejected')
            keep_attempt = False
        if keep_attempt:
            guide_command = replace(guide_command, guide_command_id=attempt['guide_command_id'])
            pending_transition_id = attempt['transition_id']
        else:
            guide_command = replace(guide_command, guide_command_id='guide_' + uuid4().hex)
            pending_transition_id = _pending_transition_id(guide_command, match_result.transition_id)
            attempt = None
            if pending_transition_id and match_result.target_node_id:
                target = next(n for n in normalized_nodes if n.node_id == match_result.target_node_id)
                attempt = {
                    'attempt_id': uuid4().hex, 'step_id': pending_transition_id,
                    'transition_id': pending_transition_id,
                    'guide_command_id': guide_command.guide_command_id,
                    'target_node_id': target.node_id, 'target_key': match_result.target_key,
                    'target_window_id': target.window_id,
                    'target_bounds': target.bounds, 'baseline_value': target.value,
                    'baseline_observation_id': current_snapshot.observation_id,
                    'baseline_captured_at_ms': current_snapshot.captured_at_ms,
                }
        pending_confirmation_transition_id = _pending_confirmation_transition_id(
            guide_command.command_type, match_result.transition_id)
        guide_command = replace(guide_command, render_policy={**guide_command.render_policy,
            'runtime_strategy': replan_decision.strategy,
            'attempt_id': attempt['attempt_id'] if attempt else None,
            'step_id': pending_transition_id})
        observation_debug = _observation_debug(
            request=request,
            normalized_nodes=normalized_nodes,
            current_state_id=current_state_id,
            effective_state_id=effective_state_id,
            vision_analysis=vision_analysis,
        )
        fallback_debug = _fallback_debug(
            match_result=match_result,
            guide_command=guide_command,
            vision_analysis=vision_analysis,
            verification_result=verification_result,
        )
        agent_chain_debug = _agent_chain_debug(
            typed_goal=typed_goal,
            vision_analysis=vision_analysis,
            match_result=match_result,
            guide_command=guide_command,
        )

        session_changes = dict(
            task_id=graph.definition.task_id, typed_goal=typed_goal, current_state_id=effective_state_id,
            pending_transition_id=pending_transition_id,
            pending_confirmation_transition_id=pending_confirmation_transition_id,
            last_guide_command_id=guide_command.guide_command_id,
            last_target_node_id=attempt['target_node_id'] if attempt else match_result.target_node_id,
            failed_node_ids=failed_node_ids, transition_attempts=transition_attempts,
            last_snapshot=current_snapshot, last_user_action_event=None,
            pending_attempt=attempt, processed_event_keys=processed_events,
            failed_target_keys=failed_target_keys, recovery_count=recovery_count,
            total_failed_attempts=total_failed_attempts, task_completed=task_completed)
        if existing_session:
            self.session_store.update(request.session_id, **session_changes)
        else:
            self.session_store.start(session_id=request.session_id, **session_changes)

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
                "state_score": state_evidence['state_score'],
                "task_completed": task_completed,
                "recovery_count": recovery_count,
                "failed_target_keys": failed_target_keys,
                "correlation": {
                    "observation_id": current_snapshot.observation_id,
                    "attempt_id": verification_result.attempt_id if verification_result else None,
                    "next_attempt_id": attempt['attempt_id'] if attempt else None,
                },
                "session_stateful": True,
                "similarity_provider": getattr(self.similarity_scorer, "provider", "custom"),
                "observation": observation_debug,
                "fallback": fallback_debug,
                "agent_chain": agent_chain_debug,
                "replan_decision": replan_decision.to_dict(),
                "failed_node_ids": failed_node_ids,
                "transition_attempts": transition_attempts,
                "vision_fallback": vision_analysis.to_dict()
                if vision_analysis
                else {"status": "not_requested"},
            },
        )
        self.trace_logger.record({
            "request_id": request.request_id,
            "session_id": request.session_id,
            "task_id": graph.definition.task_id,
            "correlation": {
                "session_hash": sha256(request.session_id.encode()).hexdigest(),
                "request_hash": sha256(request.request_id.encode()).hexdigest(),
                "graph_hash": sha256(json.dumps(graph.to_dict(), sort_keys=True).encode()).hexdigest(),
                "observation_hash": sha256(current_snapshot.observation_id.encode()).hexdigest(),
                "guide_hash": sha256(guide_command.guide_command_id.encode()).hexdigest(),
                "attempt_hash": sha256(verification_result.attempt_id.encode()).hexdigest()
                    if verification_result and verification_result.attempt_id else None,
                "action_hash": verified_attempt.get("event_key") if verified_attempt else None,
            },
            "typed_goal": typed_goal.to_dict(),
            "state_id": effective_state_id,
            "observation": observation_debug,
            "fallback": fallback_debug,
            "agent_chain": agent_chain_debug,
            "match": match_result.to_dict(),
            "guide": guide_command.to_dict(),
            "verification": verification_result.to_dict() if verification_result else None,
            "user_action_event": request.user_action_event.to_dict() if request.user_action_event else None,
            "failure_reason": match_result.fallback_reason,
            "vision_fallback": vision_analysis.to_dict()
            if vision_analysis
            else {"status": "not_requested"},
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

    def _retry_with_vision_nodes(
        self,
        *,
        request: AgentTurnRequest,
        existing_nodes: tuple[AccessibilityNode, ...],
        current_analysis: VisionFallbackAnalysis | None,
        graph: TaskGraph,
        previous_state_id: str | None,
        excluded_node_ids: tuple[str, ...],
        screen_bounds: Bounds | None,
    ) -> tuple[tuple[NormalizedNode, ...], VisionFallbackAnalysis]:
        vision_analysis = current_analysis or self.vision_fallback.analyze(
            request.vision_snapshot,
            reason="no_matching_target_node",
        )
        augmented_nodes = _append_vision_nodes(
            existing_nodes,
            vision_analysis,
            package_name=_vision_package_name(request),
        )
        if len(augmented_nodes) <= len(existing_nodes):
            return (), vision_analysis
        normalized_nodes = normalize_accessibility_tree(
            augmented_nodes,
            screen_bounds=screen_bounds,
            active_window_id=request.active_window_id,
        )
        current_state_id = classify_screen_state(
            normalized_nodes,
            graph,
            device_context=request.raw_goal.device_context if request.raw_goal else None,
            previous_state_id=previous_state_id,
        )
        match_result = match_next_ui(
            current_state_id,
            normalized_nodes,
            graph,
            excluded_node_ids=excluded_node_ids,
            similarity_scorer=self.similarity_scorer,
        )
        if match_result.fallback_reason:
            return (), vision_analysis
        return normalized_nodes, vision_analysis


def _raw_observation_debug(request: AgentTurnRequest) -> JsonDict:
    vision_snapshot = request.vision_snapshot
    return {
        "accessibility_tree_present": bool(request.accessibility_tree),
        "accessibility_node_count": len(request.accessibility_tree),
        "vision_snapshot_present": vision_snapshot is not None,
        "vision_element_count": len(vision_snapshot.elements) if vision_snapshot else 0,
        "vision_has_screenshot": bool(vision_snapshot and vision_snapshot.screenshot_base64),
        "vision_has_ocr_text": bool(vision_snapshot and vision_snapshot.ocr_text),
        "foreground_app": request.raw_goal.device_context.foreground_app
        if request.raw_goal
        else None,
        "app_package": _vision_package_name(request),
    }


def _observation_debug(
    *,
    request: AgentTurnRequest,
    normalized_nodes: tuple[NormalizedNode, ...],
    current_state_id: str,
    effective_state_id: str,
    vision_analysis: VisionFallbackAnalysis | None,
) -> JsonDict:
    raw_debug = _raw_observation_debug(request)
    packages = sorted(
        {
            node.package_name
            for node in normalized_nodes
            if node.package_name
        }
    )
    roles = sorted({node.role for node in normalized_nodes})
    tags = sorted(
        {
            tag
            for node in normalized_nodes
            for tag in node.semantic_tags
        }
    )
    actionable_count = sum(1 for node in normalized_nodes if node.actionable)
    synthetic_vision_count = sum(
        1 for node in normalized_nodes if node.node_id.startswith("vision:")
    )
    raw_debug.update({
        "normalized_node_count": len(normalized_nodes),
        "actionable_node_count": actionable_count,
        "synthetic_vision_node_count": synthetic_vision_count,
        "current_state_id": current_state_id,
        "effective_state_id": effective_state_id,
        "observed_packages": packages,
        "visible_roles": roles,
        "visible_tags": tags,
        "vision_status": vision_analysis.status if vision_analysis else "not_requested",
    })
    return raw_debug


def _fallback_debug(
    *,
    match_result: MatchResult,
    guide_command: GuideCommand,
    vision_analysis: VisionFallbackAnalysis | None,
    verification_result: VerificationResult | None,
) -> JsonDict:
    render_policy = guide_command.render_policy
    recovery_action = render_policy.get("recovery_action")
    fallback_action = render_policy.get("fallback_action")
    expected_next_observation = (
        render_policy.get("expected_next_observation")
        or (fallback_action or {}).get("expected_next_observation")
    )
    matched_from_vision = bool(
        match_result.target_node_id
        and match_result.target_node_id.startswith("vision:")
    )
    fallback_type = "none"
    if recovery_action:
        fallback_type = "settings_app_recovery"
    elif render_policy.get("vision_fallback"):
        fallback_type = "vision_fallback"
    elif matched_from_vision:
        fallback_type = "vision_augmented_match"
    elif match_result.fallback_reason:
        fallback_type = "target_match_fallback"
    elif verification_result and verification_result.status in {
        VerificationStatus.NO_CHANGE,
        VerificationStatus.WRONG_STATE,
        VerificationStatus.UNKNOWN,
    }:
        fallback_type = "replan"

    return {
        "type": fallback_type,
        "reason": (
            match_result.fallback_reason
            or (verification_result.message if verification_result else None)
        ),
        "matched_from_vision": matched_from_vision,
        "vision_status": vision_analysis.status if vision_analysis else "not_requested",
        "recovery_action": recovery_action,
        "fallback_action": fallback_action,
        "expected_next_observation": expected_next_observation,
    }


def _agent_chain_debug(
    *,
    typed_goal: TypedGoal | None,
    vision_analysis: VisionFallbackAnalysis | None,
    match_result: MatchResult | None,
    guide_command: GuideCommand,
) -> JsonDict:
    fallback_used = (
        bool(match_result and match_result.fallback_reason)
        or bool(vision_analysis and vision_analysis.status != "not_requested")
        or bool(guide_command.render_policy.get("recovery_action"))
        or bool(guide_command.render_policy.get("vision_fallback"))
    )
    return {
        "goal_typing": {
            "method": typed_goal.typing_method if typed_goal else None,
            "goal_type": typed_goal.goal_type if typed_goal else None,
            "confidence": typed_goal.confidence if typed_goal else None,
        },
        "screen_classifier": "rule_based_task_graph",
        "matcher": getattr(match_result, "match_method", None),
        "similarity": "runtime_similarity_scorer",
        "verification": "snapshot_and_state_transition",
        "replanner": "rule_based_retry_exclusion",
        "fallback": "enabled" if fallback_used else "not_used",
        "guide_builder": guide_command.command_type.value,
    }


def _pending_transition_id(
    guide_command: GuideCommand,
    transition_id: str | None,
) -> str | None:
    if guide_command.command_type in {
        CommandType.CONFIRMATION,
        CommandType.DONE,
        CommandType.BLOCKED,
    }:
        return None
    if not guide_command.requires_user_action or guide_command.action_type == ActionType.OBSERVE_ONLY:
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


def _screen_bounds(request: AgentTurnRequest) -> Bounds | None:
    if request.screen_bounds:
        return request.screen_bounds
    if request.raw_goal and request.raw_goal.device_context.screen_bounds:
        return request.raw_goal.device_context.screen_bounds
    snapshot = request.vision_snapshot
    if snapshot and snapshot.screen_width and snapshot.screen_height:
        return (0, 0, snapshot.screen_width, snapshot.screen_height)
    return None


def _vision_package_name(request: AgentTurnRequest) -> str | None:
    if request.vision_snapshot and request.vision_snapshot.package_name:
        return request.vision_snapshot.package_name
    if request.raw_goal:
        return request.raw_goal.device_context.app_package
    first_node = next((node for node in request.accessibility_tree if node.package_name), None)
    return first_node.package_name if first_node else None


def _append_vision_nodes(
    nodes: tuple[AccessibilityNode, ...],
    analysis: VisionFallbackAnalysis,
    *,
    package_name: str | None,
) -> tuple[AccessibilityNode, ...]:
    if analysis.status != "used":
        return nodes
    vision_nodes = vision_elements_to_accessibility_nodes(
        analysis,
        package_name=package_name,
    )
    return nodes + vision_nodes


def _command_for_vision_analysis(
    session_id: str,
    analysis: VisionFallbackAnalysis,
    *,
    tree_was_empty: bool,
) -> GuideCommand | None:
    if analysis.status == "consent_required":
        return vision_fallback_command(
            session_id=session_id,
            message="화면 정보를 읽지 못했어요. 화면 이미지 분석을 허용하면 다음 안내를 이어갈게요.",
        )
    if tree_was_empty and analysis.status in {
        "not_requested",
        "not_configured",
        "provider_error",
        "no_signal",
    }:
        return vision_fallback_command(
            session_id=session_id,
            message="화면 정보를 읽지 못했어요. 접근성 정보나 동의된 화면 분석 정보가 필요해요.",
            command_type=CommandType.BLOCKED,
            safety_level=SafetyLevel.BLOCKED,
        )
    return None
