import json
import httpx
import pytest

from app.clients.screen_state_llm import LocalScreenStateLlm


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', 'https://inference.example.test/v1/chat/completions')
    monkeypatch.setenv('SELF_HOSTED_LLM_TOKEN', 'test-token')
    monkeypatch.setenv('SELF_HOSTED_LLM_MODEL', 'test-model')


def reply(content, finish='stop'):
    return httpx.Response(200, json={'choices': [{'finish_reason': finish, 'message': {'content': content}}]})


def test_configured_resolver_has_bounded_request_and_structured_reply(configured):
    calls = []
    def send(payload, timeout):
        calls.append((payload, timeout))
        return reply('{"state_id":"S2","evidence_ids":["n1"]}')
    assert LocalScreenStateLlm(send).resolve({'nodes': []}) == {'state_id': 'S2', 'evidence_ids': ['n1']}
    assert calls[0][1] <= 1.5
    assert calls[0][0]['model'] == 'test-model'


@pytest.mark.parametrize('content,finish', [('not JSON', 'stop'), ('{"state_id":"S2","state_id":"S3"}', 'stop'), ('{}', 'length')])
def test_bad_model_responses_abstain(configured, content, finish):
    assert LocalScreenStateLlm(lambda *a, **kw: reply(content, finish)).resolve({}) is None


def test_missing_configuration_does_not_call_provider(monkeypatch):
    monkeypatch.delenv('SELF_HOSTED_LLM_MODEL', raising=False)
    def never(*a, **kw):
        pytest.fail('must not call an unconfigured model')
    assert LocalScreenStateLlm(never).resolve({}) is None
