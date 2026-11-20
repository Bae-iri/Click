"""Loopback facade cannot become a generic or unauthenticated live-model proxy."""
from contextlib import contextmanager
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import subprocess
import sys
from threading import Thread
from unittest.mock import Mock

import pytest

from app.schemas.app_task import AppTaskResponse
from scripts.serve_meta_controller_experiment import (ALLOWED_PACKAGES, MAX_BODY_BYTES,
    TASK_PATH, TEST_AUTHORIZATION, create_server, diagnostic_record, load_catalogue)
from tests.test_app_task import request


@contextmanager
def server(tmp_path):
    req = request(package='com.android.settings')
    service = Mock()
    service.step.return_value = AppTaskResponse(request_id=req.request_id, task_id=req.state.task_id,
        observation_id=req.screen.observation_id, fingerprint=req.screen.fingerprint,
        status='needs_input', message='test', diagnostic=dict(called=True,
        model='gemini-3.8-flash', latency_ms=12, reason='grounded_tool_proposal'))
    output = tmp_path / 'calls.jsonl'
    httpd = create_server(catalogue=frozenset({(req.screen.package, req.goal)}),
                         output_path=output, port=0, allow_live=True, service=service)
    thread = Thread(target=httpd.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
    thread.start()
    try:
        yield httpd.server_address[1], req, service, output
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(2)


def post(port, body, *, path=TASK_PATH, authorization=TEST_AUTHORIZATION, extra_headers=None):
    conn = HTTPConnection('127.0.0.1', port, timeout=2)
    headers = {'Content-Type': 'application/json', 'Authorization': authorization}
    headers.update(extra_headers or {})
    conn.request('POST', path, json.dumps(body).encode(), headers)
    response = conn.getresponse()
    status, payload = response.status, json.loads(response.read())
    conn.close()
    return status, payload


def test_exact_catalogue_request_preserves_protocol_and_logs_only_bounded_diagnostics(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        status, response = post(port, req.model_dump(mode='json'))
        assert status == 200 and response['protocol_version'] == 'app-task.v2'
        assert response['diagnostic']['model'] == 'gemini-3.8-flash'
        service.step.assert_called_once()
        log = json.loads(output.read_text())
        assert log['model_calls'] == 1 and log['model_wait_ms'] == 12
        assert log['model'] == 'gemini-3.8-flash' and not log['memory_enabled']
        assert req.goal not in output.read_text() and '검색' not in output.read_text()
        assert log['request_ref'] != req.request_id and log['episode_ref'] != req.state.task_id


@pytest.mark.parametrize('mutation,expected', [
    (lambda r: r.update(goal='Send a message with PRIVATE'), 403),
    (lambda r: r['screen'].update(package='com.private.bank'), 403),
    (lambda r: r.update(apps=[dict(package='com.android.chrome', label='Chrome')]), 403),
    (lambda r: r.update(unexpected='PRIVATE'), 422)])
def test_other_goals_packages_and_unselected_apps_are_rejected_before_service(tmp_path, mutation, expected):
    with server(tmp_path) as (port, req, service, output):
        body = req.model_dump(mode='json')
        mutation(body)
        status, response = post(port, body)
        assert status == expected and 'PRIVATE' not in str(response)
        service.step.assert_not_called()
        assert not output.exists()


def test_static_test_authorization_and_only_one_post_route(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        assert post(port, req.model_dump(mode='json'), authorization='Bearer user-token')[0] == 401
        assert post(port, req.model_dump(mode='json'), path='/api/v1/agent/app-guide/task/memory')[0] == 404
        assert post(port, {'padding': 'x' * MAX_BODY_BYTES})[0] == 413
        service.step.assert_not_called()
        assert not output.exists()


def test_health_does_not_invoke_provider(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        conn = HTTPConnection('127.0.0.1', port, timeout=2)
        conn.request('GET', '/health')
        response = conn.getresponse()
        body = json.loads(response.read())
        conn.close()
        assert response.status == 200 and body['provider_connectivity'] == 'NOT_CHECKED_BY_HEALTH'
        service.step.assert_not_called()
        assert not output.exists()


def test_live_optin_and_loopback_only(tmp_path):
    for kwargs in (dict(host='0.0.0.0', allow_live=True), dict(allow_live=False)):
        with pytest.raises(ValueError):
            create_server(catalogue={('com.android.settings', '설정 열기')}, output_path=tmp_path / 'log', **kwargs)


def test_catalogue_rejects_outside_apps(tmp_path):
    catalogue = tmp_path / 'catalogue.json'
    catalogue.write_text(json.dumps(dict(tasks=[dict(app_id='com.private.bank', goal='열기')])))
    with pytest.raises(ValueError, match='fixed allowlist'):
        load_catalogue(catalogue)
    catalogue.write_text(json.dumps(dict(tasks=[dict(app_id='com.android.settings', goal='설정 열기')]), ensure_ascii=False))
    assert load_catalogue(catalogue) == frozenset({('com.android.settings', '설정 열기')})


def test_arbitrary_diagnostic_strings_are_not_logged():
    req = request(package='com.android.settings')
    response = Mock(diagnostic={'called': True, 'model': 'PRIVATE-KEY', 'reason': 'PRIVATE',
                               'provider_cause': 'PRIVATE', 'latency_ms': float('nan')}, status='unavailable')
    log = diagnostic_record(req, response, 5)
    assert 'PRIVATE' not in str(log) and log['model_wait_ms'] is None


def test_absent_diagnostic_early_return_stays_safe():
    req = request(package='com.android.settings')
    record = diagnostic_record(req, Mock(diagnostic=None, status='protected'), 1)
    assert record['model'] is None and record['model_calls'] == 0


def test_standalone_cli_needs_no_pytest_pythonpath(tmp_path):
    script = Path(__file__).resolve().parents[1] / 'scripts/serve_meta_controller_experiment.py'
    result = subprocess.run([sys.executable, str(script), '--help'], cwd=tmp_path,
        env={k: v for k, v in os.environ.items() if k != 'PYTHONPATH'},
        capture_output=True, text=True, timeout=10)
    assert result.returncode == 0 and '--allow-live' in result.stdout
