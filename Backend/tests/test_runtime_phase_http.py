from fastapi import HTTPException
from fastapi.testclient import TestClient

from app.main import create_app
from app.clients.turn_executor import execute
from tests.helpers import register
from tests.test_agent_sessions import payload, post, session


def test_timeout_phase_reaches_http_and_failed_turn_can_retry_without_version_advance(monkeypatch):
    def timeout(*args):
        raise HTTPException(504, headers={'X-Click-Runtime-Phase': 'provider_http'})
    app = create_app()
    with TestClient(app) as client:
        register(client)
        monkeypatch.setattr('app.services.agent_turn.run_runtime', timeout)
        failed = post(client, payload())
        assert failed.status_code == 504
        assert failed.headers['X-Click-Runtime-Phase'] == 'provider_http'
        assert session(client).json()['version'] == 0
        monkeypatch.setattr('app.services.agent_turn.run_runtime', execute)
        retried = post(client, payload())
        assert retried.status_code == 200
        assert retried.json()['session_version'] == 1


def test_experiment_receipt_keeps_only_closed_runtime_phases():
    import importlib.util
    import json
    from pathlib import Path
    path = Path(__file__).parents[2] / 'experiments/task-success/2026-11-22/trace_proxy.py'
    spec = importlib.util.spec_from_file_location('runtime_phase_trace_probe', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    request = {'command_type': 'start_task'}
    headers = {'X-Click-Runtime-Phase': 'provider_http', 'Authorization': 'PRIVATE_TOKEN'}
    row = module.receipt('test', '/api/v1/agent/commands', request, {}, 504, 20000, headers)
    assert row['runtime_phase'] == 'provider_http'
    assert 'PRIVATE_TOKEN' not in json.dumps(row)
    headers['X-Click-Runtime-Phase'] = 'PRIVATE_SCREEN'
    assert 'runtime_phase' not in module.receipt('test', '/api/v1/agent/commands', request, {}, 504, 20000, headers)
