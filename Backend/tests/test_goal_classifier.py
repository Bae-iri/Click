"""Classifier privacy/protocol boundaries and the real authenticated API path."""
from copy import deepcopy
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.clients.self_hosted import InferenceEndpoint
from app.main import create_app
from app.models.agent_session import AgentSession
from app.services.goal_inputs import GoalInputRequirements
from app.services.goal_llm import LocalGoalLlm
from scripts.check_goal_llm import main as check_goal_llm
from tests.helpers import register
from tests.test_goal_preparation import Flow

GOAL = 'settings.font_size.increase'
CANDIDATES = [{'goal_type': GOAL, 'display_name': '글자 크기 키우기',
               'target_app': 'android_settings', 'slots': {'setting': 'font_size', 'direction': 'increase'}}]
FIELDS = GoalInputRequirements().document['families'][0]['fields']


def completion(content):
    if not isinstance(content, str):
        content = json.dumps(content, ensure_ascii=False)
    return httpx.Response(200, json={'choices': [{'finish_reason': 'stop', 'message': {'content': content}}]})


@pytest.fixture(autouse=True)
def configured(monkeypatch):
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', 'https://inference.example/v1/chat/completions')
    monkeypatch.setenv('SELF_HOSTED_LLM_TOKEN', 'unit-test-only-secret')
    monkeypatch.setenv('SELF_HOSTED_LLM_MODEL', 'click-goal')


@pytest.mark.parametrize('partial', [False, True])
@pytest.mark.parametrize('private,secret', [
    ('제 이름은 김민수이고', '김민수'),
    ('집 주소는 서울특별시 예시로 123인데', '서울특별시'),
    ('비밀번호는 very-private-password인데', 'very-private-password'),
    ('인증번호: 123456,', '123456'),
    ('이메일은 person@example.com,', 'person@example.com'),
    ('전화번호는 010-1234-5678,', '010-1234-5678'),
    ('연락처 +82 10 1234 5678,', '1234'),
    ('주민번호 900101-1234567,', '900101'),
    ('https://example.com/private?token=secret', 'example.com'),
    ('０１０-１２３４-５６７８', '1234'),
    ('010\u200b1234\u200b5678', '1234'),
])
def test_only_minimized_text_and_catalog_reach_model(private, secret, partial, caplog):
    source = private + ' 휴대폰 전체 문자가 깨알 같아요'
    candidates, fields = deepcopy(CANDIDATES), deepcopy(FIELDS)
    candidates[0]['user_id'] = 'PRIVATE_USER_ID'
    fields[0]['current_value'] = 'PRIVATE_CURRENT_VALUE'
    calls = []
    def send(payload, *, timeout):
        calls.append(payload)
        encoded = json.dumps(payload, ensure_ascii=False)
        assert secret not in encoded
        assert 'PRIVATE_USER_ID' not in encoded and 'PRIVATE_CURRENT_VALUE' not in encoded
        assert payload['messages'][1]['content'].endswith('휴대폰 전체 문자가 깨알 같아요')
        assert '[REDACTED]' in payload['messages'][1]['content']
        assert len(payload['messages']) == 2 and timeout == 8.0
        result = {'goal_type': GOAL}
        if partial:
            result['inputs'] = {'direction': 'increase'}
        return completion(result)
    llm = LocalGoalLlm(send)
    result = llm.interpret_inputs(source, candidates, fields) if partial else llm.select(source, candidates)
    assert result is not None and len(calls) == 1
    assert secret not in caplog.text


@pytest.mark.parametrize('source', ['', ' ', 'a' * 2001, '비밀번호는 very-private',
                                    '글씨 키우지 마', '글씨 키워줘 취소'])
def test_empty_sensitive_only_oversized_or_cancelled_input_does_not_call_model(source):
    def unexpected(*args, **kwargs):
        pytest.fail('Input must be rejected before network access')
    assert LocalGoalLlm(unexpected).interpret_inputs(source, CANDIDATES, FIELDS) is None


def test_missing_scope_remains_a_question_even_if_model_invents_it():
    llm = LocalGoalLlm(lambda *a, **k: completion({'goal_type': GOAL, 'inputs': {
        'scope': 'system', 'direction': 'increase'}}))
    assert llm.interpret_inputs('문자가 깨알 같아요', CANDIDATES, FIELDS)['inputs'] == {'direction': 'increase'}
    assert llm.interpret_inputs('휴대폰 전체 문자가 깨알 같아요', CANDIDATES, FIELDS)['inputs'] == {
        'scope': 'system', 'direction': 'increase'}


@pytest.mark.parametrize('values', [
    {'direction': 'delete'}, {'direction': 'decrease'}, {'scope': 'all'}, {'action': 'tap'},
    {'app_name': 'invented-app'}, {'app_name': 'person@example.com'}, {'app_name': '[REDACTED]'},
    {'app_name': 'a' * 81}, {'direction': ['increase']}, {'app_name': '카카오톡\n'},
])
def test_unapproved_ungrounded_sensitive_or_contradictory_fields_are_rejected(values):
    llm = LocalGoalLlm(lambda *a, **k: completion({'goal_type': GOAL, 'inputs': values}))
    assert llm.interpret_inputs('카카오톡 문자가 깨알 같아요', CANDIDATES, FIELDS) is None


def test_grounded_app_can_be_collected_but_legacy_mode_cannot_substitute_system():
    llm = LocalGoalLlm(lambda *a, **k: completion({'goal_type': GOAL, 'inputs': {'app_name': '카카오톡'}}))
    assert llm.interpret_inputs('카카오톡 문자가 깨알 같아요', CANDIDATES, FIELDS)['inputs'] == {'app_name': '카카오톡'}
    llm = LocalGoalLlm(lambda *a, **k: completion({'goal_type': GOAL}))
    assert llm.select('카카오톡 문자가 깨알 같아요', CANDIDATES) is None


@pytest.mark.parametrize('content', [
    '{"goal_type":"settings.font_size.increase","goal_type":"settings.font_size.increase","inputs":{}}',
    '{"goal_type":"settings.font_size.increase","inputs":{"direction":"increase","direction":"increase"}}',
    {'goal_type': 'invented.goal', 'inputs': {}},
    {'goal_type': GOAL, 'inputs': {}, 'command': 'tap'},
    {'goal_type': None, 'inputs': {}},
    {'goal_type': GOAL, 'inputs': {'app_name': 'x' * 4096}},
])
def test_strict_json_contract(content):
    llm = LocalGoalLlm(lambda *a, **k: completion(content))
    assert llm.interpret_inputs('문자가 깨알 같아요', CANDIDATES, FIELDS) is None


def test_deeply_nested_json_is_a_clarification_not_a_server_error():
    content = '[' * 1500 + '0' + ']' * 1500
    llm = LocalGoalLlm(lambda *a, **k: completion(content))
    assert llm.interpret_inputs('문자가 깨알 같아요', CANDIDATES, FIELDS) is None


@pytest.mark.parametrize('partial', [False, True])
@pytest.mark.parametrize('source', ['휴대폰 전체 문자를 작게 해줘', '문자를 크게 하고 작게 해줘'])
def test_model_cannot_override_explicit_or_ambiguous_direction(partial, source):
    content = {'goal_type': GOAL}
    if partial:
        content['inputs'] = {}  # Omitting the slot must not bypass consistency checks.
    llm = LocalGoalLlm(lambda *a, **k: completion(content))
    result = llm.interpret_inputs(source, CANDIDATES, FIELDS) if partial else llm.select(source, CANDIDATES)
    assert result is None


@pytest.mark.parametrize('tool_calls,multiple', [(True, False), (False, True)])
def test_tool_calls_and_multiple_completions_are_rejected(tool_calls, multiple):
    data = completion({'goal_type': GOAL, 'inputs': {}}).json()
    if tool_calls:
        data['choices'][0]['message']['tool_calls'] = [{'function': {'name': 'tap'}}]
    if multiple:
        data['choices'] *= 2
    llm = LocalGoalLlm(lambda *a, **k: httpx.Response(200, json=data))
    assert llm.interpret_inputs('문자가 깨알 같아요', CANDIDATES, FIELDS) is None


@pytest.mark.parametrize('url,valid', [
    ('https://inference.example/v1/chat/completions', True),
    ('http://127.0.0.1:8092/v1/chat/completions', True),
    ('http://localhost:8092/v1/chat/completions', True),
    ('http://[::1]:8092/v1/chat/completions', True),
    ('http://inference.example/v1/chat/completions', False),
    ('http://192.168.0.3/v1/chat/completions', False),
])
def test_remote_connection_requires_tls(monkeypatch, url, valid):
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', url)
    calls = []
    def send(*args, **kwargs):
        calls.append(True)
        return completion({'goal_type': GOAL})
    assert (LocalGoalLlm(send).select('휴대폰 전체 문자가 깨알 같아요', CANDIDATES) == GOAL) == valid
    assert bool(calls) == valid


@pytest.mark.parametrize('fault', [None, 'timeout', 'bad_output'])
def test_api_masks_input_and_keeps_confirmation_or_clarification(monkeypatch, caplog, fault):
    raw = '제 이름은 김민수이고 010-1234-5678, 문자가 깨알 같아요'
    calls = []
    def post(endpoint, payload, *, timeout):
        calls.append(payload)
        assert endpoint.url.startswith('https://')
        encoded = json.dumps(payload, ensure_ascii=False)
        assert '김민수' not in encoded and '010-1234-5678' not in encoded
        if fault == 'timeout':
            raise httpx.ReadTimeout('PRIVATE_ERROR_CONTENT')
        return completion({'goal_type': 'invented.goal' if fault else GOAL, 'inputs': {'direction': 'increase'}})
    monkeypatch.setattr(InferenceEndpoint, 'post', post)
    app = create_app()
    with TestClient(app) as client:
        register(client)
        flow = Flow(client, app, raw)
        body = flow.resolve()
        assert body['resolution_status'] == 'needs_clarification' and body['goal'] is None
        assert body['preparation']['question']['field'] == 'scope'
        assert body['preparation']['values'] == ({} if fault else {'direction': 'increase'})
        assert 'guide_command' not in body and len(calls) == 1
        if fault is None:
            ready = flow.answer('system')
            assert ready['resolution_status'] == 'confirmation_required'
            assert ready['goal']['goal_type'] == GOAL and len(calls) == 1
        with app.state.session_factory() as db:
            row = db.get(AgentSession, flow.session)
            assert row.episode_id is None and row.version == 0
    assert '김민수' not in caplog.text and '010-1234-5678' not in caplog.text
    assert 'PRIVATE_ERROR_CONTENT' not in caplog.text


@pytest.mark.parametrize('available', [True, False])
def test_connection_check_requires_successful_classification(monkeypatch, capsys, available):
    calls = []
    def post(endpoint, payload, *, timeout):
        calls.append(payload)
        if not available:
            raise httpx.ConnectError('PRIVATE_CONNECTION_ERROR')
        direction = 'decrease' if '더 작게' in payload['messages'][1]['content'] else 'increase'
        return completion({'goal_type': 'settings.font_size.' + direction, 'inputs': {'direction': direction}})
    monkeypatch.setattr(InferenceEndpoint, 'post', post)
    assert check_goal_llm([]) == (0 if available else 1)
    output = capsys.readouterr().out
    assert ('3/3' if available else '0/3') in output and len(calls) == 3
    assert 'PRIVATE_CONNECTION_ERROR' not in output and 'unit-test-only-secret' not in output
    assert 'inference.example' not in output


def test_connection_check_without_configuration_does_not_connect(monkeypatch, capsys):
    monkeypatch.delenv('SELF_HOSTED_LLM_MODEL')
    def unexpected(*args, **kwargs):
        pytest.fail('Unconfigured check must not connect')
    monkeypatch.setattr(InferenceEndpoint, 'post', unexpected)
    assert check_goal_llm([]) == 2
    assert 'CONFIG_ERROR' in capsys.readouterr().out
