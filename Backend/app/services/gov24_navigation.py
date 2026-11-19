"""Manual navigation to the resident-copy service entry; no application submission."""
from copy import deepcopy
from pathlib import Path
import json

from app.services import public_guide_policy as policy
from app.services.gov24_routes import SCHEMA, choose_route


def digest(value):
    return policy_digest(json.dumps(value, sort_keys=True, ensure_ascii=False))


def policy_digest(value):
    from hashlib import sha256
    return sha256(value.encode()).hexdigest()


CODE_HASH = policy_digest(json.dumps(policy.CIVIL, sort_keys=True, ensure_ascii=False) + ''.join(
    Path(__file__).with_name(name).read_text(encoding='utf-8') for name in
    ('gov24_navigation.py', 'gov24_routes.py', 'gov24_graphs.py', 'public_guide_policy.py')))


def page_of(screen, goal_type=None):
    keys = {n.option_id for n in screen.nodes}
    graph = policy.CIVIL_GRAPHS[goal_type or policy.CIVIL['goal_type']]
    if any(set(required) <= keys for required in graph['completion']['any_of']):
        return graph['completion']['page']
    if 'marker_home' in keys:
        return 'public_menu'
    if 'marker_services' in keys:
        return 'services'
    return 'public_menu' if any(policy.CIVIL_OPTIONS.get(k, {}).get('group') == 'navigation' for k in keys) else 'unknown'


def decide(service, request, original, now):
    from app.schemas.public_guide import PublicGuideResponse
    state = deepcopy(original)
    graph = policy.CIVIL_GRAPHS[state['goal']['goal_type']]
    label = graph['label']
    step = state.get('steps', 0) + 1
    result = PublicGuideResponse(request_id=request.request_id, session_id=request.session_id,
        session_version=request.expected_version + 1, status='IN_PROGRESS', outcome='in_progress',
        subgoal='find_resident_service' if graph['goal_type'] == policy.CIVIL['goal_type'] else 'find_service', step=step,
        service_label=label, baseline_graph_id=graph['task_id'], graph_state='public_menu',
        message=f'정부24의 {label} 안내 화면을 찾고 있어요. 공개 메뉴가 보이면 다음 위치를 안내할게요.')
    evidence = dict(schema_version=SCHEMA, policy_version=policy.CIVIL['version'], code_hash=CODE_HASH,
        goal_type=graph['goal_type'], task_id=graph['task_id'].replace('GOV24-BASE-', 'GOV24-'),
        baseline_graph_id=graph['task_id'], page='unknown', reason='entry_required', step=step)
    pending = linked_attempt(request, state, now)
    if pending:
        # Private or unrecognized destinations still contribute an unresolved
        # attempt; omitting them would inflate success rates.
        evidence['attempt'] = dict(id=policy_digest(pending['guide_id']), source=pending['source'],
                                   option=pending['option'], result='unknown')
    if request.command in {'cancel', 'finish'}:
        result.status = 'CANCELLED' if request.command == 'cancel' else 'COMPLETED'
        result.outcome = 'cancelled' if request.command == 'cancel' else 'user_reported'
        result.message = f'정부24 {label} 화면 안내를 마쳤어요.'
    else:
        screen = policy.sanitize(request.screen, now * 1000, civil=True)
        if service.authentication(request, screen, state, result, evidence, civil=True):
            pass
        elif screen and screen.status == 'public':
            arrived = page_of(screen, graph['goal_type']) == graph['completion']['page']
            if not arrived and policy.navigation_limit(state, request, screen, civil=True):
                service.navigation_block(result, evidence)
            else:
                observe(service, request, screen, state, result, evidence, now)
        else:
            result.message = f'{label}로 이동할 공개 메뉴를 확인하지 못했어요. 정부24의 서비스 목록을 열거나 목록을 내려주세요.'
            evidence['reason'] = 'unrecognized'
    if result.subgoal in {'handoff', 'await_login', 'await_public'}:
        result.graph_state = 'handoff'
    state.update(steps=step, outcome=result.outcome)
    evidence['outcome'] = result.outcome
    evidence['navigation_turns'] = state.get('navigation_turns', 0)
    from app.services.gov24_diagnostics import attach
    attach(result, evidence, state, request, now * 1000, civil=True)
    return result, evidence, state


def linked_attempt(request, state, now):
    pending, action, screen = state.get('pending'), request.action, request.screen
    if (pending and action and screen
            and now * 1000 - 30000 <= screen.captured_at_ms <= now * 1000 + 5000
            and action.guide_id == pending['guide_id']
            and action.observation_id == pending['observation_id'] and action.node_id == pending['node_id']
            and pending['captured_at_ms'] <= action.occurred_at_ms <= screen.captured_at_ms
            and action.occurred_at_ms <= now * 1000 + 5000):
        return pending
    return None


def observe(service, request, screen, state, result, evidence, now):
    from app.schemas.goal_preparation import InputChoice, InputQuestion
    from uuid import uuid4
    graph = policy.CIVIL_GRAPHS[state['goal']['goal_type']]
    label = graph['label']
    destination = graph['completion']['page']
    page = page_of(screen, graph['goal_type'])
    result.graph_state = 'service_entry' if page == destination else page
    keys = sorted(n.option_id for n in screen.nodes)
    source = digest(keys)
    screen_hash = digest(screen.model_dump(exclude={'observation_id', 'captured_at_ms'}))
    question_hash = policy.question_fingerprint(screen)
    evidence.update(page=page, source=source)
    pending = linked_attempt(request, state, now)
    if pending:
        attempt_result = 'unknown'
        if source != pending['source'] and screen_hash != pending['screen_hash'] and page != 'unknown':
            # Progress is confirmed by observed public destination semantics.
            target = pending['option']
            progressed = (page == destination or target in {'nav_services', 'nav_frequent'}
                          and page == 'services' and pending['page'] != 'services')
            attempt_result = 'successes' if progressed else 'failures'
        evidence['attempt'] = dict(id=policy_digest(pending['guide_id']), source=pending['source'],
                                   option=pending['option'], result=attempt_result)
        if attempt_result != 'unknown':
            state.pop('pending', None)
    if page == destination:
        result.status, result.outcome, result.subgoal = 'COMPLETED', 'verified_navigation', destination
        result.message = f'{label} 안내 화면에 도착했어요. 신청서 작성과 최종 신청은 정부24에서 직접 진행해 주세요.'
        evidence['reason'] = 'verified_navigation'
        state.pop('pending', None)
        return
    allowed = {edge['target_option'] for edge in graph['transitions']}
    candidates = {}
    for node in screen.nodes:
        if node.option_id in allowed and policy.CIVIL_OPTIONS[node.option_id]['group'] == 'navigation':
            candidates.setdefault(node.option_id, []).append(node)
    candidates = {key: nodes[0] for key, nodes in candidates.items() if len(nodes) == 1}
    evidence['candidate_count'] = len(candidates)
    if not candidates:
        result.message = f'{label} 메뉴가 아직 보이지 않아요. 정부24 목록을 내려 항목을 찾아주세요. 화면이 바뀌면 안내를 이어갈게요.'
        result.reason_code = 'no_candidates'
        evidence['reason'] = 'no_candidates'
        return
    selected = None
    question = state.get('question', {})
    if request.choice or request.answer:
        if question.get('id') == request.question_id and question.get('screen_hash') == question_hash:
            if request.choice in candidates:
                selected = request.choice
            elif request.answer:
                text = policy.normalize(request.answer)
                matches = [k for k in candidates if any(policy.normalize(a) == text for a in policy.CIVIL_OPTIONS[k]['aliases'])]
                if len(matches) == 1:
                    selected = matches[0]
            if selected:
                evidence['reason'] = 'user_selected'
    else:
        try:
            winner = choose_route(service.factory, CODE_HASH, source, candidates, now, goal_type=graph['goal_type'])
        except Exception:
            winner = None
        if winner:
            selected = winner['key']
            evidence.update(reason='ai2_selected', selection_metrics={k: winner[k] for k in
                ('successes', 'failures', 'unknown', 'attempts', 'success_rate', 'score')})
            if winner.get('graph_hash'):
                evidence.update(selection_policy='ai2_gov24_task_graph', selected_graph_hash=winner['graph_hash'])
        else:
            from app.task_graphs.gov24 import choose
            baseline = choose(graph, page, candidates)
            if baseline:
                selected = baseline['target_option']
                evidence.update(reason='baseline_graph_selected', baseline_transition=baseline['id'])
    if selected:
        node = candidates[selected]
        service.target(result, screen, node)
        result.message = f"표시한 {policy.CIVIL_OPTIONS[selected]['label']} 항목을 눌러주세요. 이동 후 현재 화면 확인을 눌러주세요."
        evidence['selected_option'] = selected
        state.pop('question', None)
        state['pending'] = dict(guide_id=result.guide_id, observation_id=screen.observation_id,
            node_id=node.node_id, captured_at_ms=screen.captured_at_ms, screen_hash=screen_hash,
            source=source, option=selected, page=page)
    else:
        result.message = f'{label}로 이동할 메뉴를 골라주세요. 실제 이동 결과를 관측해 다음 경로 선택에 반영해요.'
        result.question = InputQuestion(id='civil-' + uuid4().hex, field='civil_navigation',
            label='이동할 메뉴', kind='choice', message=result.message,
            choices=[InputChoice(value=k, label=policy.CIVIL_OPTIONS[k]['label']) for k in candidates], max_length=160)
        state['question'] = dict(id=result.question.id, screen_hash=question_hash)
        evidence['reason'] = 'needs_choice'
