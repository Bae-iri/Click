"""Jev's real protocol boundary and authenticated preparation path, without network calls."""
from copy import deepcopy
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from app.clients.self_hosted import InferenceEndpoint
from app.main import create_app
from app.models.agent_session import AgentSession
from app.services.goal_classifier import configured_goal_classifier
from app.services.goal_jev import JEV_ENDPOINT, JevGoalClassifier
from app.services.goal_inputs import GoalInputRequirements
from app.services.goal_llm import LocalGoalLlm
from scripts.check_goal_llm import main as check_goal_llm
from tests.helpers import register
from tests.test_goal_preparation import Flow


GOAL = 'settings.font_size.increase'
CANDIDATES = [
    {'goal_type': 'settings.font_size.' + direction, 'display_name': label,
     'target_app': 'android_settings', 'slots': {'setting': 'font_size', 'direction': direction}}
    for direction, label in [('increase', '글자 크기 키우기'), ('decrease', '글자 크기 줄이기')]]
FIELDS = GoalInputRequirements().document['families'][0]['fields']


@pytest.fixture(autouse=True)
def configured(monkeypatch):
    monkeypatch.setenv('GOAL_CLASSIFIER_PROVIDER', 'jev')
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-secret')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')


def response_for(payload, choice='goal_0', probability=0.98):
    keys = payload['questions']['goal']['criteria']
    probabilities = {k: probability if k == choice else (1 - probability) / (len(keys) - 1) for k in keys}
    return {'model': payload['model'], 'answers': {'goal': {
        'type': 'choice', 'choice': choice, 'probabilities': probabilities,
        'confidence': (probability - 1 / len(keys)) / (1 - 1 / len(keys)),
    }}, 'usage': {'input_tokens': 100, 'output_tokens': 0}}


def success(payload, *, timeout):
    return httpx.Response(200, json=response_for(payload))


def test_provider_is_explicit_and_default_remains_self_hosted(monkeypatch):
    assert isinstance(configured_goal_classifier(), JevGoalClassifier)
    monkeypatch.delenv('GOAL_CLASSIFIER_PROVIDER')
    assert isinstance(configured_goal_classifier(), LocalGoalLlm)
    monkeypatch.setenv('GOAL_CLASSIFIER_PROVIDER', 'misspelled')
    with pytest.raises(ValueError):
        configured_goal_classifier()


def test_minimized_choice_protocol_and_grounded_inputs(caplog):
    candidates = deepcopy(CANDIDATES)
    candidates[0]['user_id'] = 'PRIVATE_USER'
    calls = []
    def send(payload, *, timeout):
        calls.append(payload)
        encoded = json.dumps(payload, ensure_ascii=False)
        for secret in ('김민수', '010-1234-5678', 'PRIVATE_USER', 'test-only-secret'):
            assert secret not in encoded
        assert set(payload) == {'model', 'state', 'questions'}
        assert '[REDACTED]' in payload['state'] and timeout == 8.0
        assert {'clarify', 'unsupported', 'cancel', 'multiple'} <= set(payload['questions']['goal']['criteria'])
        return success(payload, timeout=timeout)
    result = JevGoalClassifier(send).interpret_inputs(
        '제 이름은 김민수이고 010-1234-5678, 휴대폰 전체 문자가 깨알 같아요', candidates, FIELDS)
    assert result == {'goal_type': GOAL, 'inputs': {'scope': 'system', 'direction': 'increase'}}
    assert len(calls) == 1 and '김민수' not in caplog.text


def test_missing_fields_are_not_generated():
    fields = deepcopy(FIELDS) + [{'id': 'reading_distance', 'kind': 'choice',
                                  'choices': [{'value': 'near', 'label': '가까이'}]}]
    assert JevGoalClassifier(success).interpret_inputs('문자가 깨알 같아요', CANDIDATES, fields) == {
        'goal_type': GOAL, 'inputs': {'direction': 'increase'}}


@pytest.mark.parametrize('source', ['휴대폰 전체 문자를 작게 해줘', '문자를 크게 하고 작게 해줘',
                                    '카카오톡 문자가 깨알 같아요'])
def test_model_cannot_override_direction_or_app_scope(source):
    assert JevGoalClassifier(success).interpret_inputs(source, CANDIDATES, FIELDS) is None


@pytest.mark.parametrize('source,accepted', [
    ('유튜브 재생이 잘 안 들려. 더 우렁차게 들려줘', True),
    ('음악을 더 크게 듣고 싶어', True),
    ('유튜브 앱에서만 재생 소리를 크게 해줘', False),
    ('카카오톡 앱에서만 동영상 소리를 크게 해줘', False),
    ('유튜브 소리를 줄이고 음악 소리를 키워줘', False),
])
def test_media_context_does_not_authorize_app_only_or_conflicting_volume_requests(source, accepted):
    from pathlib import Path
    catalog = json.loads((Path(__file__).parents[1] / 'app/task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
    candidates = [goal for goal in catalog['goals'] if goal['execution_status'] == 'approved']
    fields = GoalInputRequirements().family('settings.volume.increase')['fields']
    def send(payload, **kwargs):
        criteria = payload['questions']['goal']['criteria']
        choice = next(key for key, value in criteria.items()
            if isinstance(value, dict) and value['goal_type'] == 'settings.volume.increase')
        return httpx.Response(200, json=response_for(payload, choice))
    result = JevGoalClassifier(send).interpret_inputs(source, candidates, fields)
    if accepted:
        assert result['goal_type'] == 'settings.volume.increase'
        assert result['inputs']['audio_stream'] == 'media'
    else:
        assert result is None


@pytest.mark.parametrize('source', ['', ' ', 'a' * 2001, '비밀번호는 private', '글씨 키우지 마', '글씨 키워줘 취소'])
def test_rejected_input_never_reaches_provider(source):
    assert JevGoalClassifier(lambda *a, **kw: pytest.fail('unexpected call')).select(source, CANDIDATES) is None


@pytest.mark.parametrize('key,value', [('TYPESAFE_API_KEY', ''), ('TYPESAFE_API_KEY', 'bad\nkey'),
    ('JEV_MODEL', 'jev-latest'), ('JEV_MIN_CONFIDENCE', 'nan'), ('JEV_MIN_CONFIDENCE', '0'),
    ('JEV_MIN_CONFIDENCE', '1.1'), ('JEV_MIN_CONFIDENCE', 'invalid')])
def test_invalid_configuration_never_connects(monkeypatch, key, value):
    monkeypatch.setenv(key, value)
    assert JevGoalClassifier(lambda *a, **kw: pytest.fail('unexpected call')).select('깨알 같아요', CANDIDATES) is None


@pytest.mark.parametrize('choice', ['clarify', 'unsupported', 'unsupported_app', 'unrelated', 'cancel', 'multiple'])
def test_abstention_choices_never_become_goals(choice):
    def send(payload, **kwargs):
        return httpx.Response(200, json=response_for(payload, choice))
    assert JevGoalClassifier(send).select('깨알 같아요', CANDIDATES) is None


@pytest.mark.parametrize('choice', ['unsupported', 'unsupported_app', 'unrelated'])
@pytest.mark.parametrize('probability', [0.55, 0.98])
def test_preparation_preserves_only_confident_unsupported_reasons(choice, probability):
    def send(payload, **kwargs):
        return httpx.Response(200, json=response_for(payload, choice, probability))
    result = JevGoalClassifier(send).interpret_inputs('정부24에서 서류를 발급해줘', CANDIDATES, [])
    assert result == ({'goal_type': None, 'inputs': {}, 'reason': choice} if probability == 0.98 else None)


@pytest.mark.parametrize('source,choice,status,reason', [
    ('정부24에서 주민등록등본 발급해줘', 'unsupported_app', 'unsupported', 'unsupported_app'),
    ('주민등록등본 발급해줘', 'unsupported_app', 'unsupported', 'unsupported_app'),
    ('정부24에서 글자 크기 키워줘', 'unsupported_app', 'unsupported', 'unsupported_app'),
    ('휴대폰의 새로운 설정 기능을 만들어줘', 'unsupported', 'unsupported', 'unsupported'),
    ('재미있는 시 한 편 써줘', 'unrelated', 'unsupported', 'unrelated'),
    ('이거 좀 바꿔줘', 'clarify', 'needs_clarification', None),
])
def test_authenticated_preparation_separates_unsupported_from_clarification(monkeypatch, source, choice, status, reason):
    calls = []
    def post(self, payload, **kwargs):
        calls.append(payload)
        return httpx.Response(200, json=response_for(payload, choice))
    monkeypatch.setattr(JevGoalClassifier, '_post', post)
    app = create_app()
    with TestClient(app) as client:
        register(client)
        flow = Flow(client, app, source)
        body = flow.resolve()
        assert body['resolution_status'] == status
        assert body['goal'] is None and body['preparation']['graph_reference'] is None
        assert body['metadata']['goal_selection']['candidates'] == []
        if reason:
            assert body['preparation']['status'] == 'unsupported'
            assert body['preparation']['question'] is None and body['preparation']['missing_fields'] == []
            assert body['metadata']['goal_interpretation']['reason'] == reason
            if reason == 'unsupported_app':
                assert '아직 클릭이 지원하지 않는 앱이에요.' in body['message']
            restored = flow.resolve()
            assert restored['message'] == body['message']
            assert restored['metadata']['goal_interpretation']['reason'] == reason
            assert restored['preparation']['status'] == 'unsupported'
            from app.services.goal_resolution import font_goal
            assert client.post('/api/v1/agent/commands', json={**flow.start_payload(),
                'goal': font_goal('increase').model_dump()}).status_code == 409
            flow.text = '휴대폰 전체 글자 크기 키워줘'
            corrected = flow.resolve(preparation_reset=True)
            assert corrected['resolution_status'] == 'confirmation_required'
            assert 'goal_interpretation' not in corrected['metadata']
        else:
            assert body['preparation']['question']['field'] == 'request'
        assert len(calls) == 1
        with app.state.session_factory() as db:
            row = db.get(AgentSession, flow.session)
            assert row.episode_id is None and row.version == 0


def test_catalog_only_app_is_unsupported_without_asking_model(monkeypatch):
    monkeypatch.setattr(JevGoalClassifier, '_post', lambda *a, **kw: pytest.fail('known unsupported app'))
    app = create_app()
    for goal in app.state.turn_service.goal_preparation.catalog['goals']:
        if goal['target_app'] == 'coupang':
            goal['execution_status'] = 'catalog_only'
    with TestClient(app) as client:
        register(client)
        body = Flow(client, app, '쿠팡 상품 검색해줘').resolve()
        assert body['resolution_status'] == 'unsupported'
        assert body['metadata']['goal_interpretation']['reason'] == 'unsupported_app'
        assert '아직 클릭이 지원하지 않는 앱이에요.' in body['message']
        assert body['preparation']['question'] is None and body['goal'] is None


@pytest.mark.parametrize('fault', ['low_confidence', 'false_confidence', 'missing_probability', 'wrong_sum',
    'invalid_probability', 'boolean_probability', 'not_top', 'unknown_choice', 'wrong_model',
    'extra_answer', 'extra_action', 'missing_confidence', 'duplicate_key', 'invalid_json', 'oversized'])
def test_invalid_or_uncertain_response_is_clarification(fault):
    def send(payload, **kwargs):
        data = response_for(payload, probability=0.55 if fault in {'low_confidence', 'false_confidence'} else 0.98)
        answer = data['answers']['goal']
        if fault == 'false_confidence': answer['confidence'] = 1.0
        if fault == 'missing_probability': answer['probabilities'].pop('cancel')
        if fault == 'wrong_sum': answer['probabilities']['cancel'] = 0.5
        if fault == 'invalid_probability': answer['probabilities']['cancel'] = -0.1
        if fault == 'boolean_probability': answer['probabilities']['cancel'] = False
        if fault == 'not_top': answer['choice'] = 'goal_1'
        if fault == 'unknown_choice': answer['choice'] = 'invented'
        if fault == 'wrong_model': data['model'] = 'jev-0.0.0'
        if fault == 'extra_answer': data['answers']['execute'] = {'type': 'noul', 'noul': 1}
        if fault == 'extra_action': answer['action'] = 'tap'
        if fault == 'missing_confidence': answer.pop('confidence')
        if fault == 'duplicate_key':
            return httpx.Response(200, text=json.dumps(data).replace('"choice":', '"choice": "goal_0", "choice":'))
        if fault == 'invalid_json': return httpx.Response(200, text='not json')
        if fault == 'oversized': return httpx.Response(200, text=' ' * 65537)
        return httpx.Response(200, json=data)
    assert JevGoalClassifier(send).select('깨알 같아요', CANDIDATES) is None


@pytest.mark.parametrize('status', [401, 429, 500, 302])
def test_http_errors_do_not_retry_or_fall_back(monkeypatch, status):
    monkeypatch.setattr(InferenceEndpoint, 'post', lambda *a, **kw: pytest.fail('no provider fallback'))
    calls = []
    def send(*args, **kwargs):
        calls.append(True)
        return httpx.Response(status)
    assert JevGoalClassifier(send).select('깨알 같아요', CANDIDATES) is None
    assert len(calls) == 1


def test_transport_uses_fixed_https_bearer_without_proxies_or_redirects(monkeypatch):
    real_client = httpx.Client
    def handle(request):
        assert str(request.url) == JEV_ENDPOINT
        assert request.headers['Authorization'] == 'Bearer test-only-secret'
        return success(json.loads(request.content), timeout=8.0)
    def client(**kwargs):
        assert kwargs['follow_redirects'] is False and kwargs['trust_env'] is False
        assert kwargs['timeout'].read == 8.0 and kwargs['timeout'].connect == 3.0
        return real_client(transport=httpx.MockTransport(handle), **kwargs)
    monkeypatch.setattr(httpx, 'Client', client)
    assert JevGoalClassifier().select('깨알 같아요', CANDIDATES) == GOAL


@pytest.mark.parametrize('timeout', [False, True])
def test_authenticated_flow_requires_scope_and_start_confirmation(monkeypatch, caplog, timeout):
    calls = []
    def post(self, payload, **kwargs):
        calls.append(payload)
        assert '김민수' not in payload['state']
        if timeout: raise httpx.ReadTimeout('PRIVATE_ERROR')
        return success(payload, **kwargs)
    monkeypatch.setattr(JevGoalClassifier, '_post', post)
    monkeypatch.setattr(InferenceEndpoint, 'post', lambda *a, **kw: pytest.fail('no provider fallback'))
    app = create_app()
    with TestClient(app) as client:
        register(client)
        flow = Flow(client, app, '제 이름은 김민수이고 문자가 깨알 같아요')
        body = flow.resolve()
        assert isinstance(app.state.turn_service.goal_preparation.llm, JevGoalClassifier)
        assert body['resolution_status'] == 'needs_clarification' and body['goal'] is None
        assert body['preparation']['question']['field'] == 'scope'
        assert body['preparation']['values'] == ({} if timeout else {'direction': 'increase'})
        if not timeout:
            ready = flow.answer('system')
            assert ready['resolution_status'] == 'confirmation_required'
            assert ready['goal']['goal_type'] == GOAL
        assert len(calls) == 1
        with app.state.session_factory() as db:
            row = db.get(AgentSession, flow.session)
            assert row.episode_id is None and row.version == 0
    assert 'PRIVATE_ERROR' not in caplog.text and '김민수' not in caplog.text


def test_connection_check_exercises_jev(monkeypatch, capsys):
    calls = []
    def post(self, payload, **kwargs):
        calls.append(payload)
        direction = 'decrease' if '더 작게' in payload['state'] else 'increase'
        criteria = payload['questions']['goal']['criteria']
        choice = next(k for k, v in criteria.items() if isinstance(v, dict) and v['slots']['direction'] == direction)
        return httpx.Response(200, json=response_for(payload, choice))
    monkeypatch.setattr(JevGoalClassifier, '_post', post)
    assert check_goal_llm([]) == 0
    output = capsys.readouterr().out
    assert len(calls) == 3 and '3/3' in output and 'test-only-secret' not in output
