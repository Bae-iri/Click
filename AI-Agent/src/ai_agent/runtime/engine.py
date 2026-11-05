"""Runtime engine for the Click AI Agent MVP flow."""

from __future__ import annotations

from dataclasses import replace
from uuid import uuid4
from hashlib import sha256
import json

from ai_agent.runtime import diagnostics, observation_context
from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.goal_typing import type_goal
from ai_agent.runtime.guide import (
    build_guide_command,
    clarification_command,
    command_for_vision_analysis,
    recovery_command,
)
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.recovery import decide_recovery, manual_navigation_target, stale_observation_recovery
from ai_agent.runtime.session import InMemorySessionStore, SessionStore
from ai_agent.runtime.state_manager import build_screen_snapshot, register_attempt_outcome, is_failed_attempt
from ai_agent.runtime.tasks import TASK_GRAPHS
from ai_agent.runtime.types import (
    AccessibilityNode,
    ActionType,
    AgentTurnRequest,
    AgentTurnResponse,
    Bounds,
    CommandType,
    GuideCommand,
    NormalizedNode,
    SafetyLevel,
    TaskGraph,
    TypedGoal,
    VerificationResult,
    VerificationStatus,
)
from ai_agent.runtime.validation import ContractViolationError, validate_agent_turn_request
from ai_agent.runtime.verification import correlate_action, verify_transition, font_scale, applied_value, valid_slider_value
from ai_agent.runtime.progress import observed_path, checklist
from ai_agent.runtime.targeting import target_key
from ai_agent.runtime.similarity import default_similarity_scorer
from ai_agent.runtime.vision import (
    VisionFallback,
    VisionFallbackAnalysis,
    append_vision_nodes,
)
from ai_agent.telemetry.trace_logger import TraceLogger
from ai_agent.runtime.privacy import sanitize_request
from ai_agent.runtime.vision_cycles import decide_vision_cycle
from ai_agent.runtime.semantic_state import resolve_state
from ai_agent.runtime.ui_selection import select_ui_target


class ClickAgentEngine:
    def __init__(
        self,
        *,
        session_store: SessionStore | None = None,
        task_graphs: dict[str, TaskGraph] | None = None,
        similarity_scorer=None,
        vision_fallback: VisionFallback | None = None,
        trace_logger: TraceLogger | None = None,
        replay_recorder=None,
        state_resolver=None,
        screen_resolver=None,
        ui_selector=None,
    ) -> None:
        self.session_store = session_store or InMemorySessionStore()
        self.task_graphs = task_graphs or TASK_GRAPHS
        self.similarity_scorer = similarity_scorer or default_similarity_scorer()
        self.vision_fallback = vision_fallback or VisionFallback()
        self.trace_logger = trace_logger or TraceLogger()
        self.replay_recorder = replay_recorder
        self.state_resolver = state_resolver
        self.screen_resolver = screen_resolver
        # Backend configures the primary provider. A legacy auxiliary selector
        # can only be injected explicitly and never runs alongside the primary.
        self.ui_selector = None if screen_resolver else ui_selector

    @property
    def traces(self):
        return self.trace_logger.traces

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        response = self._handle_turn(request)
        if self.replay_recorder is not None:
            try:
                self.replay_recorder.record(request, response)
            except Exception:
                # Optional diagnostics must not terminate the user's runtime.
                pass
        return response

    def _handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        original_screen = request.accessibility_tree
        request = sanitize_request(request)
        existing_session = self.session_store.get(request.session_id)
        validate_agent_turn_request(request, has_session=existing_session is not None)
        previous_snapshot = existing_session.last_snapshot if existing_session else None
        if previous_snapshot and request.observation_id and (
                request.observation_id == previous_snapshot.observation_id
                or (request.captured_at_ms is not None and previous_snapshot.captured_at_ms is not None
                    and request.captured_at_ms <= previous_snapshot.captured_at_ms)):
            return AgentTurnResponse(request_id=request.request_id, session_id=request.session_id,
                task_id=existing_session.task_id, typed_goal=existing_session.typed_goal,
                current_state_id=existing_session.current_state_id,
                verification_result=VerificationResult(VerificationStatus.WAITING_FOR_OBSERVATION,
                    existing_session.current_state_id, evidence='stale_observation'),
                guide_command=GuideCommand(guide_command_id='guide_' + uuid4().hex,
                    command_type=CommandType.HIGHLIGHT, action_type=ActionType.OBSERVE_ONLY,
                    message='새 화면 정보를 확인하고 있어요.', safety_level=SafetyLevel.LOW_RISK,
                    requires_user_action=False, render_policy={'fallback_action': {'type': 'request_fresh_observation'},
                        'recovery': stale_observation_recovery()}),
                debug={'checklist': list(existing_session.progress_steps), 'recovery': stale_observation_recovery()})

        from ai_agent.runtime.navigation import apply_navigation
        navigated_session = apply_navigation(self.session_store, request, existing_session)
        if navigated_session is not existing_session:
            previous_snapshot = None
            existing_session = navigated_session

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
                    "observation": diagnostics.raw_observation_debug(request),
                    "agent_chain": diagnostics.agent_chain_debug(
                        typed_goal=typed_goal,
                        vision_analysis=None,
                        match_result=None,
                        guide_command=guide_command,
                    ),
                },
            )

        graph = self._task_graph_for(typed_goal)
        vocabulary_key = graph.definition.required_slots.get("setting", "")
        if self.screen_resolver:
            from ai_agent.runtime.screen_privacy import screen_status, filter_nodes, MANUAL_MESSAGE
            context = request.device_context or (request.raw_goal.device_context if request.raw_goal else None)
            package = (context.foreground_app or context.app_package) if context else None
            privacy_status = screen_status(original_screen, vocabulary_key, package)
            if privacy_status:
                return AgentTurnResponse(request_id=request.request_id, session_id=request.session_id,
                    task_id=typed_goal.task_id, typed_goal=typed_goal,
                    current_state_id=existing_session.current_state_id if existing_session else "S0_UNKNOWN",
                    guide_command=GuideCommand(guide_command_id='guide_' + uuid4().hex,
                        command_type=CommandType.BLOCKED, action_type=ActionType.OBSERVE_ONLY,
                        message=MANUAL_MESSAGE, safety_level=SafetyLevel.BLOCKED,
                        requires_user_action=False, render_policy={"privacy_blocked": True,
                            "manual_only": True}),
                    debug={"ui_selection": {"provider": "jev", "mode": "primary", "called": False,
                                             "status": privacy_status}})
            request = replace(request, accessibility_tree=filter_nodes(original_screen, vocabulary_key))
        # First pass removes all unapproved text before goal typing. Narrow the
        # verified payload to this goal before classification, matching or traces.
        if request.vision_snapshot and request.vision_snapshot.privacy_status == "verified":
            request = sanitize_request(request, vision_vocabulary_key=vocabulary_key)
        previous_state_id = (
            existing_session.current_state_id
            if existing_session
            else request.previous_state_id
        )
        screen_bounds = observation_context.screen_bounds(request)
        accessibility_nodes = request.accessibility_tree
        vision_analysis: VisionFallbackAnalysis | None = None
        if request.vision_snapshot and request.vision_snapshot.privacy_status == "masking_failed":
            vision_analysis = VisionFallbackAnalysis(status="masking_failed",
                reason="privacy_validation_failed", provider="local")
        if request.vision_failure:
            vision_analysis = VisionFallbackAnalysis(status="provider_error",
                reason=request.vision_failure, provider="on_device_ocr")
        if not accessibility_nodes and request.vision_snapshot:
            vision_analysis = self.vision_fallback.analyze(
                request.vision_snapshot,
                reason="empty_accessibility_tree",
            )
            accessibility_nodes = append_vision_nodes(
                accessibility_nodes,
                vision_analysis,
                package_name=observation_context.vision_package_name(request),
            )
        elif not accessibility_nodes and not request.vision_failure and not (existing_session and existing_session.recovery_state.get("waiting_for_user")):
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
        initial_evidence = {}
        current_state_id = classify_screen_state(
            normalized_nodes,
            graph,
            device_context=observation_context.device_context(request),
            previous_state_id=previous_state_id,
            evidence=initial_evidence,
        )
        # Finish observation acquisition before verification/accounting, including vision.
        previous_exclusions = existing_session.failed_target_keys if existing_session else {}
        preliminary = match_next_ui(current_state_id, normalized_nodes, graph,
            excluded_target_keys=previous_exclusions, similarity_scorer=self.similarity_scorer)
        if (preliminary.fallback_reason and not previous_exclusions
                and vision_analysis is None and not request.vision_snapshot
                and not (existing_session and existing_session.recovery_state.get("waiting_for_user"))
                and not manual_navigation_target(graph, preliminary, normalized_nodes)
                and preliminary.fallback_reason != "ambiguous_target" and not initial_evidence.get("ambiguous")
                and not initial_evidence.get("settings_subpage")
                and (not normalized_nodes or (existing_session
                     and existing_session.recovery_state.get("attempts", 0) >= 1
                     and not existing_session.recovery_state.get("waiting_for_user")))
                and (not normalized_nodes or preliminary.transition_id != "S0_TO_S1_OPEN_SETTINGS"
                     or observation_context.vision_package_name(request) in {"com.android.settings", "com.samsung.android.settings"})):
            vision_analysis = VisionFallbackAnalysis(status="not_requested",
                reason="no_matching_target_node", provider="on_device_ocr")
        if preliminary.fallback_reason and request.vision_snapshot:
            retry_nodes, vision_analysis = self._retry_with_vision_nodes(
                request=request, existing_nodes=accessibility_nodes,
                current_analysis=vision_analysis, graph=graph, previous_state_id=previous_state_id,
                excluded_node_ids=(), screen_bounds=screen_bounds)
            if retry_nodes:
                normalized_nodes = retry_nodes
        state_evidence = {}
        current_state_id = classify_screen_state(normalized_nodes, graph,
            device_context=observation_context.device_context(request),
            previous_state_id=previous_state_id, evidence=state_evidence)
        if current_state_id == 'S0_UNKNOWN' and not self.screen_resolver:
            current_state_id = resolve_state(normalized_nodes, graph, self.state_resolver,
                previous_state_id=previous_state_id, evidence=state_evidence,
                excluded_target_keys=previous_exclusions) or current_state_id
        current_snapshot = build_screen_snapshot(current_state_id, normalized_nodes,
            observation_id=request.observation_id or request.request_id,
            captured_at_ms=request.captured_at_ms, applied_settings=request.applied_settings)
        previous_transition = next((t for t in graph.transitions if existing_session
            and t.transition_id == existing_session.pending_transition_id), None)
        attempt, processed_events = correlate_action(existing_session, request, previous_transition)
        if (attempt and not attempt.get('target_node_id', '').startswith('vision:')
                and not normalized_nodes and request.captured_at_ms is not None):
            attempt.setdefault('observation_gap_started_at_ms', request.captured_at_ms)
        elif attempt:
            attempt.pop('observation_gap_started_at_ms', None)
        verified_attempt = attempt
        verification_result = verify_transition(
            graph=graph, previous_state_id=previous_state_id,
            pending_transition_id=existing_session.pending_transition_id if existing_session else None,
            current_state_id=current_state_id,
            previous_snapshot=existing_session.last_snapshot if existing_session else None,
            current_snapshot=current_snapshot, attempt=attempt, nodes=normalized_nodes)
        # In primary mode Jev chooses across graph steps, before final transition
        # verification. Rule classification supplies evidence, never the final UI.
        primary_match = None
        primary_wait = False
        ui_selection = {"provider": getattr(self.ui_selector, "provider", "rules"),
                        "status": "skipped", "called": False}
        if self.screen_resolver:
            ui_selection = {"provider": "jev", "mode": "primary", "status": "skipped", "called": False}
            same_screen = bool(previous_snapshot and
                previous_snapshot.fingerprint == current_snapshot.fingerprint)
            repeated_failure = bool(existing_session and existing_session.recovery_state.get(
                "jev_wait_fingerprint") == current_snapshot.fingerprint)
            action_at = attempt.get('action_at_ms') if attempt else None
            settling = bool(action_at is not None and request.captured_at_ms is not None
                and request.captured_at_ms - action_at < graph.policy.settle_ms)
            pending_result = bool(attempt and attempt.get('event_key') and verification_result
                and verification_result.status in {VerificationStatus.WAITING_FOR_OBSERVATION,
                    VerificationStatus.UNKNOWN, VerificationStatus.NO_CHANGE, VerificationStatus.WRONG_STATE})
            primary_wait = bool(repeated_failure or settling or (same_screen and pending_result))
            already_complete = bool((existing_session and existing_session.task_completed)
                or (verification_result and verification_result.task_completed))
            if primary_wait:
                ui_selection['status'] = 'waiting'
            if (not already_complete and not primary_wait and request.user_confirmation != 'rejected'
                    and (not existing_session or existing_session.total_failed_attempts < graph.policy.max_failed_attempts)
                    and not (vision_analysis and vision_analysis.status == 'masking_failed')):
                primary_match = self.screen_resolver.resolve(nodes=normalized_nodes, graph=graph,
                    goal=typed_goal, raw_nodes=accessibility_nodes, session=existing_session,
                    verification=verification_result, excluded_target_keys=previous_exclusions,
                    screen_bounds=screen_bounds, attempt=attempt)
                ui_selection.update(getattr(self.screen_resolver, 'last_diagnostics', {}))
                primary_wait = primary_match is None
                if primary_match:
                    current_state_id = primary_match.state_id
                    current_snapshot = replace(current_snapshot, state_id=current_state_id)
                    state_evidence = {'state_score': primary_match.confidence, 'ambiguous': False,
                        'candidates': [{'state_id': current_state_id, 'score': primary_match.confidence}]}
                    # Re-evaluate navigation progress using the actual model-selected
                    # screen. Numeric completion still requires correlated before/after evidence.
                    verification_result = verify_transition(graph=graph, previous_state_id=previous_state_id,
                        pending_transition_id=existing_session.pending_transition_id if existing_session else None,
                        current_state_id=current_state_id,
                        previous_snapshot=existing_session.last_snapshot if existing_session else None,
                        current_snapshot=current_snapshot, attempt=attempt, nodes=normalized_nodes)
            elif not already_complete:
                primary_wait = True
        transition_attempts, failed_node_ids = register_attempt_outcome(
            existing_session,
            pending_transition_id=existing_session.pending_transition_id if existing_session else None,
            verification_result=verification_result, retry_limit=graph.policy.retry_limit, attempt=attempt)
        failed_target_keys = dict(previous_exclusions)
        failed_attempt = is_failed_attempt(verification_result, attempt,
            previous_transition.transition_id if previous_transition else None)
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
        match_result = primary_match if primary_match and not task_completed else match_next_ui(
            effective_state_id, normalized_nodes, graph,
            excluded_target_keys=failed_target_keys, similarity_scorer=self.similarity_scorer)
        awaiting_observation = bool(attempt and verification_result
            and (attempt.get('event_key') or verification_result.evidence == 'transient_screen_unavailable')
            and verification_result.status in {VerificationStatus.WAITING_FOR_OBSERVATION, VerificationStatus.UNKNOWN})
        action_time = attempt.get('action_at_ms') if attempt else None
        within_settling_interval = bool(awaiting_observation and action_time is not None
            and request.captured_at_ms is not None
            and 0 <= request.captured_at_ms - action_time < graph.policy.settle_ms)
        if (not self.screen_resolver and not task_completed and not awaiting_observation
                and request.user_confirmation != "rejected"
                and total_failed_attempts < graph.policy.max_failed_attempts
                and not state_evidence.get("settings_subpage")
                and not (vision_analysis and vision_analysis.status == "masking_failed")):
            match_result, ui_selection = select_ui_target(match_result, normalized_nodes, graph,
                self.ui_selector, excluded_target_keys=failed_target_keys, screen_bounds=screen_bounds)
        cycle = decide_vision_cycle(request=request, session=existing_session, graph=graph,
            match=match_result, analysis=vision_analysis, completed=task_completed)
        awaiting_vision_consent = bool(cycle.command and cycle.command.command_type == CommandType.CONFIRMATION)
        recovery_plan = decide_recovery(graph=graph, session=existing_session, snapshot=current_snapshot,
            match=match_result, nodes=normalized_nodes, verification=verification_result,
            failed_node_ids=failed_node_ids, total_failed_attempts=total_failed_attempts,
            state_evidence=state_evidence, awaiting_vision_consent=awaiting_vision_consent,
            vision_failed=bool(vision_analysis and (vision_analysis.status in {"no_signal", "provider_error", "not_configured"}
                or (vision_analysis.status == "used" and match_result.fallback_reason))),
            within_settling_interval=within_settling_interval, completed=task_completed,
            user_retry=bool(request.user_action_event and request.user_action_event.action_type.value == "manual_override"))
        if self.screen_resolver:
            if primary_wait and (failed_attempt or (existing_session and
                    existing_session.recovery_state.get('jev_wait_fingerprint') == current_snapshot.fingerprint)):
                recovery_plan.state['jev_wait_fingerprint'] = current_snapshot.fingerprint
            else:
                recovery_plan.state.pop('jev_wait_fingerprint', None)
        recovery_count = recovery_plan.state.get("attempts", 0)
        replan_decision = recovery_plan.replan
        matched_target = next((node for node in normalized_nodes if node.node_id == match_result.target_node_id), None)
        current_transition = next((t for t in graph.transitions if t.transition_id == match_result.transition_id), None)
        limit_reached = bool(not task_completed and matched_target
            and valid_slider_value(matched_target.value, matched_target.range_min, matched_target.range_max)
            and current_transition and current_transition.completes_task and (
                (current_transition.verification_predicate == 'target_value_increased'
                 and matched_target.range_max is not None and matched_target.value >= matched_target.range_max)
                or (current_transition.verification_predicate == 'target_value_decreased'
                    and matched_target.range_min is not None and matched_target.value <= matched_target.range_min)))
        unverified_change_at_limit = bool(limit_reached and attempt
            and attempt.get('baseline_value') is not None
            and matched_target.value != attempt['baseline_value'])
        same_binding = bool(attempt and match_result.target_key == attempt.get('target_key')
            and match_result.transition_id == attempt.get('transition_id')
            and match_result.target_node_id == attempt.get('target_node_id')
            and matched_target and matched_target.window_id == attempt.get('target_window_id')
            and match_result.target_bounds == tuple(attempt.get('target_bounds', ()))
            and (attempt.get('event_key') or (
                matched_target.value == attempt.get('baseline_value')
                and (not current_transition or current_transition.verification_predicate == 'state_reached'
                     or applied_value(current_snapshot.applied_settings, graph.definition.required_slots.get('setting', 'font_size'))
                        == attempt.get('baseline_setting_value', attempt.get('baseline_font_scale'))))))
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
            transition = next((item for item in graph.transitions
                               if item.transition_id == match_result.transition_id), None)
            vision_command = command_for_vision_analysis(request.session_id, vision_analysis,
                manual_instruction=transition.guide_message if transition else None)
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
        if (not self.screen_resolver and recovery_plan.decision != "continue" and (not awaiting_vision_consent or recovery_plan.next_action == "go_back") and not task_completed
                and guide_command.safety_level != SafetyLevel.BLOCKED
                and recovery_plan.next_action != "open_settings"):
            guide_command = recovery_command(request.session_id, recovery_plan)
            if recovery_plan.reason_code == "ACTION_EVENT_MISSING" and attempt:
                keep_attempt = True
            if recovery_plan.decision == "handoff":
                keep_attempt = False
        if not self.screen_resolver and recovery_plan.next_action == "go_back":
            keep_attempt = False
        if request.user_confirmation == 'rejected' and not cycle.command:
            guide_command = build_guide_command(session_id=request.session_id,
                match_result=match_result, graph=graph, user_confirmation='rejected')
            keep_attempt = False
        if vision_analysis and vision_analysis.status == "masking_failed":
            guide_command = command_for_vision_analysis(request.session_id, vision_analysis)
            keep_attempt = False
        if limit_reached:
            guide_command = GuideCommand(guide_command_id='guide_' + uuid4().hex,
                command_type=CommandType.HIGHLIGHT, action_type=ActionType.OBSERVE_ONLY,
                target_node_id=matched_target.node_id, target_bounds=matched_target.bounds,
                target_label=matched_target.label,
                message=('표시한 슬라이더가 이미 가장 큰 글자 크기예요. 더 크게 조절할 수 없어요.'
                    if graph.definition.required_slots.get('direction') == 'increase' else
                    '표시한 슬라이더가 이미 가장 작은 글자 크기예요. 더 작게 조절할 수 없어요.')
                    if graph.definition.required_slots.get('setting') == 'font_size' else
                    ('이미 조절 가능한 최댓값이에요. 더 높일 수 없어요.'
                    if graph.definition.required_slots.get('direction') == 'increase' else
                    '이미 조절 가능한 최솟값이에요. 더 낮출 수 없어요.'),
                safety_level=SafetyLevel.LOW_RISK, requires_user_action=False,
                render_policy={'avoid_covering_target': True, 'adjustment_limit': True})
            keep_attempt = False
        if recovery_plan.decision != "continue" and guide_command.command_type != CommandType.BLOCKED:
            guide_command = replace(guide_command, render_policy={**guide_command.render_policy,
                "recovery": recovery_plan.to_dict()})
        if "vision_fallback" in guide_command.render_policy:
            guide_command.render_policy["vision_fallback"]["vocabulary_key"] = vocabulary_key
        if (cycle.command and not primary_match and not awaiting_observation and not task_completed):
            guide_command = cycle.command
            guide_command.render_policy["vision_cycle"] = cycle.state
            if "vision_fallback" in guide_command.render_policy:
                guide_command.render_policy["vision_fallback"]["vocabulary_key"] = vocabulary_key
                guide_command.render_policy["recovery"] = recovery_plan.to_dict()
            keep_attempt = False
        if (self.screen_resolver and primary_wait and not task_completed
                and request.user_confirmation != 'rejected'
                and not awaiting_vision_consent
                and not (cycle.command and not normalized_nodes)
                and not (vision_analysis and vision_analysis.status == 'masking_failed')):
            # An abstention must retain why fresh evidence is needed. Otherwise
            # Android cannot distinguish an off-screen menu from a pending result
            # and keeps rereading the same page instead of bounded list search.
            if recovery_plan.decision != 'continue' and recovery_plan.next_action in {
                    'manual_navigation', 'user_assistance', 'reobserve', 'reselect_graph'}:
                guide_command = recovery_command(request.session_id, recovery_plan)
            else:
                guide_command = GuideCommand(guide_command_id='guide_' + uuid4().hex,
                    command_type=CommandType.HIGHLIGHT, action_type=ActionType.OBSERVE_ONLY,
                    message='안내를 준비하고 있어요.',
                    safety_level=SafetyLevel.LOW_RISK, requires_user_action=False,
                    render_policy={'fallback_action': {'type': 'request_fresh_observation'}})
            guide_command = replace(guide_command, render_policy={**guide_command.render_policy,
                'ui_selector': 'jev', 'revalidate_before_action': True})
            keep_attempt = bool(attempt and not failed_attempt)
        elif self.screen_resolver:
            guide_command = replace(guide_command, render_policy={**guide_command.render_policy,
                'ui_selector': 'jev', 'revalidate_before_action': True})
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
                    'target_resource_id': target.resource_id,
                    'target_selection_method': match_result.match_method,
                    'target_bounds': target.bounds, 'baseline_value': target.value,
                    'baseline_range_min': target.range_min, 'baseline_range_max': target.range_max,
                    'baseline_observation_id': request.observation_id,
                    'baseline_captured_at_ms': current_snapshot.captured_at_ms,
                    'setting': graph.definition.required_slots.get('setting', 'font_size'),
                    'baseline_setting_value': applied_value(current_snapshot.applied_settings, graph.definition.required_slots.get('setting', 'font_size')),
                    'baseline_font_scale': font_scale(current_snapshot.applied_settings),
                    'episode_id': request.episode_id,
                }
        pending_confirmation_transition_id = _pending_confirmation_transition_id(
            guide_command.command_type, match_result.transition_id)
        guide_command = replace(guide_command, render_policy={**guide_command.render_policy,
            'runtime_strategy': replan_decision.strategy,
            'attempt_id': attempt['attempt_id'] if attempt else None,
            'step_id': pending_transition_id})
        observation_debug = diagnostics.observation_debug(
            request=request,
            normalized_nodes=normalized_nodes,
            current_state_id=current_state_id,
            effective_state_id=effective_state_id,
            vision_analysis=vision_analysis,
        )
        fallback_debug = diagnostics.fallback_debug(
            match_result=match_result,
            guide_command=guide_command,
            vision_analysis=vision_analysis,
            verification_result=verification_result,
        )
        agent_chain_debug = diagnostics.agent_chain_debug(
            typed_goal=typed_goal,
            vision_analysis=vision_analysis,
            match_result=match_result,
            guide_command=guide_command,
        )

        technical = dict(existing_session.technical_outcome) if existing_session else {}
        if verification_result and verification_result.task_completed:
            technical = {'status': 'CHANGED', 'reason': verification_result.evidence,
                'verified_at_ms': request.captured_at_ms, 'transition_id': previous_transition.transition_id,
                'attempt_id': verified_attempt['attempt_id'], 'guide_command_id': verified_attempt['guide_command_id'],
                'target_key': verified_attempt['target_key'], 'event_key': verified_attempt['event_key'],
                'baseline_value': verified_attempt['baseline_value'],
                'baseline_observation_id': verified_attempt.get('baseline_observation_id'),
                'baseline_captured_at_ms': verified_attempt.get('baseline_captured_at_ms'),
                'action_at_ms': verified_attempt.get('action_at_ms'),
                'episode_id': verified_attempt.get('episode_id'),
                'after_value': next((n.value for n in normalized_nodes if target_key(n) == verified_attempt['target_key']), None),
                'setting': graph.definition.required_slots.get('setting', 'font_size'),
                'baseline_setting_value': verified_attempt.get('baseline_setting_value', verified_attempt.get('baseline_font_scale')),
                'setting_value': applied_value(request.applied_settings, graph.definition.required_slots.get('setting', 'font_size')),
                'baseline_font_scale': verified_attempt.get('baseline_font_scale'),
                'font_scale': font_scale(request.applied_settings), 'observation_id': request.observation_id}
            verified_node = next((n for n in normalized_nodes if target_key(n) == verified_attempt['target_key']), None)
            if verified_node:
                direction = graph.definition.required_slots.get('direction')
                at_bound = ((direction == 'increase' and verified_node.range_max is not None
                             and verified_node.value >= verified_node.range_max)
                            or (direction == 'decrease' and verified_node.range_min is not None
                                and verified_node.value <= verified_node.range_min))
                if at_bound:
                    technical['limit_direction'] = direction
        elif limit_reached:
            technical = {'status': 'UNVERIFIABLE' if unverified_change_at_limit else 'NOT_ACHIEVED',
                'reason': 'unverified_change_at_limit' if unverified_change_at_limit else 'limit_reached',
                'verified_at_ms': request.captured_at_ms,
                'limit_direction': graph.definition.required_slots.get('direction'),
                'value': matched_target.value, 'min': matched_target.range_min, 'max': matched_target.range_max}
        elif verification_result and verification_result.status == VerificationStatus.NO_CHANGE:
            technical = {'status': 'NOT_ACHIEVED', 'reason': verification_result.evidence,
                         'verified_at_ms': request.captured_at_ms}
        elif not task_completed and guide_command.command_type == CommandType.BLOCKED:
            technical = {'status': 'UNVERIFIABLE', 'reason': verification_result.evidence
                if verification_result else 'observation_unavailable', 'verified_at_ms': request.captured_at_ms}
        path = observed_path(existing_session.observed_path if existing_session else (), current_state_id, graph)
        if (existing_session and existing_session.last_snapshot and
                (current_snapshot.observation_id == existing_session.last_snapshot.observation_id
                 or (request.captured_at_ms is not None and existing_session.last_snapshot.captured_at_ms is not None
                     and request.captured_at_ms <= existing_session.last_snapshot.captured_at_ms))):
            path = existing_session.observed_path
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
            total_failed_attempts=total_failed_attempts, task_completed=task_completed,
            observed_path=path, technical_outcome=technical,
            recovery_state=recovery_plan.state, state_candidates=tuple(state_evidence.get("candidates", ())),
            vision_state=cycle.state)
        if existing_session:
            self.session_store.update(request.session_id, **session_changes)
        else:
            self.session_store.start(session_id=request.session_id, **session_changes)

        progress_steps = checklist(graph, self.session_store.get(request.session_id), match_result)
        self.session_store.update(request.session_id, progress_steps=tuple(progress_steps))
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
                "state_classification": state_evidence,
                "recovery": recovery_plan.to_dict(),
                "task_completed": task_completed,
                "checklist": progress_steps,
                "recovery_count": recovery_count,
                "failed_target_keys": failed_target_keys,
                "correlation": {
                    "observation_id": current_snapshot.observation_id,
                    "attempt_id": verification_result.attempt_id if verification_result else None,
                    "next_attempt_id": attempt['attempt_id'] if attempt else None,
                },
                "session_stateful": True,
                "similarity_provider": getattr(self.similarity_scorer, "provider", "custom"),
                "ui_selection": ui_selection,
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
            "recovery": recovery_plan.to_dict(),
            "state_classification": state_evidence,
            "ui_selection": ui_selection,
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
        if request.typed_goal:
            return request.typed_goal
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
        augmented_nodes = append_vision_nodes(
            existing_nodes,
            vision_analysis,
            package_name=observation_context.vision_package_name(request),
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
            device_context=observation_context.device_context(request),
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
