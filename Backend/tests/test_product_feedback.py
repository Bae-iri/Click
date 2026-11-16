"""Authenticated feedback ingestion never starts, pauses, or completes an agent task."""
from uuid import uuid4
from unittest.mock import Mock
import json
import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, text, delete
from app.main import create_app
from app.models.product_feedback import ProductFeedback
from app.models.user import User
from app.services.goal_jev import JevGoalClassifier
from tests.helpers import register

BASE = '/api/v1'


def jev_answer(payload, choice, probability=.98):
    criteria = payload['questions']['interaction']['criteria']
    p = {key: probability if key == choice else (1-probability)/(len(criteria)-1) for key in criteria}
    return httpx.Response(200, json={'model': payload['model'], 'answers': {'interaction': {
        'type': 'choice', 'choice': choice, 'confidence': (probability-1/len(criteria))/(1-1/len(criteria)),
        'probabilities': p}}})


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv('GOAL_CLASSIFIER_PROVIDER', 'jev')
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '.8')


def classify(client, source='야 이상한 색기야', identifier=None, **extra):
    return client.post(BASE+'/interactions/classify', json={'id': identifier or str(uuid4()), 'text': source, **extra})


@pytest.mark.parametrize('source,intent', [
    ('야 이상한 색기야', 'negative_feedback'), ('클릭 진짜 답답하네', 'negative_feedback'),
    ('클릭은 별로 안 좋아', 'negative_feedback'), ('고마워', 'positive_feedback'),
    ('폰트 키워줘서 고마워', 'positive_feedback'), ('너 덕분에 편했어', 'positive_feedback')])
def test_feedback_is_saved_encrypted_without_creating_an_agent_session(configured, source, intent):
    app = create_app()
    app.state.interactions.classifier = JevGoalClassifier(lambda payload, **kw: jev_answer(payload, intent))
    with TestClient(app) as client:
        register(client)
        response = classify(client, source)
        assert response.status_code == 200, response.text
        result = response.json()
        assert result['intent'] == intent and result['feedback']['source_text'] == source
        assert ('죄송합니다' if intent == 'negative_feedback' else '고마워요') in result['message']
        assert client.get(BASE+'/voice-requests').json()['queued'] == []
        assert client.get(BASE+'/agent/sessions').json()['items'] == []
        with app.state.engine.connect() as connection:
            stored = connection.execute(text('SELECT source_text FROM product_feedback')).scalar_one()
            assert source not in stored and stored.startswith('clickenc:')
        saved = client.post(BASE+'/feedback/'+result['feedback']['id']+'/detail', json={'detail':'설정 메뉴를 잘 못 찾아요'})
        assert saved.status_code == 200
        assert saved.json()['detail'] == '설정 메뉴를 잘 못 찾아요'
        with app.state.engine.connect() as connection:
            stored = connection.execute(text('SELECT detail FROM product_feedback')).scalar_one()
            assert '설정 메뉴' not in stored and stored.startswith('clickenc:')


def test_idempotency_ownership_limits_and_feedback_does_not_mutate_running_queue(configured):
    app = create_app(); classifier = Mock();classifier.classify_interaction.return_value = 'negative_feedback'
    app.state.interactions.classifier = classifier
    with TestClient(app) as one, TestClient(app) as two:
        register(one);register(two)
        one.post(BASE+'/voice-requests/enqueue', json={'id':str(uuid4()),'raw_goal':'와이파이 켜줘'})
        running = one.post(BASE+'/voice-requests/next/claim').json()['running']
        before = one.get(BASE+'/voice-requests').json()
        identifier = str(uuid4())
        result = classify(one, identifier=identifier, session_id=running['agent_session_id'])
        assert result.status_code == 200
        assert classify(one, identifier=identifier, session_id=running['agent_session_id']).json() == result.json()
        assert classifier.classify_interaction.call_count == 1
        assert one.get(BASE+'/voice-requests').json() == before
        assert classify(one, '다른 불만', identifier=identifier).status_code == 409
        assert classify(two, identifier=identifier).status_code == 404
        assert classify(two, session_id=running['agent_session_id']).status_code == 404
        assert two.post(BASE+'/feedback/'+identifier+'/detail', json={'detail':'overwrite'}).status_code == 404
        assert two.get(BASE+'/feedback/'+identifier).status_code == 404
        assert one.post(BASE+'/feedback/'+identifier+'/detail', json={'detail':'   '}).status_code == 422
        assert one.post(BASE+'/feedback/'+identifier+'/detail', json={'detail':'x'*2001}).status_code == 422
        assert classify(one, 'x'*501).status_code == 422
        assert classify(one, '   ').status_code == 422
        assert one.post(BASE+'/feedback/'+identifier+'/detail', json={'detail':'속도가 느려요'}).status_code == 200
        assert one.post(BASE+'/feedback/'+identifier+'/detail', json={'detail':'속도가 느려요'}).status_code == 200
        assert one.get(BASE+'/feedback/'+identifier).json()['detail'] == '속도가 느려요'


@pytest.mark.parametrize('source', ['고마워 와이파이 켜줘', '너 진짜 답답한데 블루투스 꺼줘', '화면 밝기 낮춰줘'])
def test_explicit_task_takes_precedence_over_feedback_even_if_model_would_disagree(configured, source):
    app = create_app();classifier = Mock();app.state.interactions.classifier = classifier
    with TestClient(app) as client:
        register(client)
        assert classify(client, source).json() == {'intent':'task','message':None,'feedback':None}
        classifier.classify_interaction.assert_not_called()
        with app.state.session_factory() as db:
            assert list(db.scalars(select(ProductFeedback))) == []


def test_unrelated_and_uncertain_input_uses_original_goal_path_instead_of_apologizing(configured):
    app = create_app();classifier = Mock();app.state.interactions.classifier = classifier
    with TestClient(app) as client:
        register(client)
        for intent in ['other', 'task', None]:
            classifier.classify_interaction.return_value = intent
            assert classify(client, '친구가 이상한 색기라고 했어').json()['intent'] == 'task'
        client.headers.clear()
        assert classify(client).status_code == 401


def test_operator_review_requires_separate_secret_and_supports_stable_pagination(configured, monkeypatch):
    app = create_app();classifier = Mock();app.state.interactions.classifier = classifier
    with TestClient(app) as client:
        register(client)
        for intent in ['negative_feedback', 'positive_feedback', 'negative_feedback']:
            classifier.classify_interaction.return_value = intent
            assert classify(client).status_code == 200
        assert client.get(BASE+'/ops/feedback').status_code == 404
        monkeypatch.setenv('OPS_TOKEN', 'operator-only-test-token-32-characters')
        assert client.get(BASE+'/ops/feedback').status_code == 401
        headers={'Authorization':'Bearer operator-only-test-token-32-characters'}
        first = client.get(BASE+'/ops/feedback?limit=2',headers=headers).json()
        second = client.get(BASE+'/ops/feedback?limit=2&before='+first['next_cursor'],headers=headers).json()
        ids=[r['id'] for r in first['items']+second['items']]
        assert len(ids)==len(set(ids))==3 and second['next_cursor'] is None
        assert all('user_id' not in r for r in first['items'])
        positive=client.get(BASE+'/ops/feedback?sentiment=positive',headers=headers).json()
        assert len(positive['items'])==1 and positive['items'][0]['sentiment']=='positive'
        with app.state.session_factory() as db:
            db.execute(delete(User));db.commit()
            assert list(db.scalars(select(ProductFeedback))) == []


def test_feedback_detail_is_not_sent_back_to_the_model(configured):
    app=create_app();classifier=Mock();classifier.classify_interaction.return_value='negative_feedback'
    app.state.interactions.classifier=classifier
    with TestClient(app) as client:
        register(client);identifier=classify(client).json()['feedback']['id']
        assert client.post(BASE+'/feedback/'+identifier+'/detail',json={'detail':'화면에서 버튼을 잘 못 찾아요'}).status_code==200
        classifier.classify_interaction.assert_called_once_with('야 이상한 색기야')


@pytest.mark.parametrize('failure', ['low', 'wrong_model', 'unknown', 'extra', 'nan', 'duplicate', 'timeout', 'empty'])
def test_jev_interaction_protocol_fails_closed(configured, failure):
    def send(payload, **kwargs):
        if failure == 'timeout': raise TimeoutError('test only')
        if failure == 'empty': return httpx.Response(200, content=b'{}')
        response = jev_answer(payload, 'negative_feedback', .4 if failure=='low' else .98).json()
        answer = response['answers']['interaction']
        if failure=='wrong_model': response['model']='another-model'
        if failure=='unknown': answer['choice']='run_shell'
        if failure=='extra': answer['action']='tap'
        if failure=='nan': answer['probabilities']['task']=float('nan')
        if failure=='duplicate': return httpx.Response(200,content=b'{"model":"a","model":"b"}')
        return httpx.Response(200, content=json.dumps(response).encode())
    assert JevGoalClassifier(send).classify_interaction('야 이상한 색기야') is None


def test_jev_feedback_input_is_minimized_without_ids_audio_or_screen(configured):
    def send(payload, **kwargs):
        assert set(payload)=={'model','state','questions'}
        assert '010-1234-5678' not in payload['state'] and '김민수' not in payload['state']
        assert '[REDACTED]' in payload['state']
        assert set(payload['questions']['interaction']['criteria'])=={'task','negative_feedback','positive_feedback','other'}
        return jev_answer(payload,'negative_feedback')
    assert JevGoalClassifier(send).classify_interaction('제 이름은 김민수이고 010-1234-5678, 클릭 정말 답답해')=='negative_feedback'


def test_exact_feedback_remains_available_without_model_configuration():
    app=create_app();classifier=Mock();classifier.classify_interaction.return_value=None
    app.state.interactions.classifier=classifier
    with TestClient(app) as client:
        register(client)
        assert classify(client, '고마워').json()['intent']=='positive_feedback'
        assert classify(client, '야 이상한 색기야').json()['intent']=='negative_feedback'
        assert classify(client, '친구가 야 이상한 색기야라고 했어').json()['intent']=='task'


def test_feedback_survives_restart_and_a_retried_classification(tmp_path):
    url='sqlite+pysqlite:///'+(tmp_path/'feedback.db').as_posix()
    app=create_app(url);classifier=Mock();classifier.classify_interaction.return_value='negative_feedback'
    app.state.interactions.classifier=classifier
    identifier=str(uuid4())
    with TestClient(app) as client:
        register(client);authorization=client.headers['Authorization']
        assert classify(client, identifier=identifier).status_code==200
        assert client.post(BASE+'/feedback/'+identifier+'/detail',json={'detail':'설정에서 방향을 자꾸 잘못 찾아요'}).status_code==200
    restarted=create_app(url);restarted.state.interactions.classifier=Mock()
    with TestClient(restarted) as client:
        client.headers['Authorization']=authorization
        result=classify(client, identifier=identifier)
        assert result.status_code==200
        assert result.json()['feedback']['detail']=='설정에서 방향을 자꾸 잘못 찾아요'
        restarted.state.interactions.classifier.classify_interaction.assert_not_called()
        with restarted.state.session_factory() as db:
            assert len(list(db.scalars(select(ProductFeedback))))==1


def test_deleting_only_the_linked_task_keeps_product_feedback():
    from app.models.agent_session import AgentSession
    app=create_app();classifier=Mock();classifier.classify_interaction.return_value='positive_feedback'
    app.state.interactions.classifier=classifier
    with TestClient(app) as client:
        register(client)
        client.post(BASE+'/voice-requests/enqueue',json={'id':str(uuid4()),'raw_goal':'와이파이 켜줘'})
        session=client.post(BASE+'/voice-requests/next/claim').json()['running']['agent_session_id']
        identifier=classify(client,'고마워',session_id=session).json()['feedback']['id']
        with app.state.session_factory() as db:
            db.execute(delete(AgentSession).where(AgentSession.id==session));db.commit()
            row=db.get(ProductFeedback,identifier)
            assert row is not None and row.session_id is None
        assert client.get(BASE+'/feedback/'+identifier).json()['source_text']=='고마워'
