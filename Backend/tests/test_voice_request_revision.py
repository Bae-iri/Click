from uuid import uuid4
from fastapi.testclient import TestClient
from app.main import create_app
from tests.helpers import register

BASE = '/api/v1/voice-requests'


def setup_queue(client):
    register(client)
    client.post(BASE + '/enqueue', json={'id': str(uuid4()), 'raw_goal': '화면 밝기 높여줘'})
    current = client.post(BASE + '/next/claim').json()['running']
    next_request = client.post(BASE + '/enqueue', json={'id': str(uuid4()), 'raw_goal': '음악 소리 줄여줘'}).json()
    return current, next_request


def test_explicit_revision_replaces_only_running_session_and_keeps_waiting_requests():
    with TestClient(create_app()) as client:
        current, before = setup_queue(client)
        body = {'id': str(uuid4()), 'expected_version': before['queue_version'], 'raw_goal': '글씨를 더 크게 해줘'}
        result = client.post(BASE + '/' + current['id'] + '/revise', json=body)
        assert result.status_code == 200, result.text
        after = result.json()
        assert after['running']['id'] == body['id']
        assert after['running']['raw_goal'] == body['raw_goal']
        assert after['running']['agent_session_id'] != current['agent_session_id']
        assert after['queued'] == before['queued']
        assert client.get('/api/v1/agent/sessions/' + current['agent_session_id']).json()['status'] == 'CANCELLED'
        fresh = client.get('/api/v1/agent/sessions/' + after['running']['agent_session_id']).json()
        assert fresh['version'] == 0 and fresh['episode_id'] is None
        replay = client.post(BASE + '/' + current['id'] + '/revise', json=body)
        assert replay.status_code == 200
        assert replay.json()['running'] == after['running']
        assert replay.json()['queued'] == after['queued']
        assert replay.json()['queue_version'] == after['queue_version']
        assert not replay.json()['claimed']
        late_failure = client.post(BASE + '/' + current['id'] + '/fail', json={'failure_code': 'AGENT_ERROR'})
        assert late_failure.status_code == 409
        assert client.get(BASE).json()['running'] == after['running']


def test_changed_queue_requires_reconfirmation_and_preserves_original_task():
    with TestClient(create_app()) as client:
        current, before = setup_queue(client)
        body = {'id': str(uuid4()), 'expected_version': before['queue_version'] - 1, 'raw_goal': '글씨를 키워줘'}
        result = client.post(BASE + '/' + current['id'] + '/revise', json=body)
        assert result.status_code == 409 and result.headers['X-Conflict-Code'] == 'QUEUE_CHANGED'
        after = client.get(BASE).json()
        assert after['running'] == current and after['queued'] == before['queued']


def test_foreign_ids_queued_targets_and_blank_edits_are_rejected():
    with TestClient(create_app()) as client:
        current, before = setup_queue(client)
        body = {'id': str(uuid4()), 'expected_version': before['queue_version'], 'raw_goal': '글씨를 키워줘'}
        assert client.post(BASE + '/' + str(uuid4()) + '/revise', json=body).status_code == 404
        queued = before['queued'][0]
        assert client.post(BASE + '/' + queued['id'] + '/revise', json=body).status_code == 409
        assert client.post(BASE + '/' + current['id'] + '/revise', json=dict(body, raw_goal='   ')).status_code == 422
        assert client.get(BASE).json()['running'] == current


def test_another_account_cannot_correct_the_owned_running_task():
    with TestClient(create_app()) as client:
        current, before = setup_queue(client)
        owner = client.headers['Authorization']
        register(client)
        body = {'id': str(uuid4()), 'expected_version': before['queue_version'], 'raw_goal': '다른 요청'}
        assert client.post(BASE + '/' + current['id'] + '/revise', json=body).status_code == 404
        client.headers['Authorization'] = owner
        assert client.get(BASE).json()['running'] == current
