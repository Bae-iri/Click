"""VPN navigation with controlled Jev responses and native Samsung menu rows."""
from tests.jev_answers import with_screen_judgments
from dataclasses import replace

import httpx
import pytest

from ai_agent.runtime.engine import ClickAgentEngine
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, TypedGoal
from app.services.screen_jev import JevScreenResolver
from app.task_graphs import approved_graphs
from tests.test_visible_settings_navigation import screen


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')


def vpn(action):
    graph = approved_graphs()['settings.vpn.' + action]
    definition = graph.definition
    goal = TypedGoal(goal_type=definition.goal_type, target_app=definition.target_app,
        task_type=definition.task_type, slots=definition.required_slots, source_text='',
        confidence=1, task_id=definition.task_id, is_supported=True, required_slots_filled=True)
    return graph, goal


def native(title, rows):
    return tuple(AccessibilityNode(**{**node, 'bounds_in_screen': tuple(node['bounds_in_screen'])})
        for node in screen(title, rows)['nodes'])


def answer(payload, transition, confidence=0.96):
    criteria = payload['questions']['screen']['criteria']
    choice = next(key for key, candidate in criteria.items()
        if isinstance(candidate, dict) and candidate['transition'] == transition)
    return httpx.Response(200, json=with_screen_judgments(payload, {'model': payload['model'], 'answers': {'screen': {
        'type': 'choice', 'choice': choice, 'confidence': confidence,
        'probabilities': {key: float(key == choice) for key in criteria}}}}))


@pytest.mark.parametrize('action', ['connect', 'disconnect'])
@pytest.mark.parametrize('titles', [
    ('설정', '연결', '기타 연결 설정'),
    ('Settings', 'Connections', 'More connection settings'),
])
@pytest.mark.parametrize('start_at', [0, 2], ids=['from_settings', 'user_opened_intermediate'])
def test_vpn_route_reaches_vpn_then_hands_off(action, titles, start_at):
    graph, goal = vpn(action)
    home, category, intermediate = titles
    route = [
        (home, [('connections', category, None)], 'OPEN_CATEGORY', 'connections'),
        (category, [('more', intermediate, None)], 'OPEN_INTERMEDIATE', 'more'),
        (intermediate, [('vpn', 'VPN', None)], 'OPEN_PAGE_FROM_INTERMEDIATE', 'vpn'),
    ]
    calls = []

    def send(payload, **kwargs):
        calls.append(payload)
        candidates = [candidate for candidate in payload['questions']['screen']['criteria'].values()
            if isinstance(candidate, dict)]
        assert any(candidate['transition'] == expected for candidate in candidates)
        if expected == 'OPEN_INTERMEDIATE':
            assert {candidate['transition'] for candidate in candidates} == {expected}
            assert candidates[0]['current_page'] == graph.states['S_CATEGORY'].name
            assert candidates[0]['destination_page'] == graph.states['S_INTERMEDIATE'].name
            assert intermediate.lower() in {name.lower() for name in candidates[0]['target_names']}
            navigation = candidates[0]['navigation']
            assert navigation['scope'] == 'open_next_menu_only'
            assert navigation['completes_goal'] is False
            assert navigation['remaining_page_titles'][-1][0][0] == 'VPN'
        return answer(payload, expected)

    resolver = JevScreenResolver(send)
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=resolver)
    for index, (title, rows, expected, target) in enumerate(route[start_at:], start=1):
        result = engine.handle_turn(AgentTurnRequest(request_id=f'r{index}', session_id='vpn',
            typed_goal=goal, raw_goal=None, device_context=DeviceContext(platform='android'),
            accessibility_tree=native(title, rows), screen_bounds=(0, 0, 1080, 2400),
            observation_id=f'o{index}', captured_at_ms=index * 1000))
        assert result.guide_command.action_type.value == 'tap', result.debug
        assert result.guide_command.target_node_id == target
        assert result.guide_command.target_bounds == (0, 260, 1080, 460)
        assert result.match_result.transition_id == expected
        assert resolver.last_diagnostics['status'] == 'selected'

    call_count = len(calls)
    result = engine.handle_turn(AgentTurnRequest(request_id='vpn-page', session_id='vpn',
        typed_goal=goal, raw_goal=None, device_context=DeviceContext(platform='android'),
        accessibility_tree=native('VPN', []), observation_id='vpn-page', captured_at_ms=5000))
    assert result.guide_command.action_type.value == 'observe_only'
    assert result.guide_command.target_node_id is None
    assert result.guide_command.render_policy['manual_handoff'] is True
    assert not result.debug['task_completed']
    assert len(calls) == call_count


@pytest.mark.parametrize('action', ['connect', 'disconnect'])
def test_vpn_still_supports_direct_network_and_internet_route(action):
    graph, goal = vpn(action)
    raw = native('네트워크 및 인터넷', [('vpn', 'VPN', None)])
    resolver = JevScreenResolver(lambda payload, **kwargs: answer(payload, 'OPEN_PAGE'))
    selected = resolver.resolve(nodes=normalize_accessibility_tree(raw), raw_nodes=raw,
        graph=graph, goal=goal)
    assert selected.transition_id == 'OPEN_PAGE'
    assert selected.state_id == 'S_CATEGORY'
    assert selected.target_node_id == 'vpn'


def test_vpn_intermediate_requires_confident_model_selection():
    graph, goal = vpn('connect')
    raw = native('연결', [('more', '기타 연결 설정', None)])
    resolver = JevScreenResolver(lambda payload, **kwargs: answer(payload, 'OPEN_INTERMEDIATE', 0.4))
    selected = resolver.resolve(nodes=normalize_accessibility_tree(raw), raw_nodes=raw,
        graph=graph, goal=goal)
    assert selected is None
    assert resolver.last_diagnostics['status'] == 'low_confidence'
    assert resolver.last_diagnostics['chosen_transition'] == 'OPEN_INTERMEDIATE'
    assert resolver.last_diagnostics['minimum_confidence'] == 0.8


@pytest.mark.parametrize('problem', ['disabled', 'outside_screen', 'duplicate', 'unrelated'])
def test_vpn_intermediate_rejects_invalid_or_ambiguous_rows(problem):
    graph, goal = vpn('connect')
    raw = native('연결', [('more', '기타 연결 설정', None)])
    nodes = normalize_accessibility_tree(raw)
    row = next(node for node in nodes if node.node_id == 'more')
    if problem == 'disabled':
        nodes = tuple(replace(node, enabled=False) if node == row else node for node in nodes)
    elif problem == 'outside_screen':
        nodes = tuple(replace(node, bounds=(1200, 260, 1500, 460)) if node == row else node for node in nodes)
    elif problem == 'duplicate':
        nodes = (*nodes, replace(row, node_id='duplicate', bounds=(0, 500, 1080, 700)))
    else:
        raw = native('연결', [('other', '기타 설정', None)])
        nodes = normalize_accessibility_tree(raw)

    def forbidden(*args, **kwargs):
        pytest.fail('No valid unique VPN route should be sent to Jev')

    resolver = JevScreenResolver(forbidden)
    assert resolver.resolve(nodes=nodes, raw_nodes=raw, graph=graph, goal=goal,
        screen_bounds=(0, 0, 1080, 2400)) is None
    assert resolver.last_diagnostics['status'] == ('ambiguous_candidates' if problem == 'duplicate' else 'no_candidates')


@pytest.mark.parametrize('action', ['connect', 'disconnect'])
def test_vpn_route_upgrade_preserves_existing_pinned_release(action):
    from app.main import create_app
    from app.models.task_graph import ApprovedTaskGraph
    from app.repositories.task_graphs import canonical_graph

    app = create_app()
    repository = app.state.turn_service.graphs
    graph, goal = vpn(action)
    old = replace(graph,
        states={key: value for key, value in graph.states.items() if key != 'S_INTERMEDIATE'},
        transitions=tuple(transition for transition in graph.transitions
            if 'S_INTERMEDIATE' not in {transition.from_state, transition.to_state}))
    # Simulate the already deployed 1.0.0 bundle before publishing the new route.
    with repository.factory() as db:
        db.delete(db.get(ApprovedTaskGraph, goal.task_id + ':1.1.1'))
        db.commit()
    old_reference = repository.register(canonical_graph(old)[0], version='1.0.0', approved=True)
    repository.seed_mvp()
    updated, reference = repository.select(goal)
    assert reference['version'] == '1.1.1'
    assert 'S_INTERMEDIATE' in updated.states
    pinned, preserved = repository.select_pinned({'task_id': goal.task_id, 'graph_version': '1.0.0',
        'goal_type': goal.goal_type, 'goal': {'target_app': goal.target_app},
        'graph_hash': old_reference['sha256']})
    assert preserved['sha256'] == old_reference['sha256']
    assert 'S_INTERMEDIATE' not in pinned.states
    # Restarting the backend must neither conflict with nor reactivate the old route.
    repository.seed_mvp()
    assert repository.select(goal)[1] == reference
    app.state.engine.dispose()
