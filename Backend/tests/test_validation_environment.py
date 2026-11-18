from app.task_graphs.settings_navigation import PAGES
"""Disposable paired environments and trace correlation; no device quality claim."""
import json
import logging
from pathlib import Path

from dotenv import dotenv_values
from fastapi.testclient import TestClient
import pytest

from app.main import create_app
from app.core.validation import digest
from app.models.task_graph import ApprovedTaskGraph
from scripts import validation
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload
from tests.test_runtime_recovery_events import follow


@pytest.fixture
def paired(monkeypatch, tmp_path):
    instance = tmp_path / 'validation'
    source = tmp_path / '.env'
    source.write_text('DATABASE_URL=sqlite:///must-not-create.db\nTYPESAFE_API_KEY=PRIVATE_API_KEY\n', encoding='utf-8')
    monkeypatch.setattr(validation, 'INSTANCE', instance)
    monkeypatch.delenv('DATABASE_URL', raising=False)
    validation.prepare(source, 8001)
    settings = dotenv_values(instance / '.env')
    monkeypatch.setenv('CLICK_VALIDATION_ENVIRONMENT_ID', settings['CLICK_VALIDATION_ENVIRONMENT_ID'])
    monkeypatch.setenv('CLICK_VALIDATION_DIAGNOSTICS', '1')
    monkeypatch.setenv('DATA_ENCRYPTION_KEYS', settings['DATA_ENCRYPTION_KEYS'])
    monkeypatch.setenv('DATA_LOOKUP_KEY', settings['DATA_LOOKUP_KEY'])
    app = create_app(settings['DATABASE_URL'])
    return app, instance


def test_validation_identity_is_disabled_by_default(monkeypatch):
    monkeypatch.delenv('CLICK_VALIDATION_ENVIRONMENT_ID', raising=False)
    with TestClient(create_app()) as client:
        assert client.get('/validation/environment').status_code == 404


def test_prepare_uses_isolated_migrated_db_and_only_current_approved_graphs(paired, capsys):
    app, instance = paired
    prepared = json.loads((instance / 'environment.json').read_text())
    with TestClient(app) as client:
        response = client.get('/validation/environment')
        assert response.status_code == 200
        assert response.json()['fingerprint'] == prepared['fingerprint']
        assert {g['task_id'] for g in response.json()['approved_graphs']} == {
            'MVP-01', 'MVP-02', 'SET-brightness-increase', 'SET-brightness-decrease',
            'SET-volume-increase', 'SET-volume-decrease', 'SET-wifi-enable', 'SET-wifi-disable'} | {
                'NAV-' + page['key'].replace('_', '-')
                for page in PAGES} | {
                spec['task_id'] for spec in json.loads((Path(__file__).parents[1]
                    / 'app/task_graphs/drafts.json').read_text())} | {
                goal['graph_id'] for goal in json.loads((Path(__file__).parents[2]
                    / 'AI-Agent/goal_catalog/legacy_coupang_goals.json').read_text(encoding='utf-8'))['goals']}
        assert len(response.json()['approved_graphs']) == 182
        assert response.json()['database_migrations'] == ['20261117_20']
        assert '/validation/environment' not in client.get('/openapi.json').json()['paths']
    assert (instance / 'validation.db').is_file()
    assert 'PRIVATE_API_KEY' not in response.text + capsys.readouterr().out
    assert 'DATABASE_URL' not in response.text


@pytest.mark.parametrize('drift', ['source', 'graph', 'runtime'])
def test_identity_refuses_code_or_graph_changes_until_pair_is_rebuilt(paired, monkeypatch, drift):
    app, _ = paired
    if drift == 'source':
        monkeypatch.setattr('app.core.validation.source_digest', lambda: '0' * 64)
    elif drift == 'runtime':
        monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.91')
    else:
        with app.state.session_factory() as db:
            db.get(ApprovedTaskGraph, 'MVP-01:1.0.1').active = False
            db.commit()
    with TestClient(app) as client:
        assert client.get('/validation/environment').status_code == 503


def test_diagnostics_join_screen_guide_action_and_new_font_scale_without_raw_content(paired, caplog):
    app, _ = paired
    caplog.set_level(logging.INFO, logger='click.validation')
    client = authenticated_client(app)
    request = start_payload()
    request.update(request_id='PRIVATE_REQUEST', session_id='PRIVATE_SESSION')
    request['accessibility_tree'].update(observation_id='PRIVATE_OBSERVATION', captured_at_ms=1000,
        applied_settings={'source': 'android.provider.Settings.System.FONT_SCALE', 'font_scale': 1.0})
    request['accessibility_tree']['nodes'][0]['text'] = '디스플레이 PRIVATE_SCREEN'
    first = client.post('/api/v1/agent/commands', json=request)
    assert first.status_code == 200
    body = first.json()
    next_request = follow(body)
    next_request['accessibility_tree'].update(observation_id='PRIVATE_NEW_OBSERVATION', captured_at_ms=2000,
        applied_settings={'source': 'android.provider.Settings.System.FONT_SCALE', 'font_scale': 1.1})
    guide = body['guide_command']['guide_command_id']
    next_request['user_action_event'] = {'action_type': 'tap_completed', 'event_id': 'PRIVATE_EVENT',
        'guide_command_id': guide, 'session_id': body['session_id'], 'episode_id': body['episode_id'],
        'occurred_at_ms': 1500}
    second = client.post('/api/v1/agent/commands', json=next_request)
    assert second.status_code == 200, second.text
    records = [json.loads(r.getMessage()) for r in caplog.records if r.name == 'click.validation']
    assert len(records) == 2
    before, after = records
    assert before['trace_id'] == first.headers['X-Request-ID']
    assert after['trace_id'] == second.headers['X-Request-ID']
    assert before['session_id_hash'] == after['action_session_id_hash'] == digest(body['session_id'])
    assert before['episode_id_hash'] == after['action_episode_id_hash']
    assert before['guide_id_hash'] == after['action_guide_id_hash'] == digest(guide)
    assert before['graph_reference'] == after['graph_reference'] == body['graph_reference']
    assert before['observation_id_hash'] != after['observation_id_hash']
    assert before['font_scale'] == 1.0 and after['font_scale'] == 1.1
    assert 'PRIVATE_' not in '\n'.join(r.getMessage() for r in caplog.records)
    assert body['session_id'] not in json.dumps(records)


def test_diagnostic_failure_does_not_change_committed_response(paired, monkeypatch):
    app, _ = paired
    def broken(*_args, **_kwargs):
        raise RuntimeError('PRIVATE_LOGGER_ERROR')
    monkeypatch.setattr('app.core.validation.logger.info', broken)
    client = authenticated_client(app)
    request = start_payload()
    response = client.post('/api/v1/agent/commands', json=request)
    assert response.status_code == 200
    retry = client.post('/api/v1/agent/commands', json=request)
    assert retry.status_code == 200 and retry.json() == response.json()
