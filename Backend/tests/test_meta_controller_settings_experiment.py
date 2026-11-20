"""Real Settings facade: exact catalogue, native clocks, readonly actions, complete receipts."""
from contextlib import contextmanager
from dataclasses import replace
from email.message import Message
from http.client import HTTPConnection
import json
from pathlib import Path
from threading import Event, Thread
from time import monotonic, sleep
from unittest.mock import Mock

import pytest

from ai_agent.runtime.budget import remaining
from app.schemas.app_task import AppTaskRequest, AppTaskResponse, TaskAction, TaskExpectation
from scripts.serve_meta_controller_settings_experiment import (AUTHORIZATION, EVIDENCE_MODE,
    PACKAGE, TASK_PATH, VERSION, allowed, create_server, load_contract, make_handler,
    navigation_action_allowed, reference)


def manifest_data():
    return dict(schema_version='click.native-settings.v1', app_id=PACKAGE,
        tasks=[dict(task_id=task, goal=f'Open {task} settings') for task in
            ('display', 'sound', 'accessibility', 'bluetooth', 'date_time', 'language')],
        public_labels=['Display', 'Display settings', 'Adaptive brightness', '목록', '검색어 입력',
            'Passwords, passkeys & accounts'], navigation_labels=['Display'],
        public_resources=['', 'android:id/title', PACKAGE + ':id/title', PACKAGE + ':id/switch_widget',
            PACKAGE + ':id/seekbar', PACKAGE + ':id/search', PACKAGE + ':id/password_menu'],
        navigation_controls=[dict(label='Display', resource='android:id/title')])


def contract(tmp_path, data=None):
    path = tmp_path / 'catalogue.json'
    path.write_text(json.dumps(data or manifest_data()), encoding='utf-8')
    return path, load_contract(path)


def request():
    return AppTaskRequest.model_validate(dict(protocol_version='app-task.v2', request_id='native-request',
        goal='Open display settings', screen=dict(status='public', package=PACKAGE,
            observation_id='native-frame', captured_at_ms=500, fingerprint='a' * 64, width=1080, height=2400,
            nodes=[dict(id='node_0_1', label='Display', resource='android:id/title', role='button',
                bounds=[20, 250, 1000, 350], clickable=True)]),
        state=dict(task_id='native-task', revision=0, subgoals=[], attempts=[]),
        apps=[dict(package=PACKAGE, label='Settings')]))


def response(req):
    return AppTaskResponse(request_id=req.request_id, task_id=req.state.task_id,
        observation_id=req.screen.observation_id, fingerprint=req.screen.fingerprint,
        status='needs_input', message='test', diagnostic=dict(called=True,
            model='gemini-3.8-flash', latency_ms=12, reason='grounded_tool_proposal'))


@contextmanager
def server(tmp_path, **options):
    path, _ = contract(tmp_path);req = request();service = Mock();service.step.return_value = response(req)
    output = tmp_path / 'terminal.jsonl'
    httpd = create_server(manifest=path, output_path=output, port=0, allow_live=True, service=service, **options)
    thread = Thread(target=httpd.serve_forever, kwargs={'poll_interval': .01}, daemon=True);thread.start()
    try:yield httpd.server_address[1], req, service, output
    finally:httpd.shutdown();httpd.server_close();thread.join(2)


def post(port, body, *, authorization=AUTHORIZATION, path=TASK_PATH, extra_headers=None):
    headers = {'Content-Type': 'application/json', 'Authorization': authorization,
        'X-Click-Request-Ref': reference(body['request_id']),
        'X-Click-Task-Ref': reference(body['state']['task_id'])}
    headers.update(extra_headers or {})
    conn = HTTPConnection('127.0.0.1', port, timeout=3)
    conn.request('POST', path, json.dumps(body).encode(), headers)
    reply = conn.getresponse();status, payload = reply.status, json.loads(reply.read());conn.close()
    return status, payload


def records(output, count=1):
    end = monotonic() + 2
    while monotonic() < end:
        rows = [json.loads(line) for line in output.read_text().splitlines() if line.strip()]
        if len(rows) >= count:return rows
        sleep(.01)
    raise AssertionError('Terminal records did not arrive')


def get(port, path, *, authorization=AUTHORIZATION):
    conn = HTTPConnection('127.0.0.1', port, timeout=3)
    conn.request('GET', path, headers={'Authorization': authorization})
    reply = conn.getresponse();status, payload = reply.status, json.loads(reply.read());conn.close()
    return status, payload


def test_contract_requires_exact_six_goals_and_frozen_navigation(tmp_path):
    _, item = contract(tmp_path)
    assert len(item.goals) == 6 and len(item.manifest_sha256) == 64
    for change in ('few_tasks', 'duplicate', 'wrong_app', 'unsafe_nav'):
        data = manifest_data()
        if change == 'few_tasks':data['tasks'].pop()
        elif change == 'duplicate':data['tasks'][1] = data['tasks'][0]
        elif change == 'wrong_app':data['app_id'] = 'com.private.app'
        else:data['navigation_labels'].append('Passwords, passkeys & accounts')
        with pytest.raises(ValueError):contract(tmp_path, data)


def test_public_native_context_keeps_toggles_and_empty_search_but_never_values_or_private_input(tmp_path):
    _, item = contract(tmp_path);body = request().model_dump(mode='json')
    body['screen']['nodes'].extend([
        dict(id='toggle', label='Adaptive brightness', resource=PACKAGE + ':id/switch_widget', role='toggle',
            bounds=[20, 400, 1000, 500], clickable=True, checked=True),
        dict(id='search', label='검색어 입력', resource=PACKAGE + ':id/search', role='text_field',
            bounds=[20, 600, 1000, 700], editable=True)])
    assert allowed(AppTaskRequest.model_validate(body), item)
    body['screen']['nodes'][-1]['has_value'] = True
    assert not allowed(AppTaskRequest.model_validate(body), item)
    body['screen']['nodes'][-1].update(has_value=False, resource=PACKAGE + ':id/password_menu')
    assert not allowed(AppTaskRequest.model_validate(body), item)
    body['screen']['nodes'][-1].update(editable=False)
    assert not allowed(AppTaskRequest.model_validate(body), item)


def test_exact_frozen_public_menu_words_are_context_not_private_values_or_navigation_permission(tmp_path):
    _, item = contract(tmp_path);req = request()
    req.screen.nodes[0].label = 'Passwords, passkeys & accounts'
    req.screen.nodes[0].resource = PACKAGE + ':id/password_menu'
    assert allowed(req, item)
    res = response(req);res.action = TaskAction(id='a', tool='tap', target_id=req.screen.nodes[0].id,
        bounds=req.screen.nodes[0].bounds, package=PACKAGE, expectation=TaskExpectation(kind='screen_changed'))
    assert not navigation_action_allowed(req, res, item)
    req.screen.nodes[0].label = 'person@example.com'
    assert not allowed(req, item)


def test_future_completion_alias_is_distinct_from_observed_whitelist(tmp_path):
    _, item = contract(tmp_path);body = request().model_dump(mode='json')
    body['state']['completion'] = dict(checks=[dict(package=PACKAGE, label='Future display title',
        resource=PACKAGE + ':id/new_title', role='text')])
    assert allowed(AppTaskRequest.model_validate(body), item)
    body['screen']['nodes'][0]['label'] = 'Future display title'
    assert not allowed(AppTaskRequest.model_validate(body), item)
    body['screen']['nodes'][0]['label'] = 'Display'
    for label, resource in [('person@example.com', ''), ('Display', PACKAGE + ':id/password_menu'),
            ('Display', 'com.other.app:id/title')]:
        body['state']['completion']['checks'][0].update(label=label, resource=resource)
        assert not allowed(AppTaskRequest.model_validate(body), item)


def test_native_request_and_clock_are_passed_verbatim_and_receipts_have_only_hashes(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        assert post(port, req.model_dump(mode='json'))[0] == 200
        sent = service.step.call_args.args[0]
        assert sent == req and sent.screen.captured_at_ms == 500
        log = records(output)[0]
        assert log['schema_version'] == VERSION and log['evidence_mode'] == EVIDENCE_MODE
        assert log['real_app'] is True and log['synthetic_ui'] is False
        assert log['clock_translation'] == 'none_native_wall_time'
        assert log['model_call_state'] == 'observed' and log['model_calls'] == 1
        assert 0 <= log['service_elapsed_ms'] <= log['request_total_handler_ms']
        assert log['request_ref'] == reference(req.request_id) and log['task_ref'] == reference(req.state.task_id)
        assert req.request_id not in output.read_text() and req.goal not in output.read_text()


@pytest.mark.parametrize('case,status',[('private',403), ('schema',422), ('auth',401), ('route',404), ('budget',400)])
def test_pre_service_rejections_have_terminal_receipts_and_confirmed_zero_calls(tmp_path,case,status):
    with server(tmp_path) as (port, req, service, output):
        body = req.model_dump(mode='json');options = {}
        if case == 'private':body['screen']['nodes'][0]['label'] = 'PRIVATE@example.com'
        elif case == 'schema':body.pop('goal')
        elif case == 'auth':options['authorization'] = 'PRIVATE'
        elif case == 'route':options['path'] = TASK_PATH + '/memory'
        else:options['extra_headers'] = {'X-Click-App-Task-Budget-Ms': 'PRIVATE'}
        assert post(port, body, **options)[0] == status
        log = records(output)[0]
        assert log['http_status'] == status and log['service_state'] == 'not_started'
        assert log['model_call_state'] == 'not_started' and log['model_calls'] == 0
        assert log['request_ref'] == reference(req.request_id)
        service.step.assert_not_called();assert 'PRIVATE' not in output.read_text()


@pytest.mark.parametrize('case,status',[('raise',500), ('identity',502)])
def test_service_exception_or_wrong_identity_cannot_claim_zero_model_calls(tmp_path,case,status):
    with server(tmp_path) as (port, req, service, output):
        if case == 'raise':service.step.side_effect = RuntimeError('PRIVATE token')
        else:
            service.step.return_value.task_id = 'unrelated'
            service.step.return_value.diagnostic = dict(called=False, latency_ms=0)
        assert post(port, req.model_dump(mode='json'))[0] == status
        log = records(output)[0]
        assert log['model_call_state'] == 'unknown' and log['model_calls'] is None and log['model_wait_ms'] is None
        assert 0 <= log['service_elapsed_ms'] <= log['request_total_handler_ms']
        assert 'recovery_context_used' not in log and 'allowed_action_count' not in log
        assert 'PRIVATE' not in output.read_text()


@pytest.mark.parametrize('case', ['valid','malformed','no_recovery'])
def test_terminal_copies_only_typed_bounded_context_diagnostics(case,tmp_path):
    with server(tmp_path) as (port,req,service,output):
        diagnostic=service.step.return_value.diagnostic
        diagnostic.update(recovery_context_used=True,capabilities_applied=True,allowed_actions_context_used=True,
            allowed_action_count=322,recovery_blocked_action_count=16,recovery_attempt=3,raw_recovery='PRIVATE controls')
        if case=='malformed':
            diagnostic.update(recovery_context_used='true',capabilities_applied=1,allowed_actions_context_used=[],
                allowed_action_count=322.0,recovery_blocked_action_count=17,recovery_attempt=4)
        elif case=='no_recovery':diagnostic.update(recovery_context_used=False,recovery_attempt=2)
        assert post(port,req.model_dump(mode='json'))[0]==200
        log=records(output)[0]
        keys=('recovery_context_used','capabilities_applied','allowed_actions_context_used',
            'allowed_action_count','recovery_blocked_action_count','recovery_attempt')
        if case=='malformed':assert all(key not in log for key in keys)
        else:
            assert log['recovery_context_used'] is (case=='valid') and log['capabilities_applied'] is True
            assert log['allowed_actions_context_used'] is True and log['allowed_action_count']==322
            assert log['recovery_blocked_action_count']==16
            if case=='valid':assert log['recovery_attempt']==3
            else:assert 'recovery_attempt' not in log
        assert 'PRIVATE' not in output.read_text() and 'raw_recovery' not in log


def test_unsafe_setting_value_proposal_is_blocked_after_recording_its_model_attempt(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        req.screen.nodes[0].label = 'Adaptive brightness';req.screen.nodes[0].role = 'toggle'
        req.screen.nodes[0].resource = PACKAGE + ':id/switch_widget';req.screen.nodes[0].checked = True
        res = response(req);res.action = TaskAction(id='a', tool='tap', target_id='node_0_1',
            bounds=req.screen.nodes[0].bounds, package=PACKAGE, expectation=TaskExpectation(kind='selection_changed', checked=False))
        service.step.return_value = res
        status, reply = post(port, req.model_dump(mode='json'))
        assert status == 422 and reply['error'] == 'unsafe_navigation_proposal'
        log = records(output)[0]
        assert log['model_call_state'] == 'observed' and log['model_calls'] == 1
        assert log['navigation_proposal_blocked'] and log['plan_or_parse_error']


def test_only_exact_navigation_targets_back_and_scroll_are_authorized(tmp_path):
    _, item = contract(tmp_path);req = request();res = response(req)
    res.action = TaskAction(id='a', tool='tap', target_id='node_0_1', bounds=req.screen.nodes[0].bounds,
        package=PACKAGE, expectation=TaskExpectation(kind='screen_changed'))
    assert navigation_action_allowed(req, res, item)
    req.screen.nodes[0].resource = PACKAGE + ':id/title'
    assert not navigation_action_allowed(req, res, item)
    res.action = TaskAction(id='a', tool='back', package=PACKAGE, expectation=TaskExpectation(kind='screen_changed'))
    assert navigation_action_allowed(req, res, item)
    res.action = TaskAction(id='a', tool='open_app', package=PACKAGE, expectation=TaskExpectation(kind='package_opened', package=PACKAGE))
    assert navigation_action_allowed(req, res, item)
    req.apps.clear();assert not navigation_action_allowed(req, res, item)
    req.screen.nodes[0].resource = PACKAGE + ':id/seekbar';req.screen.nodes[0].label = 'Display'
    res.action = TaskAction(id='a', tool='tap', target_id='node_0_1', bounds=req.screen.nodes[0].bounds,
        package=PACKAGE, expectation=TaskExpectation(kind='screen_changed'))
    assert not navigation_action_allowed(req, res, replace(item, navigation_controls=None))


def test_health_stays_responsive_during_provider_work_and_never_calls_provider(tmp_path):
    with server(tmp_path) as (port, req, service, output):
        entered, release = Event(), Event()
        def blocked(_request, **_kwargs):
            entered.set();assert release.wait(2);return response(req)
        service.step.side_effect = blocked
        result = []
        client = Thread(target=lambda: result.append(post(port, req.model_dump(mode='json'))), daemon=True);client.start()
        try:
            assert entered.wait(1)
            conn = HTTPConnection('127.0.0.1', port, timeout=1);conn.request('GET', '/health')
            reply = conn.getresponse();health = json.loads(reply.read());conn.close()
            assert reply.status == 200 and health['provider_connectivity'] == 'NOT_CHECKED_BY_HEALTH'
            service.step.assert_called_once()
        finally:release.set();client.join(2)
        assert result[0][0] == 200 and len(records(output)) == 1


def test_drain_waits_for_service_and_terminal_append_while_health_remains_immediate(tmp_path, monkeypatch):
    with server(tmp_path) as (port, req, service, output):
        entered, release, writing, finish_write, drained = (Event() for _ in range(5))
        original_open = Path.open
        @contextmanager
        def gated_append(path, *args, **kwargs):
            with original_open(path, *args, **kwargs) as stream:
                class Writer:
                    def write(self, text):
                        writing.set();assert finish_write.wait(2);return stream.write(text)
                yield Writer()
        def open_path(path, *args, **kwargs):
            if path == output and args and args[0] == 'a':return gated_append(path, *args, **kwargs)
            return original_open(path, *args, **kwargs)
        monkeypatch.setattr(Path, 'open', open_path)
        def blocked(_request, **_kwargs):
            entered.set();assert release.wait(2);return response(req)
        service.step.side_effect = blocked
        result, drain_result = [], []
        client = Thread(target=lambda: result.append(post(port, req.model_dump(mode='json'))), daemon=True)
        def drain():
            drain_result.append(get(port, '/drain'));drained.set()
        waiter = Thread(target=drain, daemon=True);client.start()
        try:
            assert entered.wait(1);waiter.start()
            assert not drained.wait(.05)
            assert get(port, '/health')[0] == 200
            release.set();assert writing.wait(1)
            assert not drained.wait(.05)
            finish_write.set();assert drained.wait(1)
            assert drain_result[0][0] == 200 and drain_result[0][1]['terminal_receipts_complete'] is True
            assert len(records(output)) == 1
            service.step.assert_called_once()
        finally:
            release.set();finish_write.set();client.join(2)
            if waiter.ident is not None:waiter.join(2)


def test_drain_is_authorized_bounded_and_does_not_call_provider(tmp_path):
    with server(tmp_path, drain_timeout_ms=25) as (port, req, service, output):
        assert get(port, '/drain', authorization='invalid')[0] == 401
        assert get(port, '/drain')[0] == 200;service.step.assert_not_called()
        entered, release = Event(), Event()
        def blocked(_request, **_kwargs):
            entered.set();assert release.wait(2);return response(req)
        service.step.side_effect = blocked
        client = Thread(target=lambda: post(port, req.model_dump(mode='json')), daemon=True);client.start()
        try:
            assert entered.wait(1);started = monotonic();status, payload = get(port, '/drain')
            assert status == 503 and payload['status'] == 'busy' and payload['terminal_receipts_complete'] is False
            assert monotonic() - started < .5
            assert get(port, '/health')[0] == 200;service.step.assert_called_once()
        finally:release.set();client.join(2)
        assert get(port, '/drain')[0] == 200 and len(records(output)) == 1


def test_drain_cannot_claim_complete_receipts_after_terminal_write_failure(tmp_path):
    _, item = contract(tmp_path);handler = make_handler(Mock(), item, tmp_path / 'terminal.jsonl')
    fake = Mock();fake._do_post_terminal.side_effect = OSError('PRIVATE disk')
    with pytest.raises(OSError):handler.do_POST(fake)
    fake.path = '/drain';fake.headers = {'Authorization': AUTHORIZATION}
    handler.do_GET(fake)
    assert fake.reply.call_args.args[0] == 503
    assert fake.reply.call_args.args[1]['terminal_receipts_complete'] is False


def test_body_timeout_and_client_write_failure_are_recordable_without_raw_errors(tmp_path):
    path, item = contract(tmp_path);handler = make_handler(Mock(), item, tmp_path / 'terminal.jsonl')
    fake = Mock();fake.path = TASK_PATH;fake.headers = Message()
    for key, value in {'Authorization': AUTHORIZATION, 'Content-Length': '20', 'Content-Type': 'application/json'}.items():fake.headers[key] = value
    fake.rfile.read.side_effect = TimeoutError('PRIVATE timeout')
    assert handler._post(fake, {}) == (408, {'error': 'request_read_timeout'})
    output = tmp_path / 'write-failure.jsonl';handler = make_handler(Mock(), item, output)
    fake = Mock();fake.headers = {};fake._post.return_value = (403, {'error': 'outside_frozen_settings_catalogue'})
    fake.reply.side_effect = BrokenPipeError('PRIVATE socket')
    handler._do_post_terminal(fake);log = json.loads(output.read_text())
    assert log['response_delivery'] == 'client_disconnected' and log['model_calls'] == 0
    assert 'PRIVATE' not in output.read_text()


def test_explicit_live_optin_and_loopback_are_required(tmp_path):
    path, _ = contract(tmp_path)
    for options in (dict(host='0.0.0.0', allow_live=True), dict(allow_live=False)):
        with pytest.raises(ValueError):create_server(manifest=path, output_path=tmp_path / 'log', **options)
