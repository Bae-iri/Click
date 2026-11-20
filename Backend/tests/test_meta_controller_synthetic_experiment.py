"""The synthetic live facade must not become a general live-model proxy."""
from contextlib import contextmanager
from http.client import HTTPConnection
import json
from threading import Thread
from time import time
from unittest.mock import Mock

import pytest

from app.schemas.app_task import AppTaskRequest, AppTaskResponse
from scripts.serve_meta_controller_synthetic_experiment import (AUTHORIZATION, PACKAGE,
    TASK_PATH, allowed, create_server, load_contract)


def request():
    return AppTaskRequest.model_validate(dict(protocol_version='app-task.v2', request_id='synthetic-request',
        goal='디스플레이 화면을 열어줘', screen=dict(status='public', package=PACKAGE,
            observation_id='synthetic-frame', captured_at_ms=500, fingerprint='a' * 64, width=1080, height=2400,
            nodes=[dict(id='primary', label='디스플레이', resource=PACKAGE + ':id/primary', role='button',
                bounds=[20, 250, 1000, 350], clickable=True)]),
        state=dict(task_id='synthetic-task', revision=0, subgoals=[], attempts=[]), apps=[]))


@contextmanager
def server(tmp_path):
    req = request()
    service = Mock()
    service.step.return_value = AppTaskResponse(request_id=req.request_id, task_id=req.state.task_id,
        observation_id=req.screen.observation_id, fingerprint=req.screen.fingerprint,
        status='needs_input', message='test', diagnostic=dict(called=True,
            model='gemini-3.8-flash', latency_ms=12, reason='grounded_tool_proposal'))
    output = tmp_path / 'provider.jsonl'
    httpd = create_server(output_path=output, port=0, allow_live=True, service=service)
    thread = Thread(target=httpd.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
    thread.start()
    try:
        yield httpd.server_address[1], req, service, output
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(2)


def post(port, body, authorization=AUTHORIZATION, path=TASK_PATH):
    conn = HTTPConnection('127.0.0.1', port, timeout=3)
    conn.request('POST', path, json.dumps(body).encode(),
        {'Content-Type': 'application/json', 'Authorization': authorization})
    response = conn.getresponse()
    status, payload = response.status, json.loads(response.read())
    conn.close()
    return status, payload


def test_only_exact_frozen_goals_public_fixture_controls_and_selected_app_are_allowed():
    req = request()
    assert allowed(req, load_contract())
    for mutation in (
        lambda r: r.update(goal='PRIVATE goal'),
        lambda r: r['screen'].update(package='com.android.settings'),
        lambda r: r['screen']['nodes'][0].update(label='PRIVATE controls'),
        lambda r: r['screen']['nodes'][0].update(resource='com.android.settings:id/primary'),
        lambda r: r['screen']['nodes'][0].update(source='ocr'),
        lambda r: r.update(apps=[dict(package='com.android.chrome', label='Chrome')]),
    ):
        body = req.model_dump(mode='json')
        mutation(body)
        assert not allowed(AppTaskRequest.model_validate(body), load_contract())


def test_public_search_node_uses_normalized_label_without_any_input_value():
    body = request().model_dump(mode='json')
    body['screen']['nodes'] = [dict(id='search', label='검색어 입력', resource=PACKAGE + ':id/search',
        role='text_field', editable=True, bounds=[20, 300, 1000, 400])]
    assert allowed(AppTaskRequest.model_validate(body), load_contract())


def test_live_optin_and_loopback_are_required_before_binding(tmp_path):
    for kwargs in (dict(host='0.0.0.0', allow_live=True), dict(allow_live=False)):
        with pytest.raises(ValueError):
            create_server(output_path=tmp_path / 'log', **kwargs)


def test_real_service_receives_evolving_request_with_only_freshness_clock_translation(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        before = int(time() * 1000)
        status, response = post(port, req.model_dump(mode='json'))
        assert status == 200 and response['protocol_version'] == 'app-task.v2'
        sent = service.step.call_args.args[0]
        assert before <= sent.screen.captured_at_ms <= int(time() * 1000)
        assert sent.screen.fingerprint == req.screen.fingerprint
        assert sent.screen.observation_id == req.screen.observation_id
        assert sent.state == req.state and sent.goal == req.goal
        assert req.screen.captured_at_ms == 500  # Controller's original frame is unchanged.
        log = json.loads(output.read_text())
        assert log['evidence_mode'] == 'live_model_synthetic_closed_loop'
        assert log['model_calls'] == 1 and log['model_wait_ms'] == 12
        assert log['synthetic_ui'] is True and log['real_app'] is False
        assert '디스플레이' not in output.read_text() and req.request_id not in output.read_text()


def test_private_label_authentication_and_other_route_never_reach_service(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        body = req.model_dump(mode='json')
        body['screen']['nodes'][0]['label'] = 'PRIVATE'
        assert post(port, body)[0] == 403
        assert post(port, req.model_dump(mode='json'), authorization='Bearer something')[0] == 401
        assert post(port, req.model_dump(mode='json'), path=TASK_PATH + '/memory')[0] == 404
        service.step.assert_not_called()
        assert not output.exists()
