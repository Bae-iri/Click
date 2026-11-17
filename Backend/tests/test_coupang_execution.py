"""Commerce integration boundaries; synthetic native observations, no purchases."""
from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest

from app.clients.checkpoint import capture, restore
from app.main import create_app
from app.models.agent_session import AgentSession
from app.schemas.agent_turn import TypedGoalRequest
from app.services.screen_jev import JevScreenResolver
from app.task_graphs import approved_graphs
from ai_agent.runtime import ClickAgentEngine
from ai_agent.runtime.commerce_privacy import filter_observation
from ai_agent.runtime.types import (
    AccessibilityNode, AgentTurnRequest, CommandType, DeviceContext, TypedGoal,
    UserActionEvent, UserActionType,
)
from ai_agent.task_graph.commerce import DOCUMENT
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload
from tests.test_goal_preparation import Flow

GRAPHS = approved_graphs()
SUFFIXES = list(DOCUMENT['tasks'])
PKG = 'com.coupang.mobile'


@pytest.mark.parametrize('text', [
    '쿠팡에서 구매하는거 도와줘', '쿠팡에서 구매하는 거 도와줘',
    '쿠팡에서 구매하는 걸 도와줘', '쿠팡에서 구매하는 것 좀 도와줘',
    '쿠팡에서 물건 사는 거 도와줘', '쿠팡 구매 도와줘',
    '쿠팡에서 구매하는 방법 알려줘', '쿠팡에서 구매 도와주세요',
])
def test_purchase_help_reaches_checkout_review_without_model_or_transaction(text, monkeypatch):
    app = create_app()
    def unexpected(*args, **kwargs):
        pytest.fail('A catalog-authored request must not depend on model availability')
    monkeypatch.setattr(app.state.interactions.classifier, 'classify_interaction', unexpected, raising=False)
    monkeypatch.setattr(app.state.turn_service.goal_preparation.llm, 'interpret_inputs', unexpected)
    monkeypatch.setattr(app.state.turn_service.goal_resolution.llm, 'select', unexpected)
    with authenticated_client(app) as client:
        interaction = client.post('/api/v1/interactions/classify', json={'id': str(uuid4()), 'text': text})
        assert interaction.status_code == 200 and interaction.json()['intent'] == 'task'
        flow = Flow(client, app, text)
        legacy = flow.payload()
        legacy.pop('preparation_version')
        legacy_body = client.post('/api/v1/agent/commands', json=legacy)
        assert legacy_body.status_code == 200
        assert legacy_body.json()['goal']['goal_type'] == 'coupang.order.checkout'
        body = flow.resolve()
        assert body['resolution_status'] == 'confirmation_required', body
        assert body['goal']['goal_type'] == 'coupang.order.checkout'
        assert body['preparation']['question'] is None
        assert body['preparation']['graph_reference']['task_id'] == 'CP-order-checkout'
        with app.state.session_factory() as db:
            session = db.get(AgentSession, flow.session)
            assert session.version == 0 and session.episode_id is None


@pytest.mark.parametrize('text,expected', [
    ('쿠팡에서 구매하는거 도와줘 취소', None),
    ('쿠팡에서 구매하는거 도와줘 하지마', None),
    ('쿠팡 구매내역 확인해줘', 'coupang.order.list'),
    ('쿠팡 주문 취소해줘', 'coupang.order.cancel'),
])
def test_purchase_help_aliases_do_not_capture_cancel_or_order_history(text, expected):
    service = create_app().state.turn_service.goal_preparation
    from app.services.goal_inputs import negated
    matches = [] if negated(text) else service._keywords(text)
    assert [g['goal_type'] for g in matches] == ([expected] if expected else [])


def node(id, text, clickable=False, index=0, **kwargs):
    return AccessibilityNode(id, package_name=PKG, text=text, clickable=clickable, window_id=1,
        bounds_in_screen=(20, 40 + index * 200, 1000, 160 + index * 200), **kwargs)


def test_actual_native_navigation_aliases_and_search_resource_are_read_without_queries():
    nodes = [node('home', '', True, content_description='쿠팡 홈'),
        node('category', '', True, 1, content_description='전체 카테고리'),
        node('search', '', True, 2, resource_id=PKG + ':id/rds_search_bar'),
        node('private', '쿠팡 홈 개인 메모', True, 3),
        node('input', 'private-query', True, 4, class_name='android.widget.EditText')]
    safe, result = filter_observation(nodes, PKG)
    assert result.status is None
    assert safe[0].content_description == '홈'
    assert safe[1].content_description == '카테고리'
    assert safe[2].content_description == '상품 검색'
    assert safe[2].resource_id == nodes[2].resource_id
    assert safe[3].text is None and safe[4].text is None and not safe[4].clickable


def goal(suffix):
    definition = GRAPHS['coupang.' + suffix].definition
    return TypedGoal(definition.goal_type, 'coupang', definition.task_type,
        dict(definition.required_slots), '', 1, task_id=definition.task_id,
        is_supported=True, required_slots_filled=True)


def request(suffix, nodes, at=1000, **kwargs):
    return AgentTurnRequest('request-' + str(at), 'commerce-session', None, tuple(nodes), typed_goal=goal(suffix),
        device_context=DeviceContext('android', foreground_app=PKG), screen_bounds=(0, 0, 1080, 2400),
        active_window_id=1, observation_id='observation-' + str(at), captured_at_ms=at, episode_id='episode-1', **kwargs)


@pytest.mark.parametrize('suffix', SUFFIXES)
def test_every_commerce_goal_has_a_pinned_graph_and_reaches_its_correct_support_mode(suffix):
    graph = GRAPHS['coupang.' + suffix]
    spec = DOCUMENT['tasks'][suffix]
    title = DOCUMENT['pages'][spec['page']]['titles'][0]
    nodes = [node('title', title)]
    if spec.get('mode') == 'action':
        nodes.append(node('action', spec['action_labels'][0], True, 1))
    engine = ClickAgentEngine(task_graphs={graph.definition.task_id: graph})
    result = engine.handle_turn(request(suffix, nodes))
    checkpoint = capture(engine.session_store, 'commerce-session', graph)
    restored = restore(checkpoint).get('commerce-session')
    assert restored.task_id == graph.definition.task_id
    assert restored.typed_goal.target_app == 'coupang'
    if spec.get('mode') == 'navigation':
        assert result.guide_command.command_type == CommandType.DONE
        assert result.verification_result.evidence == 'commerce_page_visible'
    elif spec.get('mode') == 'action':
        assert result.guide_command.command_type == CommandType.CONFIRMATION
        assert not restored.task_completed
    else:
        assert result.guide_command.render_policy['manual_handoff']
        assert result.guide_command.render_policy['allow_result_check']
        assert not restored.task_completed
        assert restored.technical_outcome['status'] == 'UNVERIFIABLE'


def test_menu_link_is_not_destination_and_duplicate_controls_are_not_guessed(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only')
    resolver = JevScreenResolver(lambda *a, **k: pytest.fail('duplicates cannot form a unique target'))
    engine = ClickAgentEngine(task_graphs=GRAPHS, screen_resolver=resolver)
    result = engine.handle_turn(request('cart.view', [node('cart1', '장바구니', True), node('cart2', '장바구니', True, 1)]))
    assert result.guide_command.command_type != CommandType.DONE
    assert result.guide_command.target_node_id is None


@pytest.mark.parametrize('change', [{'enabled': False}, {'window_id': None}, {'bounds_in_screen': (0, 0, 0, 0)},
    {'bounds_in_screen': (0, 3000, 900, 3100)}])
def test_ineligible_page_text_cannot_be_completion_evidence(change):
    title = replace(node('title', '장바구니'), **change)
    result = ClickAgentEngine(task_graphs=GRAPHS).handle_turn(request('cart.view', [title]))
    assert result.guide_command.command_type != CommandType.DONE
    assert not result.debug['task_completed']


def test_jev_navigates_from_home_and_reuses_the_exact_unexecuted_target(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only')
    calls = []
    def send(payload, **kwargs):
        calls.append(payload)
        criteria = payload['questions']['screen']['criteria']
        key = next(k for k, v in criteria.items() if isinstance(v, dict) and
            next(n for n in payload['state']['nodes'] if n['id'] == v['node'])['label'] == '마이쿠팡')
        return httpx.Response(200, json={'model': payload['model'], 'answers': {'screen': {
            'type': 'choice', 'choice': key, 'confidence': 1, 'probabilities': {k: int(k == key) for k in criteria}}}})
    engine = ClickAgentEngine(task_graphs=GRAPHS, screen_resolver=JevScreenResolver(send))
    nodes = [node('home', '홈'), node('my', '마이쿠팡', True, 1), node('secret', '구매자 김민수 owner@example.com', index=2)]
    first = engine.handle_turn(request('order.list', nodes))
    second = engine.handle_turn(request('order.list', nodes, 2000))
    assert first.guide_command.target_node_id == second.guide_command.target_node_id == 'my'
    assert len(calls) == 1
    assert second.debug['ui_selection']['retained']
    assert first.debug['ui_selection']['decision_hash'] == second.debug['ui_selection']['decision_hash']
    encoded = json.dumps(calls, ensure_ascii=False)
    for private in ('김민수', 'owner@example.com', 'bounds', 'node_id', PKG, 'secret'):
        assert private not in encoded


def test_confirmation_then_correlated_new_effect_is_required_for_cart_add():
    engine = ClickAgentEngine(task_graphs=GRAPHS)
    nodes = [node('title', '상품 상세'), node('add', '장바구니 담기', True, 1)]
    first = engine.handle_turn(request('cart.add', nodes))
    assert first.guide_command.command_type == CommandType.CONFIRMATION
    accepted = engine.handle_turn(request('cart.add', nodes, 1100, user_confirmation='accepted'))
    assert accepted.guide_command.action_type.value == 'tap'
    event = UserActionEvent(UserActionType.TAP_COMPLETED, accepted.guide_command.guide_command_id,
        'add', (20, 240, 1000, 360), occurred_at_ms=1200, event_id='tap-1',
        session_id='commerce-session', episode_id='episode-1')
    done = engine.handle_turn(request('cart.add', [node('effect', '장바구니에 담았습니다')], 2000, user_action_event=event))
    assert done.guide_command.command_type == CommandType.DONE
    assert done.verification_result.task_completed
    assert engine.session_store.get('commerce-session').technical_outcome['reason'] == 'commerce_effect_observed'


def test_unlinked_feedback_and_action_timeout_never_repeat_or_claim_success():
    engine = ClickAgentEngine(task_graphs=GRAPHS)
    nodes = [node('title', '상품 상세'), node('add', '장바구니 담기', True, 1)]
    engine.handle_turn(request('cart.add', nodes))
    accepted = engine.handle_turn(request('cart.add', nodes, 1100, user_confirmation='accepted'))
    unlinked = engine.handle_turn(request('cart.add', [*nodes, node('effect', '장바구니에 담았습니다', index=2)], 2000))
    assert not unlinked.debug['task_completed']
    assert unlinked.guide_command.action_type.value == 'observe_only'
    assert unlinked.guide_command.render_policy['manual_handoff']
    # A separate attempt with a linked click but no effect also stays unverified.
    other = ClickAgentEngine(task_graphs=GRAPHS)
    other.handle_turn(request('cart.add', nodes))
    accepted = other.handle_turn(request('cart.add', nodes, 1100, user_confirmation='accepted'))
    event = UserActionEvent(UserActionType.TAP_COMPLETED, accepted.guide_command.guide_command_id,
        'add', occurred_at_ms=1200, event_id='tap', session_id='commerce-session', episode_id='episode-1')
    pending = other.handle_turn(request('cart.add', nodes, 7000, user_action_event=event))
    assert not pending.debug['task_completed']
    assert pending.guide_command.action_type.value == 'observe_only'
    assert pending.guide_command.render_policy['allow_result_check']


def test_private_inputs_and_customer_identity_never_become_exported_controls():
    nodes = [node('title', '배송지 관리'), node('name', '장바구니', True, 1,
        resource_id=PKG + ':id/receiver_name'), node('password', 'secret-123', True, 2,
        class_name='android.widget.EditText')]
    filtered, assessment = filter_observation(nodes, PKG)
    assert assessment.status is None
    assert filtered[0].text == '배송지 관리'
    assert all(n.text is None and not n.clickable for n in filtered[1:])


def test_api_preparation_navigation_and_manual_result_keep_business_cancel_distinct():
    app = create_app()
    client = authenticated_client(app)
    payload = start_payload()
    payload['goal'] = {'catalog_version': '2.4.0', 'goal_type': 'coupang.order.cancel',
        'target_app': 'coupang', 'slots': {'action': 'cancel'}}
    payload['device_context']['foreground_package'] = PKG
    payload['accessibility_tree'] = {'package_name': PKG, 'active_window_id': 1,
        'screen_width': 1080, 'screen_height': 2400, 'observation_id': 'native-1', 'captured_at_ms': 1000,
        'nodes': [node('title', '주문 상세').to_dict()]}
    response = client.post('/api/v1/agent/commands', json=payload)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['task_status'] == 'IN_PROGRESS'
    assert body['guide_command']['render_policy']['manual_handoff']
    result = client.post('/api/v1/agent/commands', json={
        'contract_version': 'agent-fe-contract.v0.3', 'request_id': 'ask-result',
        'session_id': body['session_id'], 'expected_version': body['session_version'],
        'episode_id': body['episode_id'], 'command_type': 'request_result'})
    assert result.status_code == 200, result.text
    assert result.json()['metadata']['task_outcome']['setting'] == 'commerce'
    assert result.json()['metadata']['task_outcome']['technical']['status'] == 'UNVERIFIABLE'
    assert '글자' not in result.json()['metadata']['result_prompt']['message']
    from app.services.goal_inputs import negated
    assert not negated('쿠팡 주문 취소해줘')
    assert negated('쿠팡 주문 취소하지마')


def test_coupang_schema_fences_app_slots_and_old_catalog_versions():
    valid = dict(catalog_version='2.4.0', goal_type='coupang.cart.view', target_app='coupang', slots={'action': 'view'})
    assert TypedGoalRequest(**valid)
    for change in ({'target_app': 'android_settings'}, {'slots': {'action': 'checkout'}}, {'catalog_version': '2.3.0'}):
        with pytest.raises(ValueError):
            TypedGoalRequest(**{**valid, **change})


def test_navigation_click_waits_for_observed_progress_before_another_target_is_issued():
    engine = ClickAgentEngine(task_graphs=GRAPHS)
    home = [node('home', '쿠팡'), node('my', '마이쿠팡', True, 1)]
    first = engine.handle_turn(request('order.list', home))
    event = UserActionEvent(UserActionType.TAP_COMPLETED, first.guide_command.guide_command_id,
        'my', occurred_at_ms=1200, event_id='navigate', session_id='commerce-session', episode_id='episode-1')
    waiting = engine.handle_turn(request('order.list', home, 2000, user_action_event=event))
    assert waiting.guide_command.action_type.value == 'observe_only'
    assert waiting.verification_result.status.value == 'WAITING_FOR_OBSERVATION'
    progressed = engine.handle_turn(request('order.list', [node('title', '마이쿠팡'), node('orders', '주문목록', True, 1)], 3000))
    assert progressed.guide_command.target_node_id == 'orders'
    assert progressed.verification_result.evidence == 'observed_progress'
    assert engine.session_store.get('commerce-session').transition_attempts['CP_NAVIGATE:observed'] == 1


def test_all_commerce_display_names_resolve_to_one_goal_and_business_cancel_keeps_its_object():
    from app.services.goal_preparation import GoalPreparationService
    from app.services.goal_inputs import GoalInputRequirements, negated
    service = GoalPreparationService.__new__(GoalPreparationService)
    service.catalog = json.loads((Path(__file__).parents[1] / 'app/task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
    requirements = GoalInputRequirements()
    for entry in service.catalog['goals']:
        if entry['target_app'] != 'coupang':
            continue
        assert service._keywords(entry['display_name']) == [entry]
        family = requirements.family(entry['goal_type'])
        assert family['goal_prefix'] == entry['goal_type']
        assert requirements.missing(family, {}) == []
        assert not negated(entry['display_name'])
    for text in ('쿠팡에서 주문을 취소해줘', '내 구매를 취소하고 싶어', '결제를 취소해줘'):
        assert not negated(text)
    assert negated('주문목록 보여줘 취소')


def test_cart_add_uses_backend_challenge_and_user_result_does_not_forge_a_transaction():
    client = authenticated_client(create_app())
    payload = start_payload()
    payload['goal'] = {'catalog_version': '2.4.0', 'goal_type': 'coupang.cart.add', 'target_app': 'coupang', 'slots': {'action': 'add'}}
    payload['device_context']['foreground_package'] = PKG
    tree = {'package_name': PKG, 'active_window_id': 1, 'screen_width': 1080, 'screen_height': 2400,
        'observation_id': 'before', 'captured_at_ms': 1000,
        'nodes': [node('title', '상품 상세').to_dict(), node('add', '장바구니 담기', True, 1).to_dict()]}
    payload['accessibility_tree'] = tree
    first = client.post('/api/v1/agent/commands', json=payload)
    assert first.status_code == 200, first.text
    body = first.json()
    assert body['task_status'] == 'WAITING_CONFIRMATION'
    follow = {'contract_version': 'agent-fe-contract.v0.3', 'request_id': 'accept-cart',
        'session_id': body['session_id'], 'expected_version': body['session_version'], 'episode_id': body['episode_id'],
        'command_type': 'respond_confirmation', 'user_confirmation': 'accepted',
        'confirmation_id': body['metadata']['confirmation']['id'],
        'accessibility_tree': {**tree, 'observation_id': 'accepted', 'captured_at_ms': 1100}}
    accepted = client.post('/api/v1/agent/commands', json=follow)
    assert accepted.status_code == 200, accepted.text
    current = accepted.json()
    assert current['guide_command']['action_type'] == 'tap'
    result = client.post('/api/v1/agent/commands', json={'contract_version': 'agent-fe-contract.v0.3',
        'command_type': 'request_result', 'request_id': 'manual-cart-result', 'session_id': body['session_id'],
        'expected_version': current['session_version'], 'episode_id': current['episode_id']})
    assert result.status_code == 200, result.text
    waiting = result.json()
    answer = client.post('/api/v1/agent/commands', json={'contract_version': 'agent-fe-contract.v0.3',
        'command_type': 'respond_result', 'request_id': 'manual-cart-answer', 'session_id': body['session_id'],
        'expected_version': waiting['session_version'], 'episode_id': waiting['episode_id'],
        'result_prompt_id': waiting['metadata']['result_prompt']['id'], 'user_result': 'satisfied'})
    assert answer.status_code == 200, answer.text
    outcome = answer.json()['metadata']['task_outcome']
    assert outcome['technical']['status'] == 'UNVERIFIABLE'
    assert outcome['final'] == 'USER_REPORTED_RESOLVED'


def test_natural_coupang_checkout_request_prepares_a_goal_without_starting_a_transaction():
    from tests.test_new_agent_commands import resolution
    from app.models.agent_session import AgentSession
    application = create_app()
    client = authenticated_client(application)
    request_payload, response = resolution(client, '쿠팡 상품 주문해줘')
    assert response.status_code == 200, response.text
    assert response.json()['resolution_status'] == 'confirmation_required'
    assert response.json()['goal']['goal_type'] == 'coupang.order.checkout'
    with application.state.session_factory() as db:
        row = db.get(AgentSession, request_payload['session_id'])
        assert row.version == 0 and row.episode_id is None


@pytest.mark.parametrize('problem', ['low_confidence', 'unknown_choice', 'wrong_model', 'http_failure'])
def test_jev_failure_cannot_become_a_local_commerce_action(monkeypatch, problem):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only')
    def send(payload, **kwargs):
        criteria = payload['questions']['screen']['criteria']
        key = next(k for k in criteria if k.startswith('candidate_'))
        answer = {'type': 'choice', 'choice': key, 'confidence': .2 if problem == 'low_confidence' else 1,
            'probabilities': {k: int(k == key) for k in criteria}}
        if problem == 'unknown_choice':
            answer['choice'] = 'invented-target'
        return httpx.Response(503 if problem == 'http_failure' else 200,
            json={'model': 'jev-0.0.0' if problem == 'wrong_model' else payload['model'], 'answers': {'screen': answer}})
    engine = ClickAgentEngine(task_graphs=GRAPHS, screen_resolver=JevScreenResolver(send))
    result = engine.handle_turn(request('order.list', [node('home', '쿠팡'), node('my', '마이쿠팡', True, 1)]))
    assert result.guide_command.target_node_id is None
    assert result.guide_command.action_type.value == 'observe_only'
    assert not result.debug['task_completed']
