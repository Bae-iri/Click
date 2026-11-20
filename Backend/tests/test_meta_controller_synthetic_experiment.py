"""The synthetic live facade must not become a general live-model proxy."""
from contextlib import contextmanager
from http.client import HTTPConnection
import json
from threading import Thread
from time import time, monotonic, sleep
from unittest.mock import Mock

import pytest

from app.schemas.app_task import AppTaskRequest, AppTaskResponse
from scripts.serve_meta_controller_synthetic_experiment import (AUTHORIZATION, PACKAGE,
    TASK_PATH, allowed, create_server, load_contract, make_handler, reference)


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
        {'Content-Type': 'application/json', 'Authorization': authorization,
            'X-Click-Request-Ref': reference(body['request_id']), 'X-Click-Task-Ref': reference(body['state']['task_id'])})
    response = conn.getresponse()
    status, payload = response.status, json.loads(response.read())
    conn.close()
    return status, payload


def records(output, count=1):
    # The terminal delivery result is written after the response socket write.
    deadline = monotonic() + 1
    while monotonic() < deadline:
        try:
            rows = [json.loads(line) for line in output.read_text().splitlines()]
            if len(rows) >= count:
                return rows
        except (FileNotFoundError, json.JSONDecodeError):
            pass
        sleep(.005)
    raise AssertionError('Terminal telemetry was not persisted')


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
        log = records(output)[0]
        assert log['evidence_mode'] == 'live_model_synthetic_closed_loop'
        assert log['model_calls'] == 1 and log['model_wait_ms'] == 12
        assert log['synthetic_ui'] is True and log['real_app'] is False
        assert log['event'] == 'request_terminal' and log['http_status'] == 200
        assert log['request_ref'] == reference(req.request_id) and log['task_ref'] == reference(req.state.task_id)
        assert log['model_call_state'] == 'observed' and log['response_delivery'] == 'sent'
        assert '디스플레이' not in output.read_text() and req.request_id not in output.read_text()


def test_private_label_authentication_and_other_route_never_reach_service(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        body = req.model_dump(mode='json')
        body['screen']['nodes'][0]['label'] = 'PRIVATE'
        assert post(port, body)[0] == 403
        assert post(port, req.model_dump(mode='json'), authorization='Bearer something')[0] == 401
        assert post(port, req.model_dump(mode='json'), path=TASK_PATH + '/memory')[0] == 404
        service.step.assert_not_called()
        rows = records(output, 3)
        assert [r['http_status'] for r in rows] == [403, 401, 404]
        assert all(r['model_call_state'] == 'not_started' and r['model_calls'] == 0 for r in rows)
        assert all(r['request_ref'] == reference(req.request_id) for r in rows)
        assert 'PRIVATE' not in output.read_text() and req.request_id not in output.read_text()


def test_future_public_completion_alias_is_not_required_to_be_a_visible_fixture_control():
    body = request().model_dump(mode='json')
    body['state']['completion'] = {'checks': [dict(package=PACKAGE, label='고급 설정 세부 화면',
        resource=PACKAGE + ':id/future_title', role='text')]}
    assert allowed(AppTaskRequest.model_validate(body), load_contract())
    for label, resource in [('person@example.com', ''), ('안내', PACKAGE + ':id/password'),
                            ('고급', 'com.other.app:id/title')]:
        check = body['state']['completion']['checks'][0]
        check.update(label=label, resource=resource)
        assert not allowed(AppTaskRequest.model_validate(body), load_contract())
    # The observed controls are still restricted to the exact synthetic world.
    body['state']['completion'] = None
    body['screen']['nodes'][0]['label'] = '고급 설정 세부 화면'
    assert not allowed(AppTaskRequest.model_validate(body), load_contract())


def test_schema_rejection_keeps_hashed_ids_and_never_reaches_model(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        body = req.model_dump(mode='json');body.pop('goal')
        status, reply = post(port, body)
        assert status == 422 and reply['error'] == 'invalid_app_task_request'
        record = records(output)[0]
        assert record['request_ref'] == reference(req.request_id)
        assert record['task_ref'] == reference(req.state.task_id)
        assert record['service_state'] == 'not_started' and record['model_calls'] == 0
        service.step.assert_not_called()


def test_service_exception_has_terminal_500_and_unknown_provider_count(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        service.step.side_effect = RuntimeError('PRIVATE_SECRET')
        status, reply = post(port, req.model_dump(mode='json'))
        assert status == 500 and reply['model_call_state'] == 'unknown'
        record = records(output)[0]
        assert record['outcome'] == 'service_exception' and record['service_state'] == 'raised'
        assert record['model_calls'] is None and record['model_wait_ms'] is None
        assert 'PRIVATE_SECRET' not in output.read_text() and 'PRIVATE_SECRET' not in str(reply)


def test_response_for_another_task_cannot_claim_zero_provider_calls(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        service.step.return_value.task_id = 'unrelated-task'
        service.step.return_value.diagnostic = {'called': False, 'latency_ms': 0}
        status, reply = post(port, req.model_dump(mode='json'))
        assert status == 502 and reply['error'] == 'service_identity_mismatch'
        record = records(output)[0]
        assert record['service_state'] == 'returned' and record['outcome'] == 'invalid_service_response'
        assert record['model_call_state'] == 'unknown' and record['model_calls'] is None
        assert record['model_wait_ms'] is None and 'unrelated-task' not in output.read_text()


@pytest.mark.parametrize('diagnostic,state,calls', [({'called': False}, 'not_started', 0), ({}, 'unknown', None)])
def test_missing_provider_instrumentation_cannot_be_reported_as_zero(tmp_path, diagnostic, state, calls):
    with server(tmp_path) as (port, req, service, output):
        service.step.return_value.diagnostic = diagnostic
        assert post(port, req.model_dump(mode='json'))[0] == 200
        record = records(output)[0]
        assert record['model_call_state'] == state and record['model_calls'] == calls


def test_malformed_body_terminal_uses_only_hash_headers_and_server_ref(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        conn = HTTPConnection('127.0.0.1', port, timeout=3)
        conn.request('POST', TASK_PATH, b'PRIVATE NOT JSON', {'Content-Type': 'application/json',
            'Authorization': AUTHORIZATION, 'X-Click-Request-Ref': reference(req.request_id),
            'X-Click-Task-Ref': reference(req.state.task_id)})
        response = conn.getresponse();assert response.status == 422;response.read();conn.close()
        record = records(output)[0]
        assert record['request_ref'] == reference(req.request_id) and len(record['server_ref']) == 24
        assert 'PRIVATE' not in output.read_text()
        service.step.assert_not_called()


def test_request_body_timeout_is_bounded_and_pre_provider():
    from email.message import Message
    handler = make_handler(Mock(), load_contract(), '/unused')
    fake = Mock();fake.path = TASK_PATH;fake.headers = Message()
    for key, value in {'Authorization': AUTHORIZATION, 'Content-Length': '20', 'Content-Type': 'application/json'}.items():
        fake.headers[key] = value
    fake.rfile.read.side_effect = TimeoutError('PRIVATE timeout')
    assert handler._post(fake, {}) == (408, {'error': 'request_read_timeout'})


def test_client_write_failure_is_persisted_after_response_prepared(tmp_path):
    output = tmp_path / 'terminal.jsonl'
    handler = make_handler(Mock(), load_contract(), output)
    fake = Mock();fake.headers = {}
    fake._post.return_value = (403, {'error': 'outside_frozen_public_fixture'})
    fake.reply.side_effect = BrokenPipeError('PRIVATE socket details')
    handler.do_POST(fake)
    record = json.loads(output.read_text())
    assert record['http_status'] == 403 and record['response_delivery'] == 'client_disconnected'
    assert record['model_calls'] == 0 and 'PRIVATE' not in output.read_text()


def test_result_only_plan_failure_diagnostics_are_whitelisted(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        response = service.step.return_value
        response.status = 'confirm_result'
        response.diagnostic.update(failure_kind='plan', failure_stage='completion_contract',
            reason_code='contract_validation_failed', secret='PRIVATE_SECRET')
        assert post(port, req.model_dump(mode='json'))[0] == 200
        record = records(output)[0]
        assert record['plan_or_parse_error'] is True
        assert record['service_reason_code'] == 'contract_validation_failed'
        assert record['service_failure_stage'] == 'completion_contract'
        assert 'PRIVATE_SECRET' not in output.read_text()
