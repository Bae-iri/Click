"""Jev owns step/target decisions; evidence and permission gates still own execution."""
from dataclasses import replace
from unittest.mock import Mock

import httpx
import pytest

from ai_agent.runtime import ClickAgentEngine, CommandType, UserActionEvent, UserActionType
from ai_agent.runtime.budget import deadline
from ai_agent.runtime.jev_ui import JevUiSelector
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, ActionType
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.services.screen_jev import JevScreenResolver
from tests.test_screen_jev import scene, sender_for


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')


def setup_slider():
    graph, edge, node, goal = scene('font_size')
    resolver = JevScreenResolver(sender_for(edge))
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=resolver)
    raw = AccessibilityNode(node_id=node.node_id, text='unknown control', role='slider',
        class_name='android.widget.SeekBar', package_name=node.package_name,
        bounds_in_screen=node.bounds, extras={'value': 3, 'min': 0, 'max': 10})
    request = AgentTurnRequest(request_id='r1', session_id='s', typed_goal=goal, raw_goal=None,
        accessibility_tree=(raw, AccessibilityNode(node_id='page-title', text='글자 크기',
            package_name=node.package_name, class_name='android.widget.TextView',
            bounds_in_screen=(10, 0, 300, 80))), device_context=DeviceContext(platform='android'),
        observation_id='o1', captured_at_ms=1000,
        applied_settings={'source': 'android.provider.Settings.System.FONT_SCALE', 'font_scale': 1.0})
    return graph, edge, engine, resolver, request


def test_primary_corrects_rule_state_and_does_not_call_auxiliary(monkeypatch):
    graph, edge, engine, _, request = setup_slider()
    import ai_agent.runtime.engine as runtime
    classify = runtime.classify_screen_state

    def wrong_state(*args, **kwargs):
        classify(*args, **kwargs)
        return 'S1_SETTINGS_HOME'

    monkeypatch.setattr(runtime, 'classify_screen_state', wrong_state)
    monkeypatch.setattr(JevUiSelector, 'select', Mock(side_effect=AssertionError('auxiliary call')))
    engine.state_resolver = Mock(side_effect=AssertionError('private LLM call'))
    result = engine.handle_turn(request)
    assert result.current_state_id == edge.from_state
    assert result.guide_command.target_node_id == request.accessibility_tree[0].node_id
    assert result.debug['ui_selection']['mode'] == 'primary'
    assert result.debug['ui_selection']['status'] == 'selected'
    assert result.debug['ui_selection']['called'] is True


def test_primary_preserves_confirmation_and_requires_measured_change():
    _, edge, engine, _, request = setup_slider()
    first = engine.handle_turn(request)
    assert first.guide_command.command_type == CommandType.CONFIRMATION
    assert not first.debug['task_completed']
    approved = engine.handle_turn(replace(request, request_id='r2', observation_id='o2',
        captured_at_ms=2000, user_confirmation='accepted'))
    assert approved.guide_command.action_type == ActionType.DRAG
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, approved.guide_command.guide_command_id,
        approved.guide_command.target_node_id, occurred_at_ms=2100, event_id='e1')
    # An unlabelled/unknown control selected by Jev must still be verifiable by its
    # exact stored binding, event, native value and applied system setting.
    changed = replace(request.accessibility_tree[0], extras={'value': 4, 'min': 0, 'max': 10})
    done = engine.handle_turn(replace(request, request_id='r3', observation_id='o3',
        captured_at_ms=3000, accessibility_tree=(changed, *request.accessibility_tree[1:]), user_action_event=event,
        applied_settings={**request.applied_settings, 'font_scale': 1.2}))
    assert done.debug['task_completed'] is True
    assert done.guide_command.command_type == CommandType.DONE


def test_primary_resynchronizes_skipped_steps_before_verification():
    from ai_agent.runtime.types import VerificationStatus
    graph, edge, engine, resolver, request = setup_slider()
    entry = next(t for t in graph.transitions if t.from_state == 'S1_SETTINGS_HOME')
    before = AccessibilityNode(node_id='display', text=entry.target_keywords[0],
        package_name='com.android.settings', clickable=True,
        class_name='android.widget.TextView', bounds_in_screen=(10, 100, 300, 140))
    resolver._sender = sender_for(entry)
    first = engine.handle_turn(replace(request, accessibility_tree=(before,)))
    assert first.current_state_id == entry.from_state
    resolver._sender = sender_for(edge)
    advanced = engine.handle_turn(replace(request, request_id='r2', observation_id='o2', captured_at_ms=2000))
    assert advanced.current_state_id == edge.from_state
    assert advanced.verification_result.status == VerificationStatus.SUCCESS
    assert advanced.verification_result.evidence == 'observed_progress'
    assert advanced.verification_result.count_attempt is False
    assert not advanced.debug['task_completed']


@pytest.mark.parametrize('evidence', ['no_event', 'no_setting', 'different_target'])
def test_model_confidence_never_proves_completion(evidence):
    _, _, engine, _, request = setup_slider()
    first = engine.handle_turn(replace(request, user_confirmation='accepted'))
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, first.guide_command.guide_command_id,
        first.guide_command.target_node_id, occurred_at_ms=1100, event_id='e1')
    changed = replace(request.accessibility_tree[0], extras={'value': 4, 'min': 0, 'max': 10})
    if evidence == 'different_target':
        changed = replace(changed, node_id='different-control')
    result = engine.handle_turn(replace(request, request_id='r2', observation_id='o2',
        captured_at_ms=2000, accessibility_tree=(changed, *request.accessibility_tree[1:]),
        user_action_event=None if evidence == 'no_event' else event,
        applied_settings={} if evidence == 'no_setting' else {**request.applied_settings, 'font_scale': 1.2}))
    assert not result.debug['task_completed']
    assert result.guide_command.command_type != CommandType.DONE


@pytest.mark.parametrize('failure', ['offline', 'low_confidence', 'abstain', 'success'])
def test_http_path_uses_primary_and_never_falls_back_to_rule_action(monkeypatch, failure):
    from app.main import create_app
    from tests.helpers import authenticated_client
    from tests.test_typed_agent_contract import start_payload

    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', 'jev')
    calls = []

    def post(self, payload, **kwargs):
        calls.append(payload)
        if failure == 'offline':
            raise httpx.ConnectError('offline')
        options = payload['questions']['screen']['criteria']
        choice = 'insufficient_evidence'
        if failure == 'success':
            choice = next(k for k, value in options.items() if isinstance(value, dict)
                          and value['step'] == 'S1_SETTINGS_HOME')
        return httpx.Response(200, json={'model': payload['model'], 'answers': {'screen': {
            'type': 'choice', 'choice': choice, 'confidence': 0.1 if failure == 'low_confidence' else 1,
            'probabilities': {key: float(key == choice) for key in options}}}})

    monkeypatch.setattr(JevScreenResolver, '_post', post)
    monkeypatch.setattr(JevUiSelector, 'select', Mock(side_effect=AssertionError('auxiliary call')))
    response = authenticated_client(create_app()).post('/api/v1/agent/commands', json=start_payload())
    assert response.status_code == 200, response.text
    body = response.json()
    assert len(calls) == 1
    assert body['guide_command']['action_type'] == ('tap' if failure == 'success' else 'observe_only')
    assert body['guide_command']['target_node_id'] == ('display' if failure == 'success' else None)
    if failure != 'success':
        assert body['guide_command']['message'] == '안내를 준비하고 있어요.'
        assert body['guide_command']['requires_user_action'] is False
        assert body['guide_command']['render_policy']['fallback_action']['type'] == 'request_fresh_observation'
    assert 'recovery_action' not in body['guide_command']['render_policy']
    assert body['metadata']['debug']['ui_selection']['mode'] == 'primary'
    assert body['metadata']['debug']['ui_selection']['called'] is True
    assert body['metadata']['debug']['ui_selection']['grounded_candidate_count'] > 0
    if failure == 'low_confidence':
        assert body['metadata']['debug']['ui_selection']['minimum_confidence'] == 0.8
    assert body['metadata']['debug']['ui_selection']['status'] == {
        'offline': 'provider_error', 'low_confidence': 'low_confidence',
        'abstain': 'abstained', 'success': 'selected'}[failure]
    selection = body['metadata']['debug']['ui_selection']
    assert selection['grounded_candidate_count'] > 0
    if failure != 'offline':
        assert selection['minimum_confidence'] == 0.8
        assert selection['top_probability'] == 1
        assert selection['probability_margin'] == 1


def test_primary_provider_configuration(monkeypatch):
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', 'jev')
    adapter = RuntimeAgentAdapter()
    assert isinstance(adapter.runtime.screen_resolver, JevScreenResolver)
    assert adapter.runtime.state_resolver is None
    assert adapter.runtime.ui_selector is None
    monkeypatch.delenv('TYPESAFE_API_KEY')
    with pytest.raises(ValueError, match='TYPESAFE_API_KEY'):
        RuntimeAgentAdapter()


@pytest.mark.parametrize('outcome', ['selected', 'low_confidence', 'abstained'])
def test_repeated_visible_menu_observations_preserve_selection_diagnostics(monkeypatch, outcome):
    from app.main import create_app
    from tests.helpers import authenticated_client
    from tests.test_typed_agent_contract import start_payload

    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', 'jev')
    calls = []

    def post(self, payload, **kwargs):
        calls.append(payload)
        options = payload['questions']['screen']['criteria']
        candidates = [key for key, value in options.items() if isinstance(value, dict)]
        assert len(candidates) == 1
        choice = 'insufficient_evidence' if outcome == 'abstained' else candidates[0]
        return httpx.Response(200, json={'model': payload['model'], 'answers': {'screen': {
            'type': 'choice', 'choice': choice, 'confidence': 0.4 if outcome == 'low_confidence' else 1,
            'probabilities': {key: float(key == choice) for key in options}}}})

    monkeypatch.setattr(JevScreenResolver, '_post', post)
    with authenticated_client(create_app()) as client:
        payload = start_payload()
        for turn in range(3):
            payload['accessibility_tree'].update(observation_id=f'visible-{turn}', captured_at_ms=1000 * (turn + 1))
            response = client.post('/api/v1/agent/commands', json=payload)
            assert response.status_code == 200, response.text
            body = response.json()
            selection = body['metadata']['debug']['ui_selection']
            assert selection['status'] == outcome
            assert selection['grounded_candidate_count'] == 1
            assert selection['minimum_confidence'] == 0.8
            assert selection['top_probability'] == 1
            assert selection['probability_margin'] == 1
            assert body['guide_command']['action_type'] == ('tap' if outcome == 'selected' else 'observe_only')
            assert body['guide_command']['target_node_id'] == ('display' if outcome == 'selected' else None)
            assert client.post('/api/v1/agent/commands', json=payload).json() == body
            assert len(calls) == (1 if outcome == 'selected' else turn + 1)
            assert selection['called'] is (outcome != 'selected' or turn == 0)
            if outcome == 'selected' and turn:
                assert selection['retained'] is True
            payload = {'contract_version': payload['contract_version'], 'command_type': 'observe',
                'request_id': f'visible-observe-{turn + 1}', 'session_id': body['session_id'],
                'expected_version': body['session_version'], 'episode_id': body['episode_id'],
                'device_context': payload['device_context'], 'accessibility_tree': payload['accessibility_tree']}


@pytest.mark.parametrize('invalid', ['offscreen', 'windows', 'duplicate', 'blocked', 'excluded'])
def test_main_candidate_boundaries(invalid):
    graph, edge, node, goal = scene('font_size')
    nodes = (node,)
    exclusions = None
    if invalid == 'offscreen':
        nodes = (replace(node, bounds=(2000, 100, 2300, 140)),)
    elif invalid == 'windows':
        nodes += (replace(node, node_id='other', window_id=99),)
    elif invalid == 'duplicate':
        nodes += (node,)
    elif invalid == 'blocked':
        from ai_agent.runtime.types import SafetyLevel
        graph = replace(graph, transitions=tuple(replace(t, safety_level=SafetyLevel.BLOCKED)
            for t in graph.transitions))
    else:
        from ai_agent.runtime.targeting import target_key
        exclusions = {t.transition_id: (target_key(node),) for t in graph.transitions}
    sender = Mock(side_effect=AssertionError('invalid candidates must not be sent'))
    resolver = JevScreenResolver(sender)
    assert resolver.resolve(nodes=nodes, graph=graph, goal=goal,
        screen_bounds=(0, 0, 1080, 2400), excluded_target_keys=exclusions) is None
    sender.assert_not_called()


def test_deadline_does_not_call_provider():
    graph, _, node, goal = scene('font_size')
    sender = Mock(side_effect=AssertionError('deadline exhausted'))
    resolver = JevScreenResolver(sender)
    token = deadline.set(0)
    try:
        assert resolver.resolve(nodes=(node,), graph=graph, goal=goal) is None
    finally:
        deadline.reset(token)
    assert resolver.last_diagnostics['status'] == 'deadline_exceeded'
    assert resolver.last_diagnostics['called'] is False


def test_connection_check_reads_backend_env_and_reports_primary(monkeypatch, tmp_path, capsys):
    import json
    from scripts.check_jev_screen import main
    monkeypatch.delenv('AI1_UI_SELECTOR_PROVIDER', raising=False)
    monkeypatch.delenv('TYPESAFE_API_KEY')
    env = tmp_path / '.env'
    env.write_text('AI1_UI_SELECTOR_PROVIDER=jev\nTYPESAFE_API_KEY=synthetic-test-key\n')
    graph, _, _, _ = scene('font_size')
    entry = next(t for t in graph.transitions if t.from_state == 'S1_SETTINGS_HOME')
    monkeypatch.setattr(JevScreenResolver, '_post', lambda self, payload, **kw: sender_for(entry)(payload, **kw))
    # load_dotenv changes process environment; register keys for pytest restoration.
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', '')
    monkeypatch.delenv('AI1_UI_SELECTOR_PROVIDER')
    try:
        assert main(['--env-file', str(env)]) == 0
        output = capsys.readouterr().out
        assert 'synthetic-test-key' not in output
        result = json.loads(output)
        assert result['mode'] == 'primary'
        assert result['called'] is True
        assert result['target_node_id'] == 'display'
    finally:
        import os
        os.environ.pop('AI1_UI_SELECTOR_PROVIDER', None)
        os.environ.pop('TYPESAFE_API_KEY', None)
