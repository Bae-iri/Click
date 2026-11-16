"""Coupang guidance using Agent1 observations, session state and action evidence.

Item identity and private entry stay with the user. A model choice never proves
a transaction, and a manual result never becomes technical success.
"""
from dataclasses import replace
from hashlib import sha256
from uuid import uuid4

from ai_agent.task_graph.commerce import PACKAGES
from ai_agent.runtime.commerce_privacy import filter_observation
from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.observation_context import screen_bounds as request_bounds
from ai_agent.runtime.primary import selection_binding
from ai_agent.runtime.state_manager import build_screen_snapshot
from ai_agent.runtime.targeting import target_key
from ai_agent.runtime.types import (
    ActionType, AgentTurnResponse, CommandType, GuideCommand, MatchResult, SafetyLevel,
    VerificationResult, VerificationStatus as Status,
)
from ai_agent.runtime.verification import correlate_action


def target_visible(nodes, graph, *, screen_bounds=None):
    if not screen_bounds:
        return False
    nodes = tuple(n for n in nodes if n.enabled and n.window_id is not None and n.bounds
        and screen_bounds[0] <= n.bounds[0] < n.bounds[2] <= screen_bounds[2]
        and screen_bounds[1] <= n.bounds[1] < n.bounds[3] <= screen_bounds[3])
    titles = {normalize_text(t) for group in graph.states['C_TARGET'].required_keyword_groups for t in group}
    # A menu row with the same label is never a destination page.
    if any(n.package_name in PACKAGES and n.structure_role == 'screen_text'
            and n.position_hint in {'top', 'unknown'} and n.label in titles for n in nodes):
        return True
    groups = [v for k, v in graph.synonyms.items() if k.startswith('commerce_controls_')]
    return bool(groups) and all(any(n.package_name in PACKAGES and n.actionable
        and any(part in {normalize_text(t) for t in group} for part in (n.label, *n.label_parts))
        for n in nodes) for group in groups)


def bindings_for(nodes, graph, *, screen_bounds=None):
    if (not screen_bounds or len({n.node_id for n in nodes}) != len(nodes)
            or len({n.window_id for n in nodes}) > 1):
        return {}
    ready = target_visible(nodes, graph, screen_bounds=screen_bounds)
    edge = next((t for t in graph.transitions if t.transition_id == ('CP_ACTION' if ready else 'CP_NAVIGATE')), None)
    if edge is None:
        return {}
    words = {normalize_text(t) for t in edge.target_keywords}
    candidates = []
    for node in nodes:
        bounds = node.bounds
        if (node.package_name not in PACKAGES or node.window_id is None or not node.enabled or not node.actionable
                or not node.clickable or node.source_confidence is not None or node.checkable
                or node.role in {'slider', 'toggle', 'radio', 'text_input', 'text_field'}
                or not bounds or not (screen_bounds[0] <= bounds[0] < bounds[2] <= screen_bounds[2]
                    and screen_bounds[1] <= bounds[1] < bounds[3] <= screen_bounds[3])):
            continue
        if any(part in words for part in (node.label, *node.label_parts)):
            candidates.append(node)
    # Neither confidence nor row order disambiguates identical native controls.
    from collections import Counter
    identities = Counter(tuple(n.label_parts) or (n.label,) for n in candidates)
    candidates = [n for n in candidates if identities[tuple(n.label_parts) or (n.label,)] == 1]
    return {f'candidate_{i}': (edge, n) for i, n in enumerate(candidates)}


def _match(edge, node, confidence=1.0, method='commerce_exact'):
    return MatchResult(edge.from_state, edge.to_state, edge.action_type, node.node_id,
        node.label, node.bounds, method, confidence, transition_id=edge.transition_id, target_key=target_key(node))


def handle_turn(engine, request, graph, goal, session, original_screen):
    context = request.device_context or (request.raw_goal.device_context if request.raw_goal else None)
    package = (context.foreground_app or context.app_package) if context else None
    safe_nodes, assessment = filter_observation(original_screen, package)
    bounds = request_bounds(request)
    nodes = normalize_accessibility_tree(safe_nodes, screen_bounds=bounds, active_window_id=request.active_window_id)
    ready = target_visible(nodes, graph, screen_bounds=bounds)
    state = 'C_TARGET' if ready else 'C_NAVIGATE' if any(n.package_name in PACKAGES for n in nodes) else 'S0_UNKNOWN'
    snapshot = build_screen_snapshot(state, nodes, observation_id=request.observation_id,
        captured_at_ms=request.captured_at_ms)
    mode = graph.synonyms['commerce_mode'][0]
    instruction = graph.synonyms['commerce_instructions'][-1]
    navigation_instruction = graph.synonyms['commerce_instructions'][0]
    guide_id = 'guide_' + uuid4().hex
    diagnostic = {'provider': 'jev' if engine.screen_resolver else 'rules', 'mode': 'primary',
        'called': False, 'status': 'skipped'}
    technical = dict(session.technical_outcome) if session else {}
    technical.setdefault('status', 'UNVERIFIABLE')
    technical.setdefault('reason', 'commerce_observation_required')
    technical['setting'] = 'commerce'
    technical['goal_type'] = graph.definition.goal_type
    processed = session.processed_event_keys if session else ()
    attempt = dict(session.pending_attempt) if session and session.pending_attempt else None
    selection = None
    recovery = dict(session.recovery_state) if session else {}
    path = list(session.observed_path) if session else []
    transition_attempts = dict(session.transition_attempts) if session else {}
    completed, verification, match = False, None, None

    def observe(message, *, manual=False, result=False, policy=None):
        return GuideCommand(guide_id, CommandType.HIGHLIGHT, ActionType.OBSERVE_ONLY, message,
            SafetyLevel.LOW_RISK, False, render_policy={'avoid_covering_target': False,
                'manual_only': manual, 'allow_result_check': result, **(policy or {})})

    if request.user_confirmation == 'rejected':
        guide = GuideCommand(guide_id, CommandType.BLOCKED, ActionType.OBSERVE_ONLY,
            '요청을 중단했어요.', SafetyLevel.BLOCKED, False)
        attempt = None
    elif assessment.status:
        diagnostic.update(status=assessment.status, observation_reason=assessment.reason)
        guide = observe('쿠팡 화면으로 돌아가 주세요. 쿠팡에서 이어서 안내할게요.', manual=True)
        attempt = None
    elif session and session.task_completed:
        completed = True
        state = 'C_DONE'
        guide = GuideCommand(guide_id, CommandType.DONE, ActionType.OBSERVE_ONLY,
            '확인한 쿠팡 작업 결과를 유지하고 있어요.', SafetyLevel.LOW_RISK, False)
    else:
        if attempt and session:
            transition = next(t for t in graph.transitions if t.transition_id == attempt['transition_id'])
            attempt, processed = correlate_action(session, request, transition)
        success_labels = {normalize_text(t) for t in graph.synonyms.get('commerce_success', ())}
        effect = any(n.package_name in PACKAGES and n.label in success_labels for n in nodes)
        action_at = attempt.get('action_at_ms') if attempt else None
        observed_after = bool(attempt and attempt.get('event_key') and request.observation_id
            and request.observation_id != attempt.get('baseline_observation_id')
            and request.captured_at_ms is not None and action_at is not None
            and request.captured_at_ms >= action_at + graph.policy.settle_ms)
        navigation = next(t for t in graph.transitions if t.transition_id == 'CP_NAVIGATE')
        destination_index = attempt.get('navigation_target_index') if attempt else None
        expected_title = (normalize_text(navigation.target_keywords[destination_index]).replace(' ', '')
            if type(destination_index) is int and 0 <= destination_index < len(navigation.target_keywords) else None)
        destination_seen = ready or bool(expected_title and any(n.structure_role == 'screen_text'
            and n.position_hint in {'top', 'unknown'} and n.label.replace(' ', '') == expected_title for n in nodes))
        navigation_changed = bool(attempt and attempt['transition_id'] == 'CP_NAVIGATE'
            and observed_after and destination_seen and session and session.last_snapshot
            and session.last_snapshot.fingerprint != snapshot.fingerprint)
        if navigation_changed:
            verification = VerificationResult(Status.SUCCESS, state, state, attempt_id=attempt['attempt_id'],
                count_attempt=True, evidence='observed_progress')
            transition_attempts['CP_NAVIGATE:observed'] = transition_attempts.get('CP_NAVIGATE:observed', 0) + 1
        if (attempt and attempt['transition_id'] == 'CP_ACTION' and observed_after
                and effect and not attempt.get('baseline_effect')):
            diagnostic.update(attempt.get('selection_diagnostics', {}), called=False, retained=True)
            match = MatchResult('C_TARGET', 'C_VERIFY', ActionType.TAP, attempt['target_node_id'], None,
                tuple(attempt['target_bounds']), attempt.get('target_selection_method', 'commerce_exact'),
                diagnostic.get('confidence', 1.0), transition_id='CP_ACTION', target_key=attempt['target_key'])
            completed, state = True, 'C_DONE'
            verification = VerificationResult(Status.SUCCESS, state, task_completed=True,
                attempt_id=attempt['attempt_id'], count_attempt=True, evidence='commerce_effect_observed')
            technical.update(status='CHANGED', reason='commerce_effect_observed', verified_at_ms=request.captured_at_ms)
            guide = GuideCommand(guide_id, CommandType.DONE, ActionType.OBSERVE_ONLY,
                '안내한 조작 뒤 반영된 결과를 확인했어요.', SafetyLevel.LOW_RISK, False)
            attempt = None
        elif attempt and attempt['transition_id'] == 'CP_ACTION' and attempt.get('event_key'):
            diagnostic.update(attempt.get('selection_diagnostics', {}), called=False, retained=True)
            # Never repeat a purchase/cart action while its effect is unknown.
            elapsed = (request.captured_at_ms - action_at) if request.captured_at_ms is not None and action_at is not None else 0
            timed_out = elapsed >= graph.policy.observation_timeout_ms
            verification = VerificationResult(Status.UNKNOWN if timed_out else Status.WAITING_FOR_OBSERVATION,
                state, attempt_id=attempt['attempt_id'], evidence='commerce_result_unobserved')
            technical.update(status='UNVERIFIABLE', reason='commerce_result_unobserved')
            guide = observe('조작 결과를 아직 확인하지 못했어요. 쿠팡에서 반영 여부를 직접 확인한 뒤 결과 확인을 눌러주세요.'
                if timed_out else '조작 결과를 확인하고 있어요. 같은 버튼을 다시 누르지 말고 기다려 주세요.',
                manual=timed_out, result=timed_out, policy={'manual_handoff': True} if timed_out else
                    {'fallback_action': {'type': 'request_fresh_observation'}})
        elif ready and mode == 'navigation':
            completed, state = True, 'C_DONE'
            verification = VerificationResult(Status.DONE, state, task_completed=True, evidence='commerce_page_visible')
            technical.update(status='OPENED', reason='commerce_page_visible', verified_at_ms=request.captured_at_ms)
            guide = GuideCommand(guide_id, CommandType.DONE, ActionType.OBSERVE_ONLY,
                graph.states['C_TARGET'].name + ' 화면을 열었어요.', SafetyLevel.LOW_RISK, False)
            attempt = None
        elif ready and mode == 'assisted':
            technical.update(status='UNVERIFIABLE', reason='commerce_manual_result_required')
            guide = observe(instruction + ' 직접 진행한 뒤 결과 확인을 눌러주세요.', manual=True, result=True,
                policy={'manual_handoff': True})
            verification = verification or VerificationResult(Status.UNKNOWN, state, evidence='commerce_manual_result_required')
            attempt = None
        elif mode == 'action' and effect:
            technical.update(status='UNVERIFIABLE', reason='commerce_result_unobserved')
            guide = observe('반영 안내가 보이지만 안내한 조작과 연결하지 못했어요. 쿠팡에서 결과를 직접 확인해 주세요.',
                manual=True, result=True, policy={'manual_handoff': True})
            attempt = None
        elif state == 'S0_UNKNOWN':
            guide = observe('쿠팡 앱을 열어주세요. 앱이 없다면 설치한 뒤 다시 시도해 주세요.', manual=True,
                policy={'recovery_action': {'type': 'open_coupang', 'target_app': 'coupang',
                    'package_name': 'com.coupang.mobile', 'requires_user_activation': True}})
        elif (attempt and attempt['transition_id'] == 'CP_NAVIGATE' and attempt.get('event_key')
                and not navigation_changed):
            elapsed = (request.captured_at_ms - action_at) if request.captured_at_ms is not None and action_at is not None else 0
            timed_out = elapsed >= graph.policy.observation_timeout_ms
            verification = VerificationResult(Status.UNKNOWN if timed_out else Status.WAITING_FOR_OBSERVATION,
                state, attempt_id=attempt['attempt_id'], evidence='commerce_result_unobserved')
            guide = observe('화면 이동을 확인하지 못했어요. 직접 이동하거나 다시 시도해 주세요.' if timed_out else
                '화면 이동을 확인하고 있어요. 잠시 기다려 주세요.', manual=timed_out,
                policy={} if timed_out else {'fallback_action': {'type': 'request_fresh_observation'}})
        elif transition_attempts.get('CP_NAVIGATE:observed', 0) >= 30:
            guide = observe('안내 경로가 길어져 직접 이동이 필요해요. 원하시는 화면으로 이동해 주세요.', manual=True)
        else:
            bindings = bindings_for(nodes, graph, screen_bounds=bounds)
            previous_selection = (attempt or session.pending_selection) if session else None
            same = bool(session and session.last_snapshot and session.last_snapshot.fingerprint == snapshot.fingerprint)
            retained = None
            if same and previous_selection and not previous_selection.get('event_key'):
                retained = next(((edge, node) for edge, node in bindings.values()
                    if edge.transition_id == previous_selection.get('transition_id')
                    and node.node_id == previous_selection.get('target_node_id')
                    and node.window_id == previous_selection.get('target_window_id')
                    and node.resource_id == previous_selection.get('target_resource_id')
                    and node.bounds == tuple(previous_selection.get('target_bounds', ()))
                    and target_key(node) == previous_selection.get('target_key')), None)
            if retained:
                diagnostic.update(previous_selection.get('selection_diagnostics', {}), called=False, retained=True)
                match = _match(*retained, diagnostic.get('confidence', 1.0),
                    previous_selection.get('target_selection_method', 'commerce_exact'))
            elif recovery.get('waiting_for_user') and same:
                diagnostic.update(status='abstained', paused=True)
            elif engine.screen_resolver:
                match = engine.screen_resolver.resolve(nodes=nodes, raw_nodes=safe_nodes, graph=graph,
                    goal=goal, session=session, screen_bounds=bounds)
                diagnostic.update(engine.screen_resolver.last_diagnostics)
            else:
                # Local mode accepts a unique next route, never an arbitrary button.
                ordered = next(t for t in graph.transitions if t.transition_id == ('CP_ACTION' if ready else 'CP_NAVIGATE')).target_keywords
                for word in ordered:
                    choices = [(edge, node) for edge, node in bindings.values()
                        if normalize_text(word) in {node.label, *node.label_parts}]
                    if len(choices) == 1:
                        match = _match(*choices[0])
                        diagnostic['status'] = 'selected'
                        break
            # The provider cannot invent IDs, coordinates or a graph transition.
            valid = next(((edge, node) for edge, node in bindings.values()
                if match and edge.transition_id == match.transition_id and node.node_id == match.target_node_id
                and node.bounds == match.target_bounds and target_key(node) == match.target_key), None)
            if match and valid:
                edge, node = valid
                guide_id = (previous_selection.get('guide_command_id') if retained else None) or guide_id
                selection = {**selection_binding(match, nodes, diagnostic), 'guide_command_id': guide_id}
                confirmation = edge.safety_level == SafetyLevel.CONFIRM_REQUIRED and request.user_confirmation != 'accepted'
                guide = GuideCommand(guide_id, CommandType.CONFIRMATION if confirmation else CommandType.HIGHLIGHT,
                    ActionType.OBSERVE_ONLY if confirmation else ActionType.TAP,
                    edge.confirmation_message if confirmation else (edge.guide_message if ready else f'화면의 {node.label} 항목을 눌러주세요.'),
                    edge.safety_level, True, node.node_id, node.label, node.bounds,
                    {'avoid_covering_target': True, 'preferred_position': 'below_target', 'manual_only': True})
                if not confirmation:
                    if not retained or not attempt:
                        attempt = {**selection, 'attempt_id': 'attempt_' + uuid4().hex, 'episode_id': request.episode_id,
                            'baseline_observation_id': request.observation_id, 'baseline_captured_at_ms': request.captured_at_ms,
                            'baseline_effect': effect,
                            'navigation_target_index': next((i for i, word in enumerate(edge.target_keywords)
                                if normalize_text(word) in {node.label, *node.label_parts}), None)}
                    selection = None
                else:
                    attempt = None
                recovery = {}
            else:
                match, attempt = None, None
                count = recovery.get('attempts', 0) + 1 if same else 1
                recovery = {'attempts': count, 'waiting_for_user': count >= graph.policy.recovery_limit}
                guide = observe(navigation_instruction + ' 원하는 항목이 보이지 않으면 목록을 직접 스크롤하거나 해당 화면으로 이동해 주세요.',
                    manual=True, result=ready)
                if ready:
                    technical.update(status='UNVERIFIABLE', reason='commerce_manual_result_required')
                    guide = replace(guide, render_policy={**guide.render_policy, 'manual_handoff': True})

    # Stable, graph-owned progress labels only. No product/order names enter state.
    if not path or path[-1] != state:
        path = [*path, state][-32:]
    steps = [{'id': 'open', 'label': '쿠팡 열기', 'status': 'completed' if state != 'S0_UNKNOWN' else 'current'},
        {'id': 'page', 'label': graph.states['C_TARGET'].name + ' 찾기',
            'status': 'completed' if ready or completed else 'current'},
        {'id': 'result', 'label': '결과 확인' if mode != 'navigation' else '화면 도착 확인',
            'status': 'completed' if completed else 'current' if ready else 'pending'}]
    changes = dict(task_id=graph.definition.task_id, typed_goal=goal, current_state_id=state,
        pending_attempt=attempt, pending_selection=selection,
        pending_transition_id=attempt['transition_id'] if attempt else None,
        pending_confirmation_transition_id=selection['transition_id'] if selection else None,
        last_guide_command_id=guide.guide_command_id, last_target_node_id=guide.target_node_id,
        last_snapshot=replace(snapshot, state_id=state), processed_event_keys=processed,
        transition_attempts=transition_attempts,
        recovery_state=recovery, observed_path=tuple(path), progress_steps=tuple(steps),
        technical_outcome=technical, task_completed=completed)
    if engine.session_store.has(request.session_id):
        engine.session_store.update(request.session_id, **changes)
    else:
        engine.session_store.start(session_id=request.session_id, **changes)
    # Runtime event collection uses the existing encrypted persistence pipeline.
    if diagnostic.get('called'):
        diagnostic.setdefault('decision_hash', sha256((request.request_id + guide.guide_command_id).encode()).hexdigest())
    diagnostic.setdefault('screen_hash', snapshot.fingerprint)
    debug = {'state_score': 1.0 if ready or completed else 0.0, 'task_completed': completed,
        'checklist': steps, 'normalized_node_count': len(nodes), 'ui_selection': diagnostic,
        'session_stateful': True, 'correlation': {'observation_id': request.observation_id,
            'attempt_id': verification.attempt_id if verification else None}}
    engine.trace_logger.record({'ui_selection': diagnostic,
        'verification': verification.to_dict() if verification else {}, 'guide': guide.to_dict()})
    return AgentTurnResponse(request.request_id, request.session_id, graph.definition.task_id, goal, state,
        guide, match_result=match, verification_result=verification, debug=debug)
