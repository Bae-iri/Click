"""Planner -> graph manager -> Jev -> human overlay -> independent verifier pilot."""
from copy import deepcopy
from hashlib import sha256
import json
import logging
from pathlib import Path

from app.schemas.public_guide import PublicGuideResponse
from app.services import public_guide_policy as policy
from app.services.gov24_planner import EXPERIMENT, SERVICES

LOG = logging.getLogger(__name__)
CODE_HASH = sha256(''.join(Path(__file__).with_name(f).read_text(encoding='utf-8') for f in
    ('gov24_experiment.py', 'gov24_planner.py', 'gov24_jev.py')).encode()).hexdigest()
NEXT = {'SUCCESS': 'next_subgoal', 'WAITING': 'keep_current_subgoal',
        'UNKNOWN': 'observe_or_ask_user', 'FAILED': 'jev_retry', 'BLOCKED': 'handoff_or_replan'}


def check(status, reason, **facts):
    return {'status': status, 'reason': reason, 'next': NEXT[status], 'facts': facts}


def observation(screen, goal_type):
    """Sanitized nodes are visible, enabled, active-window-only and package-bound on Android."""
    keys = {n.option_id for n in screen.nodes}
    marker = SERVICES[goal_type][2]
    # A heading alone can be a home tile. Require the service title plus action boundary.
    if marker in keys and 'boundary_apply' in keys:
        return 'requested_service', keys
    if 'marker_services' in keys:
        return 'services', keys
    return ('public_menu' if any(policy.CIVIL_OPTIONS[k]['group'] == 'navigation' for k in keys) else 'unknown'), keys


def signature(screen):
    # Ignore volatile native node IDs/positions when deciding whether UI state changed.
    return sha256(json.dumps(sorted(n.option_id for n in screen.nodes)).encode()).hexdigest()


def decide(service, request, original, now):
    from app.services.gov24_navigation import linked_attempt
    state = deepcopy(original)
    run = state.setdefault('experiment', {'version': EXPERIMENT, 'completed': [], 'plan': None,
        'revision': 0, 'planner_calls': 0, 'local_failures': 0, 'failures': [], 'history': []})
    goal = state['goal']['goal_type']
    title = SERVICES[goal][0]
    step = state.get('steps', 0) + 1
    result = PublicGuideResponse(request_id=request.request_id, session_id=request.session_id,
        session_version=request.expected_version + 1, status='IN_PROGRESS', outcome='in_progress',
        subgoal='find_resident_service', step=step,
        message=f'정부24를 열고 현재 화면 확인을 눌러주세요. {title} 안내 화면까지 이동할게요.')
    evidence = dict(schema_version=EXPERIMENT, policy_version=policy.CIVIL['version'], code_hash=CODE_HASH,
        task_id='GOV24-PLANNER', goal_type=goal, page='unknown', step=step, reason='entry_required')
    checks = {name: check('UNKNOWN', 'no_fresh_public_observation') for name in ('action', 'subgoal', 'final')}
    if linked_attempt(request, state, now):
        checks['action'] = check('SUCCESS', 'matching_target_tap', option=state['pending']['option'])
        state['pending']['tap_confirmed'] = True
    trace = {'version': EXPERIMENT, 'run_ref': sha256(request.session_id.encode()).hexdigest()[:16],
             'verification': checks, 'planner': {'called': False},
             'jev': {'called': False}, 'next': 'observe_or_ask_user'}
    screen = policy.sanitize(request.screen, now * 1000, civil=True)
    if request.command in {'cancel', 'finish'}:
        result.status = 'CANCELLED' if request.command == 'cancel' else 'COMPLETED'
        result.outcome = 'cancelled' if request.command == 'cancel' else 'user_reported'
        result.message = '화면 안내를 마쳤어요. 실제 발급·신청 완료 여부는 확인하지 않았어요.'
        trace['next'] = 'stop'
    elif step > policy.POLICY['max_turns']:
        result.status, result.outcome = 'BLOCKED', 'blocked'
        checks['subgoal'] = check('BLOCKED', 'turn_limit')
        trace['next'] = 'stop'
    elif (screen and screen.status == 'handoff') or (request.screen and request.screen.status == 'sensitive'):
        result.status, result.outcome, result.subgoal = 'COMPLETED', 'handoff', 'handoff'
        result.message = '인증이나 개인정보 입력이 필요해요. 화면 안내를 멈췄어요. 정부24에서 직접 진행해 주세요.'
        checks['action'] = check('SUCCESS', 'matching_target_tap') if linked_attempt(request, state, now) else checks['action']
        checks['subgoal'] = check('BLOCKED', 'private_boundary')
        checks['final'] = check('BLOCKED', 'authentication_or_private_input_required')
        evidence['reason'], trace['next'] = 'private_boundary', 'user_handoff'
        state.pop('pending', None)
    elif screen and screen.status == 'public':
        observe(service, request, state, run, screen, result, evidence, trace, now)
    else:
        # Missing AX data is not a failure and must not trigger replanning.
        result.message = '화면 정보를 충분히 읽지 못했어요. 화면이 뜬 뒤 현재 화면 확인을 눌러주세요.'
        evidence['reason'] = 'insufficient_observation'
    trace.update(revision=run['revision'], planner_calls=run['planner_calls'],
                 plan=run['plan'], completed=list(run['completed']),
                 active_subgoal=active_node(run), request_scope=state['goal'].get('request_scope', 'screen_only'),
                 recent_failures=list(run['failures']))
    for level, verdict in checks.items():
        verdict['condition_type'] = {'action': 'action_performed', 'subgoal': 'screen_entry', 'final': 'task_complete'}[level]
    run['history'] = (run['history'] + [{'step': step, 'page': evidence['page'],
        'verification': deepcopy(checks), 'next': trace['next'], 'revision': run['revision']}])[-24:]
    run['last_trace'] = deepcopy(trace)
    evidence.update(experiment=trace, outcome=result.outcome)
    result.experiment = trace
    state.update(steps=step, outcome=result.outcome)
    # Only canonical predicates/options, statuses and hashes: never raw AX text or secrets.
    LOG.info('gov24_experiment %s', json.dumps(dict(step=step, goal_type=goal, **trace), ensure_ascii=False))
    return result, evidence, state


def active_node(run):
    if 'requested_service_visible' in run['completed']:
        return None
    return next((n for n in (run['plan'] or {}).get('nodes', []) if n['predicate'] not in run['completed']), None)


def observe(service, request, state, run, screen, result, evidence, trace, now):
    from app.services.gov24_navigation import linked_attempt
    page, keys = observation(screen, state['goal']['goal_type'])
    source = signature(screen)
    evidence.update(page=page, source=source)
    trace['observation'] = {'package': policy.POLICY['package'], 'options': sorted(keys), 'signature': source,
                            'service_marker': SERVICES[state['goal']['goal_type']][2]}
    checks = trace['verification']
    pending = state.get('pending')
    linked = linked_attempt(request, state, now)
    if linked:
        pending['tap_confirmed'] = True
    if pending:
        checks['action'] = check('SUCCESS', 'matching_target_tap', option=pending['option']) if pending.get('tap_confirmed') else (
            check('UNKNOWN', 'action_not_correlated') if request.action or source != pending['source'] else
            check('WAITING', 'awaiting_user_tap', option=pending['option']))
    else:
        checks['action'] = check('UNKNOWN', 'no_guided_action_to_verify')
    achieved = {'requested_service_visible'} if page == 'requested_service' else {'services_visible'} if page == 'services' else set()
    current = active_node(run)
    if page == 'requested_service':
        # Direct navigation may legitimately bypass an optional menu subgoal.
        run['completed'] = list(dict.fromkeys(run['completed'] + ['requested_service_visible']))
        checks['subgoal'] = check('SUCCESS', 'service_title_and_action_visible', options=sorted(keys))
        screen_only = state['goal'].get('request_scope', 'screen_only') == 'screen_only'
        checks['final'] = check('SUCCESS', 'requested_screen_verified') if screen_only else check(
            'UNKNOWN', 'issuance_or_submission_not_observed', verified_scope='service_information_screen_only')
        result.status, result.outcome, result.subgoal = 'COMPLETED', 'verified_navigation', 'resident_service'
        result.message = f'{SERVICES[state["goal"]["goal_type"]][0]} 안내 화면에 도착했어요. 실제 발급·신청은 정부24에서 직접 진행해 주세요.'
        evidence['reason'], trace['next'] = 'verified_navigation', 'stop'
        state.pop('pending', None)
        return
    if current and current['predicate'] in achieved:
        run['completed'].append(current['predicate'])
        checks['subgoal'] = check('SUCCESS', 'required_menu_marker_visible', predicate=current['predicate'])
        run['local_failures'] = 0
        state.pop('pending', None)
        pending = None
        trace['next'] = 'next_subgoal'
    elif pending:
        if source == pending['source']:
            checks['subgoal'] = check('WAITING', 'awaiting_user_or_screen_update')
            result.message = '안내한 항목을 눌러주세요. 이미 눌렀다면 화면이 뜬 뒤 현재 화면 확인을 눌러주세요.'
            # Responses must bind to this fresh observation, never an old node/coordinate.
            visible = [n for n in screen.nodes if n.option_id == pending['option']]
            if not pending.get('tap_confirmed') and len(visible) == 1:
                service.target(result, screen, visible[0])
                pending.update(guide_id=result.guide_id, observation_id=screen.observation_id,
                               node_id=visible[0].node_id, captured_at_ms=screen.captured_at_ms,
                               bounds=list(visible[0].bounds))
            trace['next'] = 'keep_current_subgoal'
            return
        if page == 'unknown':
            checks['subgoal'] = check('UNKNOWN', 'destination_has_no_verifiable_marker')
            result.message = '이동한 화면에서 목표 달성을 확인하기 어려워요. 화면을 다시 확인해 주세요.'
            return
        if pending.get('tap_confirmed'):
            checks['subgoal'] = check('FAILED', 'observed_destination_does_not_match_subgoal')
            run['failures'] = (run['failures'] + [{'source': pending['source'], 'option': pending['option'],
                                                  'predicate': pending['predicate'], 'destination': page}])[-8:]
            run['local_failures'] += 1
        else:
            checks['subgoal'] = check('UNKNOWN', 'uncorrelated_navigation')
        state.pop('pending', None)
    if not run['plan'] or run['local_failures'] >= 2:
        if run['planner_calls'] >= 3:
            checks['subgoal'] = check('BLOCKED', 'planning_budget_exhausted')
            result.status, result.outcome = 'BLOCKED', 'blocked'
            result.message = '안내 경로를 확인하지 못했어요. 정부24에서 직접 확인해 주세요.'
            trace['next'] = 'stop'
            return
        run['planner_calls'] += 1
        plan, diagnostic = service.government_planner.plan(state['goal']['goal_type'], page, sorted(keys),
                                                          run['completed'], run['failures'])
        trace['planner'] = diagnostic
        if not plan:
            checks['subgoal'] = check('UNKNOWN', diagnostic.get('reason', 'plan_unavailable'))
            result.message = '안내 계획을 준비하지 못했어요. 잠시 뒤 현재 화면 확인을 눌러주세요.'
            return
        run['plan'], run['revision'], run['local_failures'] = plan, run['revision'] + 1, 0
        # Evaluate already visible predicates even on the first planning call.
        for node in plan['nodes']:
            if node['predicate'] in achieved and node['predicate'] not in run['completed']:
                run['completed'].append(node['predicate'])
                checks['subgoal'] = check('SUCCESS', 'predicate_already_observed', predicate=node['predicate'])
    current = active_node(run)
    if not current:
        checks['subgoal'] = check('UNKNOWN', 'no_active_subgoal_without_verified_final_screen')
        return
    candidates = {}
    for node in screen.nodes:
        if node.option_id in current['options'] and policy.CIVIL_OPTIONS[node.option_id]['group'] == 'navigation':
            candidates.setdefault(node.option_id, []).append(node)
    failed = {f['option'] for f in run['failures'] if f['source'] == source and f['predicate'] == current['predicate']}
    candidates = {key: nodes[0] for key, nodes in candidates.items() if len(nodes) == 1 and key not in failed}
    if not candidates:
        if checks['subgoal']['status'] != 'FAILED':
            checks['subgoal'] = check('UNKNOWN', 'no_visible_candidate_for_subgoal')
        result.message = '현재 화면에서 다음 안내 대상을 확인하지 못했어요. 메뉴를 확인한 뒤 현재 화면 확인을 눌러주세요.'
        return
    selected, diagnostic = service.government_selector.select(state['goal']['goal_type'], current,
                                                               list(candidates), sorted(keys), run['failures'])
    trace['jev'] = diagnostic
    if selected not in candidates:
        if checks['subgoal']['status'] != 'FAILED':
            checks['subgoal'] = check('UNKNOWN', 'jev_could_not_select_visible_target')
        result.message = '누를 항목을 확실히 판단하지 못했어요. 화면을 다시 확인해 주세요.'
        return
    node = candidates[selected]
    service.target(result, screen, node)
    evidence.update(reason='planner_jev_selected', selected_option=selected)
    result.message = f'표시한 {policy.CIVIL_OPTIONS[selected]["label"]} 항목을 눌러주세요. 이동한 화면을 확인할게요.'
    state['pending'] = dict(guide_id=result.guide_id, observation_id=screen.observation_id, node_id=node.node_id,
        captured_at_ms=screen.captured_at_ms, source=source, option=selected, predicate=current['predicate'],
        bounds=list(node.bounds), page=page)
    trace['next'] = 'user_action'
    if checks['subgoal']['status'] not in {'SUCCESS', 'FAILED'}:
        checks['subgoal'] = check('WAITING', 'guidance_presented_awaiting_user')
