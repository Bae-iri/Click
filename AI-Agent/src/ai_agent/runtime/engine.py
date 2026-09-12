"""Runtime engine for the Click AI Agent MVP flow."""

from __future__ import annotations

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
from ai_agent.runtime.verification import verify_transition
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
        if match_result.fallback_reason and request.vision_snapshot:
            retry_nodes, vision_analysis = self._retry_with_vision_nodes(
                request=request,
                existing_nodes=accessibility_nodes,
                current_analysis=vision_analysis,
                graph=graph,
                previous_state_id=previous_state_id,
                excluded_node_ids=replan_decision.excluded_node_ids,
                screen_bounds=screen_bounds,
            )
            if retry_nodes:
                normalized_nodes = retry_nodes
                current_state_id = classify_screen_state(
                    normalized_nodes,
                    graph,
                    device_context=request.raw_goal.device_context if request.raw_goal else None,
                    previous_state_id=previous_state_id,
                )
                current_snapshot = build_screen_snapshot(current_state_id, normalized_nodes)
                effective_state_id = _effective_state_id(current_state_id, verification_result)
                match_result = match_next_ui(
                    effective_state_id,
                    normalized_nodes,
                    graph,
                    excluded_node_ids=replan_decision.excluded_node_ids,
                    similarity_scorer=self.similarity_scorer,
                )
        if match_result.fallback_reason and vision_analysis:
            vision_command = _command_for_vision_analysis(
                request.session_id,
                vision_analysis,
                tree_was_empty=not request.accessibility_tree,
            )
            if vision_command:
                guide_command = vision_command
            else:
                guide_command = build_guide_command(
                    session_id=request.session_id,
                    match_result=match_result,
                    graph=graph,
                    user_confirmation=request.user_confirmation,
                )
        else:
            guide_command = build_guide_command(
                session_id=request.session_id,
                match_result=match_result,
                graph=graph,
                user_confirmation=request.user_confirmation,
            )
        pending_transition_id = _pending_transition_id(
            guide_command,
            match_result.transition_id,
        )
        pending_confirmation_transition_id = _pending_confirmation_transition_id(
            guide_command.command_type,
            match_result.transition_id,
        )
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
            "graph_version": "mvp-01.v1",
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
    if not guide_command.requires_user_action:
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
