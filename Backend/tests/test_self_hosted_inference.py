import json
from types import SimpleNamespace
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import httpx
import pytest
from fastapi.testclient import TestClient

from app.clients.self_hosted import InferenceEndpoint
from app.services.goal_llm import LocalGoalLlm
from app.services.goal_resolution import GoalResolutionService
from app.repositories.task_graphs import NoCompatibleGraphError
from inference.whisper_stt import create_app as create_worker, load_model
from tests.test_transcription import audio_request, stt_result


TOKEN = 'test-owned-server-token-' + 'x' * 32
CANDIDATES = [{'goal_type': 'settings.font_size.increase', 'display_name': '글자 크게', 'target_app': 'android_settings',
    'slots': {'setting': 'font_size', 'direction': 'increase'}}]


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', 'http://127.0.0.1:8092/v1/chat/completions')
    monkeypatch.setenv('SELF_HOSTED_LLM_TOKEN', TOKEN)
    monkeypatch.setenv('SELF_HOSTED_LLM_MODEL', 'our-local-model')
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN', TOKEN)


def completion(content, finish='stop'):
    return httpx.Response(200, json={'choices': [{'finish_reason': finish, 'message': {'content': content}}]})


def test_llm_receives_only_utterance_and_approved_catalog(configured):
    def send(payload, **kwargs):
        assert payload['model'] == 'our-local-model'
        assert payload['messages'][1] == {'role': 'user', 'content': '휴대폰 전체 문자가 깨알 같아요'}
        assert 'accessibility_tree' not in json.dumps(payload)
        assert len(payload['messages']) == 2 and kwargs['timeout'] == 8
        return completion('{"goal_type":"settings.font_size.increase"}')
    assert LocalGoalLlm(send).select('휴대폰 전체 문자가 깨알 같아요', CANDIDATES) == 'settings.font_size.increase'


@pytest.mark.parametrize('content', ['{"goal_type":null}', '{"goal_type":"delete.everything"}',
    '{"goal_type": ["settings.font_size.increase"]}', '{"goal_type":"settings.font_size.increase","slots":{"direction":"decrease"}}',
    '```json\n{"goal_type":"settings.font_size.increase"}\n```', 'not json', '[]', 'null'])
def test_llm_abstains_on_noncanonical_content(configured, content):
    assert LocalGoalLlm(lambda *a, **kw: completion(content)).select('깨알 같아요', CANDIDATES) is None


@pytest.mark.parametrize('response', [httpx.Response(503), httpx.Response(302), httpx.Response(200, json={}),
    completion('{"goal_type":"settings.font_size.increase"}', 'length')])
def test_llm_errors_and_truncated_generation_abstain(configured, response):
    assert LocalGoalLlm(lambda *a, **kw: response).select('깨알 같아요', CANDIDATES) is None


def test_llm_disabled_or_timeout_returns_clarification(configured, monkeypatch):
    def timeout(*a, **kw):
        raise httpx.ReadTimeout('timeout')
    assert LocalGoalLlm(timeout).select('깨알 같아요', CANDIDATES) is None
    monkeypatch.delenv('SELF_HOSTED_LLM_URL')
    assert LocalGoalLlm(lambda *a, **kw: pytest.fail('no network without config')).select('깨알 같아요', CANDIDATES) is None


class Graphs:
    def select(self, goal):
        if not goal.goal_type.startswith('settings.font_size.'):
            raise NoCompatibleGraphError('unsupported')


class Llm:
    def __init__(self, answer='settings.font_size.increase'):
        self.calls = []; self.answer = answer
    def select(self, text, candidates):
        self.calls.append((text, candidates))
        return self.answer


def resolve(service, text):
    return service.resolve(SimpleNamespace(source_text=text, request_id='request', session_id='session',
        device_context=SimpleNamespace(foreground_package=None)), 0)


def test_only_keyword_miss_calls_llm_and_never_executes():
    llm = Llm(); service = GoalResolutionService(Graphs(), llm)
    body = resolve(service, '휴대폰 전체 문자가 깨알 같아요')
    assert len(llm.calls) == 1
    assert len(llm.calls[0][1]) == 2
    assert body.resolution_status == 'confirmation_required'
    assert body.goal.goal_type == 'settings.font_size.increase'
    assert body.goal.slots['direction'] == 'increase'
    assert body.session_version == 0


@pytest.mark.parametrize('text,status', [('글자 크기 키워줘', 'confirmation_required'),
    ('글자 크기 키우지 마', 'needs_clarification'), ('와이파이 켜줘', 'unsupported'),
    ('글자 크기를 크게 작게', 'needs_clarification')])
def test_known_negative_ambiguous_and_unsupported_requests_do_not_call_llm(text, status):
    llm = Llm(); response = resolve(GoalResolutionService(Graphs(), llm), text)
    assert response.resolution_status == status
    assert not llm.calls


@pytest.mark.parametrize('answer', [None, 'invented.goal', 'coupang.search'])
def test_no_llm_result_or_nonapproved_goal_cannot_start(answer):
    response = resolve(GoalResolutionService(Graphs(), Llm(answer)), '문자가 깨알 같아요')
    assert response.resolution_status == 'needs_clarification' and response.goal is None


def test_transport_ignores_proxy_environment_and_does_not_follow_redirects(monkeypatch):
    captured = {}
    class Client:
        def __init__(self, **kwargs): captured.update(kwargs)
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def post(self, url, **kwargs):
            captured.update(url=url, **kwargs)
            return httpx.Response(307)
    monkeypatch.setattr(httpx, 'Client', Client)
    response = InferenceEndpoint('https://our-inference.example/v1', TOKEN).post({'private': 'data'}, timeout=8)
    assert response.status_code == 307
    assert captured['follow_redirects'] is False and captured['trust_env'] is False
    assert captured['headers'] == {'Authorization': 'Bearer ' + TOKEN}


@pytest.mark.parametrize('url', ['', 'file:///tmp/audio', 'https://user:pass@our-host/v1',
    'https://our-host/v1?token=secret', 'https://our-host/v1#fragment'])
def test_invalid_endpoint_rejected(configured, monkeypatch, url):
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', url)
    with pytest.raises(ValueError): InferenceEndpoint.from_env('SELF_HOSTED_LLM')


def test_worker_auth_pcm_and_korean_response(configured):
    seen = []
    def transcribe(model, pcm):
        seen.append(pcm)
        assert model == 'fake-loaded-model' and len(pcm) == 3200
        return ' 글자 크기 줄여줘 '
    with TestClient(create_worker(lambda: 'fake-loaded-model', transcribe)) as client:
        body = audio_request().model_dump()
        assert client.post('/v1/transcriptions', json=body).status_code == 401
        result = client.post('/v1/transcriptions', json=body, headers={'Authorization': 'Bearer ' + TOKEN})
        assert result.json() == stt_result()
        invalid = {**body, 'mime_type': 'SECRET_INVALID_INPUT'}
        result = client.post('/v1/transcriptions', json=invalid, headers={'Authorization': 'Bearer ' + TOKEN})
        assert result.status_code == 422 and 'SECRET_INVALID_INPUT' not in result.text
    assert len(seen) == 1


def test_worker_busy_does_not_queue_private_audio(configured):
    entered, release = Event(), Event()
    def transcribe(model, pcm):
        entered.set(); assert release.wait(5); return '글자 크게'
    with TestClient(create_worker(lambda: None, transcribe)) as client, ThreadPoolExecutor() as pool:
        def request():
            return client.post('/v1/transcriptions', json=audio_request().model_dump(),
                headers={'Authorization': 'Bearer ' + TOKEN})
        first = pool.submit(request)
        assert entered.wait(5)
        try: assert request().status_code == 503
        finally: release.set()
        assert first.result().status_code == 200


def test_worker_requires_strong_token_and_local_weights(monkeypatch, tmp_path):
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN', '')
    with pytest.raises(RuntimeError), TestClient(create_worker(lambda: pytest.fail('must reject before model load'))): pass
    monkeypatch.setenv('WHISPER_MODEL_PATH', str(tmp_path))
    with pytest.raises(RuntimeError): load_model()
