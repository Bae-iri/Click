"""HTTP/checkpoint coverage of AI1's attempt and observation contract."""
from copy import deepcopy

from sqlalchemy import select
from app.main import create_app
from app.models.agent_session import AgentCheckpoint
from tests.helpers import authenticated_client
from tests.test_agent_sessions import payload
from tests.test_agent_turn_api import slider_tree


def test_idle_observations_do_not_spend_failure_budget_across_checkpoint_restore():
    app = create_app()
    client = authenticated_client(app)
    version = None
    for index, at in enumerate((1000, 2000, 7000, 15000, 30000)):
        request = payload(f'idle-{index}', 'idle-evidence')
        if index:
            request.pop('raw_goal')
            request['expected_version'] = version
        request['accessibility_tree'].update(observation_id=f'idle-obs-{index}', captured_at_ms=at)
        response = client.post('/api/v1/agent/turn', json=request)
        assert response.status_code == 200, response.text
        body = response.json()
        version = body['session_version']
        if index:
            assert body['metadata']['verification']['result']['status'] == 'WAITING_FOR_ACTION'
    with app.state.session_factory() as db:
        checkpoint = db.scalar(select(AgentCheckpoint).where(AgentCheckpoint.session_id == 'idle-evidence'))
        assert checkpoint.state['total_failed_attempts'] == 0
        assert not checkpoint.state['transition_attempts']
        assert not checkpoint.state['failed_target_keys']


def test_failures_and_event_dedup_survive_per_turn_checkpoint_restore():
    app = create_app()
    client = authenticated_client(app)
    version = None
    index = 0
    def turn(at, *, event=None):
        nonlocal version, index
        index += 1
        request = payload(f'failure-{index}', 'evidence')
        if index > 1:
            request.pop('raw_goal')
            request['expected_version'] = version
        request['accessibility_tree'].update(observation_id=f'obs-{index}', captured_at_ms=at)
        if event: request['user_action_event'] = event
        response = client.post('/api/v1/agent/turn', json=request)
        assert response.status_code == 200, response.text
        body = response.json()
        version = body['session_version']
        return body
    first = turn(1000)
    guide = first['guide_command']
    event = {'event_id': 'action-1', 'action_type': 'tap_completed', 'occurred_at_ms': 1100,
             'guide_command_id': guide['guide_command_id'], 'target_node_id': guide['target_node_id']}
    waiting = turn(1200, event=event)
    assert waiting['metadata']['verification']['result']['status'] == 'WAITING_FOR_OBSERVATION'
    retry = turn(1800)
    assert retry['metadata']['runtime_decision']['strategy'] == 'retry_target'
    replay = turn(2400, event=event)
    assert replay['metadata']['verification']['result']['status'] == 'WAITING_FOR_ACTION'
    with app.state.session_factory() as db:
        checkpoint = db.scalar(select(AgentCheckpoint).where(AgentCheckpoint.session_id == 'evidence'))
        assert sum(checkpoint.state['transition_attempts'].values()) == 1
        assert len(checkpoint.state['processed_event_keys']) == 1
        from app.clients.checkpoint import SCHEMA_VERSION
        assert checkpoint.schema_version == SCHEMA_VERSION


def test_brightness_increase_without_target_action_is_not_http_completion():
    client = authenticated_client(create_app())
    tree = slider_tree(3)
    slider = next(n for n in tree['nodes'] if n['node_id'] == 'font-slider')
    slider['text'] = 'Brightness'
    first = client.post('/api/v1/agent/turn', json={'request_id': 'b1', 'session_id': 'brightness',
        'raw_goal': 'increase font size', 'accessibility_tree': tree}).json()
    after = deepcopy(tree)
    next(n for n in after['nodes'] if n['node_id'] == 'font-slider')['extras']['progress'] = 4
    response = client.post('/api/v1/agent/turn', json={'request_id': 'b2', 'session_id': 'brightness',
        'expected_version': first['session_version'], 'accessibility_tree': after})
    assert response.status_code == 200
    assert response.json()['task_status'] != 'COMPLETED'
    assert response.json()['guide_command']['target_node_id'] is None


def test_confirmation_ignores_capture_metadata_but_keeps_target_binding():
    client = authenticated_client(create_app())
    first_tree = slider_tree(3)
    first_tree.update(observation_id='capture-1', captured_at_ms=1000)
    first = client.post('/api/v1/agent/turn', json={'request_id': 'c1', 'session_id': 'confirm',
        'raw_goal': 'increase font size', 'accessibility_tree': first_tree}).json()
    next_tree = {**first_tree, 'observation_id': 'capture-2', 'captured_at_ms': 1100}
    response = client.post('/api/v1/agent/turn', json={'request_id': 'c2', 'session_id': 'confirm',
        'expected_version': first['session_version'], 'accessibility_tree': next_tree,
        'user_confirmation': 'accepted', 'confirmation_id': first['metadata']['confirmation']['id']})
    assert response.status_code == 200, response.text
    assert response.json()['guide_command']['action_type'] == 'drag'
    waiting = client.post('/api/v1/agent/turn', json={'request_id': 'c3', 'session_id': 'confirm',
        'expected_version': response.json()['session_version'],
        'accessibility_tree': {**next_tree, 'observation_id': 'capture-3', 'captured_at_ms': 1200}})
    assert waiting.status_code == 200, waiting.text
    assert waiting.json()['guide_command']['guide_command_id'] == response.json()['guide_command']['guide_command_id']
