"""GPT uses the same grounded task contract and one bounded provider attempt."""
from contextlib import nullcontext
import json
from time import monotonic
from unittest.mock import Mock

import httpx
import pytest

from app.services.app_task import AppTaskService
from app.services import app_task_provider, openai_responses
from tests.test_app_task_goal_first import answer, current_goal


def message(text):
    return {'type': 'message', 'role': 'assistant', 'status': 'completed',
            'content': [{'type': 'output_text', 'text': text}]}


@pytest.fixture(autouse=True)
def gpt(monkeypatch):
    monkeypatch.setenv('APP_TASK_PROVIDER', 'openai')
    monkeypatch.setenv('APP_TASK_OPENAI_MODEL', 'gpt-6.2-sol')
    monkeypatch.setenv('OPENAI_API_KEY', 'test-openai')
    monkeypatch.setenv('GEMINI_API_KEY', 'test-gemini')
    monkeypatch.setenv('APP_TASK_MENU_MODEL', 'gemini-unrelated')
    monkeypatch.setenv('APP_TASK_COMPLETION_PROVIDER', 'gemini')


@pytest.mark.parametrize('mode', ['goal_first', 'result_only'])
def test_one_openai_response_retains_native_evidence_and_metering(monkeypatch, mode):
    # The Qwen first-menu experiment must leave non-menu planning and result checks on GPT.
    monkeypatch.setenv('APP_TASK_OPENROUTER_SHORT_MENU', '1')
    req = current_goal(planned=False)
    req.planning_mode = mode
    result = answer(req)
    if mode == 'result_only':
        result = {'completion_checks': result['completion_checks']}
    else:
        result['planned_steps'] = []
    body = {'status': 'completed', 'output': [{'type': 'reasoning'}, message(json.dumps(result))],
            'usage': {'input_tokens': 20, 'output_tokens': 7, 'total_tokens': 27,
                      'output_tokens_details': {'reasoning_tokens': 2}}}
    transport = Mock(return_value=httpx.Response(200, json=body))
    monkeypatch.setattr('app.services.app_task.generate', transport)
    response = AppTaskService().step(req)
    transport.assert_called_once()
    model, key, payload = transport.call_args.args
    assert (model, key, transport.call_args.kwargs['provider']) == ('gpt-6.2-sol', 'test-openai', 'openai')
    assert payload['model'] == model and payload['store'] is False
    assert 'contents' not in payload and req.screen.nodes[0].id not in payload['input']
    fmt = payload['text']['format']
    assert fmt['strict'] and set(fmt['schema']['required']) == set(fmt['schema']['properties'])
    assert response.status == 'confirm_result' and response.action is None and response.requires_confirmation
    assert response.completion.checks[0].label == '목적 화면'
    d = response.diagnostic
    assert d['completion_provider'] == 'openai' and d['called'] is True
    assert [d[k] for k in ('input_tokens','output_tokens','thinking_tokens','total_tokens')] == [20,7,2,27]


@pytest.mark.parametrize('field,value', [('OPENAI_API_KEY',''), ('APP_TASK_OPENAI_MODEL','gemini-other'),
                                          ('APP_TASK_PROVIDER','misspelled')])
def test_missing_or_invalid_openai_settings_never_call_gemini(monkeypatch, field, value):
    monkeypatch.setenv(field, value)
    transport = Mock()
    monkeypatch.setattr('app.services.app_task.generate', transport)
    result = AppTaskService().step(current_goal())
    transport.assert_not_called()
    assert result.status == 'unavailable' and result.diagnostic['called'] is False


@pytest.mark.parametrize('body', [
    {'status':'incomplete', 'output':[message('{}')]},
    {'status':'completed', 'output':[{'type':'message','role':'assistant','content':[{'type':'refusal'}]}]},
    {'status':'completed', 'output':[message('{}'), message('{}')]},
    {'status':'completed', 'output':[{'type':'function_call'}, message('{}')]},
    {'status':'completed', 'output':[message('{"tool":"tap","tool":"ask"}')]},
])
def test_incomplete_refused_or_ambiguous_reply_cannot_propose_an_action(body):
    send = Mock(return_value=httpx.Response(200, json=body))
    result = AppTaskService(send).step(current_goal())
    send.assert_called_once()
    assert result.status == 'unavailable' and result.action is None and result.completion is None
    assert result.diagnostic['called'] is True


@pytest.mark.parametrize('provider,model,key,url,headers', [
    ('openai','gpt-6.2-sol','test-openai',openai_responses.ENDPOINT,{'Authorization':'Bearer test-openai'}),
    ('openrouter', openai_responses.QWEN_FLASH_MODEL, 'test-router', openai_responses.OPENROUTER_ENDPOINT,
     {'Authorization': 'Bearer test-router'}),
    ('local',app_task_provider.LOCAL_MODEL,'',app_task_provider.LOCAL_RESPONSES_URL,{}),
])
def test_worker_posts_to_the_correct_endpoint(monkeypatch, provider, model, key, url, headers):
    client = Mock()
    client.__enter__ = Mock(return_value=client)
    client.__exit__ = Mock(return_value=False)
    reply = httpx.Response(200, content=b'{}')
    client.stream.return_value = nullcontext(reply)
    monkeypatch.setattr(app_task_provider.httpx, 'Client', Mock(return_value=client))
    monkeypatch.setattr(app_task_provider.httpx, 'HTTPTransport', Mock())
    app_task_provider._provider_worker(Mock(), model, key, b'{}', monotonic()+2, provider)
    client.stream.assert_called_once_with('POST', url, headers={**headers,'Content-Type':'application/json'}, content=b'{}')


def test_openrouter_uses_its_own_key_and_reports_round_trip(monkeypatch):
    monkeypatch.setenv('APP_TASK_PROVIDER', 'openrouter')
    monkeypatch.delenv('OPENROUTER_API_KEY', raising=False)
    req = current_goal()
    transport = Mock()
    monkeypatch.setattr('app.services.app_task.generate', transport)
    assert AppTaskService().step(req).diagnostic['reason_code'] == 'missing_api_key'
    transport.assert_not_called()

    monkeypatch.setenv('OPENROUTER_API_KEY', 'test-router')
    data = {'status': 'completed', 'output': [message(json.dumps(answer(req)))]}
    transport.return_value = app_task_provider.ProviderResponse(
        200, json.dumps(data).encode(), 42.0, http_round_trip_ms=31.0)
    result = AppTaskService().step(req)
    model, key, payload = transport.call_args.args
    assert (model, key, transport.call_args.kwargs['provider']) == (
        openai_responses.QWEN_FLASH_MODEL, 'test-router', 'openrouter')
    assert payload['reasoning'] == {'effort': 'none'}
    assert payload['text']['format']['strict'] and payload['model'] == model
    assert result.diagnostic['http_round_trip_ms'] == 31.0
    assert result.status == 'confirm_result' and result.completion


def test_local_completion_never_invokes_a_cloud_provider(monkeypatch):
    monkeypatch.setenv('APP_TASK_PROVIDER','local')
    monkeypatch.setenv('APP_TASK_COMPLETION_PROVIDER','jev')
    req = current_goal(planned=False)
    req.planning_mode = 'result_only'
    transport = Mock(side_effect=app_task_provider.ProviderError('provider_transport_error','observed'))
    jev = Mock()
    monkeypatch.setattr('app.services.app_task.generate', transport)
    response = AppTaskService(jev_sender=jev).step(req)
    jev.assert_not_called()
    transport.assert_called_once()
    assert transport.call_args.args[:2] == (app_task_provider.LOCAL_MODEL,'')
    assert transport.call_args.kwargs['provider'] == 'local'
    assert response.action is None and response.completion is None


@pytest.mark.parametrize('provider, model, payload_model', [
    ('openai', 'gemini-test', 'gemini-test'),
    ('gemini', 'gpt-test', 'gpt-test'),
    ('openai', 'gpt-test', 'gpt-other'),
    ('unknown', 'gpt-test', 'gpt-test'),
])
def test_provider_and_model_must_match_before_a_worker_starts(provider, model, payload_model):
    context = Mock()
    with pytest.raises(app_task_provider.ProviderError) as failed:
        app_task_provider.generate(model, 'test-key', {'model':payload_model}, provider=provider, _context=context)
    assert failed.value.model_call_state == 'not_started'
    context.Process.assert_not_called()


@pytest.mark.parametrize('choice,expected_status', [
    ('c0', 'proposed'),
    ('none', 'needs_input'),
    ('another', 'unavailable'),
])
@pytest.mark.parametrize('provider', ['local', 'openrouter', 'openai'])
def test_short_menu_only_proposes_a_grounded_button(monkeypatch, provider, choice, expected_status):
    from app.schemas.app_task import TaskApp
    from tests.test_app_task import request

    monkeypatch.setenv('APP_TASK_PROVIDER', provider)
    monkeypatch.setenv('OPENROUTER_API_KEY', 'test-router')
    monkeypatch.setenv('APP_TASK_OPENROUTER_SHORT_MENU', '1')
    req = request()
    req.goal = '검색 화면을 열어줘'
    req.planning_mode = 'goal_first'
    req.apps = [TaskApp(package=req.screen.package, label='공개 앱')]
    body = {'status': 'completed', 'output': [message(json.dumps({'choice': choice}))]}
    transport = Mock(return_value=httpx.Response(200, json=body))
    monkeypatch.setattr('app.services.app_task.generate', transport)

    result = AppTaskService().step(req)

    transport.assert_called_once()
    model, key, payload = transport.call_args.args
    expected_model, expected_key = ((app_task_provider.LOCAL_MODEL, '') if provider == 'local'
                                    else (openai_responses.QWEN_FLASH_MODEL, 'test-router'))
    assert (model, key) == (expected_model, expected_key)
    assert transport.call_args.kwargs['provider'] == ('local' if provider == 'local' else 'openrouter')
    assert payload['model'] == model
    schema = payload['text']['format']['schema']
    assert schema['properties']['choice']['enum'] == ['c0', 'none']
    assert payload['max_output_tokens'] == 32
    assert result.status == expected_status and result.completion is None
    if choice == 'c0':
        assert result.requires_confirmation and result.action.tool == 'tap'
        assert result.action.target_id == req.screen.nodes[0].id and not result.action.advances_subgoal
    else:
        assert result.action is None
