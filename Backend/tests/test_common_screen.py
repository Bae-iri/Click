"""New OEM menus must reach Jev without a vocabulary or graph-route match."""
from dataclasses import replace
import json

import httpx
import pytest

from app.services.screen_jev import JevScreenResolver
from app.clients.checkpoint import capture, restore
from ai_agent.runtime.engine import ClickAgentEngine
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, UserActionEvent, UserActionType
from ai_agent.runtime.common_screen import PROFILE, STATE, TRANSITION, fingerprint, navigation_nodes
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.screen_privacy import assess_screen, filter_nodes
from tests.test_screen_jev import scene, sender_for


@pytest.fixture(autouse=True)
def configuration(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')


def native_menu(title='기기 환경', labels=('화면 표시 맞춤', '음향 환경')):
    def node(identifier, **kwargs):
        return AccessibilityNode(node_id=identifier, package_name='com.android.settings',
            bounds_in_screen=kwargs.pop('bounds_in_screen', (0, 0, 1080, 2400)), **kwargs)
    nodes = [node('heading', text=title, resource_id='android:id/toolbar_title',
                  class_name='android.widget.TextView', bounds_in_screen=(0, 0, 1080, 100)),
             node('list', scrollable=True, class_name='android.widget.ListView',
                  bounds_in_screen=(0, 100, 1080, 2200))]
    for i, label in enumerate(labels):
        nodes.extend([node(f'row{i}', parent_id='list', clickable=True,
                           class_name='android.widget.LinearLayout', bounds_in_screen=(0, 200+i*200, 1080, 350+i*200)),
                      node(f'label{i}', text=label, parent_id=f'row{i}', resource_id='android:id/title',
                           class_name='android.widget.TextView', bounds_in_screen=(20, 220+i*200, 800, 300+i*200))])
    return tuple(nodes)


def sender(calls, choice='candidate_0', mutate=None):
    def send(payload, **kwargs):
        calls.append(payload)
        options = payload['questions']['screen']['criteria']
        answer = {'type': 'choice', 'choice': choice, 'confidence': 1,
                  'probabilities': {key: float(key == choice) for key in options}}
        body = {'model': payload['model'], 'answers': {'screen': answer}}
        if mutate:
            mutate(body)
        return httpx.Response(200, json=body)
    return send


def request(nodes, goal, **kwargs):
    return AgentTurnRequest('r1', 'common-session', None, nodes, typed_goal=goal,
        device_context=DeviceContext(platform='android', foreground_app='com.android.settings'),
        screen_bounds=(0, 0, 1080, 2400), observation_id='o1', captured_at_ms=1000,
        observation_profile=PROFILE, **kwargs)


def test_unlisted_oem_titles_and_rows_reach_jev_without_authored_routes():
    graph, _, _, goal = scene('font_size')
    calls = []
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=JevScreenResolver(sender(calls)))
    response = engine.handle_turn(request(native_menu(), goal))
    assert len(calls) == 1
    assert response.guide_command.target_node_id == 'row0'
    assert response.match_result.transition_id == TRANSITION
    assert response.current_state_id == STATE
    assert response.guide_command.action_type.value == 'tap'
    assert not response.verification_result or not response.verification_result.task_completed
    rendered = json.dumps(calls[0], ensure_ascii=False)
    assert '화면 표시 맞춤' in rendered and '음향 환경' in rendered
    assert 'current_page' not in rendered and 'transition' not in rendered
    assert 'bounds' not in rendered and 'row0' not in rendered
    items = calls[0]['state']['screen']['items']
    controls = [item for item in items if item['role'] == 'menu_item']
    assert controls and all(item.get('parent') in {n['id'] for n in items} for item in controls)
    assert STATE not in graph.states  # source/released graph is unchanged


def test_checkpoint_and_second_unlisted_page_continue_without_synthetic_completion():
    graph, _, _, goal = scene('font_size')
    calls = []
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=JevScreenResolver(sender(calls)))
    first_request = request(native_menu(), goal)
    first = engine.handle_turn(first_request)
    saved = capture(engine.session_store, first_request.session_id, graph)
    engine.session_store = restore(saved)
    action = UserActionEvent(UserActionType.TAP_COMPLETED, first.guide_command.guide_command_id,
                             'row0', occurred_at_ms=1100, event_id='tap1')
    second = engine.handle_turn(replace(first_request, request_id='r2', observation_id='o2', captured_at_ms=2500,
        accessibility_tree=native_menu('화면 맞춤', ('읽기 크기', '색상 효과')),
        typed_goal=None, user_action_event=action, observation_profile=None))
    assert second.guide_command.target_node_id == 'row0'
    assert second.guide_command.command_type.value != 'done'
    assert second.verification_result is None or not second.verification_result.task_completed
    assert second.verification_result.evidence == 'semantic_page_changed'
    assert second.verification_result.count_attempt
    assert len(calls) == 2


def test_unchanged_public_menu_retains_native_guide_without_new_model_calls():
    graph, _, _, goal = scene('font_size')
    calls = []
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=JevScreenResolver(sender(calls)))
    initial_request = request(native_menu(), goal)
    first = engine.handle_turn(initial_request)
    second = engine.handle_turn(replace(initial_request, request_id='r2', observation_id='o2', captured_at_ms=2000))
    assert first.guide_command.target_node_id == second.guide_command.target_node_id == 'row0'
    assert len(calls) == 1
    assert second.debug['ui_selection']['retained'] is True


def test_public_semantics_do_not_export_body_private_rows_or_credentials():
    nodes = native_menu()
    extra = (replace(nodes[0], node_id='body', resource_id=None, text='PRIVATE_BODY'),
             replace(nodes[2], node_id='account', resource_id='android:id/account_name', text='김민수'),
             replace(nodes[3], node_id='email', parent_id='account', text='someone@example.com'))
    filtered = filter_nodes((*nodes, *extra), 'font_size', semantic=True)
    assert 'PRIVATE_BODY' not in str(filtered) and '김민수' not in str(filtered)
    assert 'someone@example.com' not in str(filtered)
    assert assess_screen(nodes, 'font_size', semantic=True).status is None
    credential = replace(nodes[0], node_id='password', text='secret', class_name='android.widget.EditText', extras={'password': True})
    assert assess_screen((*nodes, credential), 'font_size', semantic=True).status == 'sensitive_screen'


def test_geometry_churn_is_not_navigation_and_embedded_controls_are_not_menus():
    nodes = normalize_accessibility_tree(native_menu())
    shifted = tuple(replace(n, node_id='new-' + n.node_id,
        bounds=(20, 10, 1060, 150)) for n in nodes)
    assert fingerprint(nodes) == fingerprint(shifted)
    slider = replace(nodes[-1], node_id='slider', role='slider', bounds=(30, 240, 700, 290))
    assert all(n.node_id != 'row0' for n in navigation_nodes((*nodes, slider)))
    assert not navigation_nodes(tuple(replace(n, node_id='vision:'+n.node_id) for n in nodes))


@pytest.mark.parametrize('label', ['공장 초기화', '계정 삭제', '결제', 'Reset device'])
def test_operations_are_never_generic_navigation_candidates(label):
    assert not navigation_nodes(normalize_accessibility_tree(native_menu(labels=(label,))))


@pytest.mark.parametrize('mutation', [
    lambda body: body['answers']['screen'].update(choice='invented'),
    lambda body: body['answers']['screen']['probabilities'].update(candidate_0=float('nan')),
    lambda body: body.update(model='jev-latest'),
])
def test_invalid_provider_answers_never_become_a_navigation(mutation):
    graph, _, _, goal = scene('font_size')
    calls = []
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=JevScreenResolver(sender(calls, mutate=mutation)))
    response = engine.handle_turn(request(native_menu(), goal))
    assert response.guide_command.target_node_id is None
    assert len(calls) == 1


def test_known_slider_keeps_existing_confirmation_and_verification_contract():
    graph, edge, slider, goal = scene('font_size')
    calls = []
    original_sender = sender_for(edge)
    def send(payload, **kwargs):
        calls.append(payload)
        return original_sender(payload, **kwargs)
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=JevScreenResolver(send))
    raw = AccessibilityNode(node_id=slider.node_id, text=slider.label, role='slider',
        class_name='android.widget.SeekBar', package_name=slider.package_name,
        bounds_in_screen=slider.bounds, extras={'value': 3, 'min': 0, 'max': 10})
    response = engine.handle_turn(request((raw,), goal))
    assert response.guide_command.command_type.value == 'confirmation'
    assert set(calls[0]['questions']) == {'page', 'target_supported', 'screen'}


def test_http_contract_carries_profile_and_keeps_semantic_checkpoint(monkeypatch):
    from app.main import create_app
    from tests.helpers import authenticated_client
    from tests.test_typed_agent_contract import start_payload
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', 'jev')
    calls = []
    monkeypatch.setattr(JevScreenResolver, '_post', lambda self, payload, **kwargs: sender(calls)(payload, **kwargs))
    app = create_app()
    with authenticated_client(app) as client:
        payload = start_payload()
        payload['accessibility_tree'].update(observation_profile=PROFILE,
            nodes=[n.to_dict() for n in native_menu()])
        response = client.post('/api/v1/agent/commands', json=payload)
        assert response.status_code == 200, response.text
        body = response.json()
        assert body['guide_command']['target_node_id'] == 'row0'
        assert body['metadata']['debug']['ui_selection']['judgment_mode'] == 'semantic'
        replay = client.post('/api/v1/agent/commands', json=payload)
        assert replay.status_code == 200 and replay.json() == body
        assert len(calls) == 1


def test_unlisted_navigation_then_native_slider_approval_and_verified_change():
    graph, edge, slider, goal = scene('font_size')
    calls = []
    common_sender = sender(calls)
    def send(payload, **kwargs):
        if 'page' in payload['questions']:
            calls.append(payload)
            return sender_for(edge)(payload, **kwargs)
        return common_sender(payload, **kwargs)
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=JevScreenResolver(send))
    first_request = request(native_menu(), goal)
    first = engine.handle_turn(first_request)
    raw = AccessibilityNode(node_id=slider.node_id, text=slider.label, role='slider',
        class_name='android.widget.SeekBar', package_name=slider.package_name,
        bounds_in_screen=slider.bounds, extras={'value': 3, 'min': 0, 'max': 10})
    applied = {'font_scale': 1.0, 'source': 'android.provider.Settings.System.FONT_SCALE'}
    leaf_request = replace(first_request, request_id='leaf', observation_id='leaf', captured_at_ms=2500,
        accessibility_tree=(raw,), applied_settings=applied,
        user_action_event=UserActionEvent(UserActionType.TAP_COMPLETED,
            first.guide_command.guide_command_id, 'row0', occurred_at_ms=1100, event_id='menu-tap'))
    leaf = engine.handle_turn(leaf_request)
    assert leaf.guide_command.command_type.value == 'confirmation'
    approved = engine.handle_turn(replace(leaf_request, request_id='approve', observation_id='approve',
        captured_at_ms=3500, user_action_event=None, user_confirmation='accepted'))
    assert approved.guide_command.action_type.value == 'drag'
    changed = engine.handle_turn(replace(leaf_request, request_id='changed', observation_id='changed',
        captured_at_ms=5000, accessibility_tree=(replace(raw, extras={'value': 4, 'min': 0, 'max': 10}),),
        applied_settings={**applied, 'font_scale': 1.2},
        user_action_event=UserActionEvent(UserActionType.DRAG_COMPLETED,
            approved.guide_command.guide_command_id, raw.node_id, occurred_at_ms=3600, event_id='drag')))
    assert changed.guide_command.command_type.value == 'done'
    assert changed.verification_result.task_completed is True
