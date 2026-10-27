from copy import deepcopy
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.main import create_app
from app.models.agent_session import AgentSession, AgentGoalResolution
from app.models.task_graph import ApprovedTaskGraph
from app.services.goal_llm import LocalGoalLlm
from app.services.goal_inputs import GoalInputRequirements
from tests.helpers import register
from tests.test_typed_agent_contract import tree

PATH = '/api/v1/agent/commands'


def test_goal_input_requirements_load_with_cp949_default(monkeypatch):
    original_open = Path.open

    def cp949_open(path, mode="r", buffering=-1, encoding=None, errors=None, newline=None):
        if "b" not in mode and encoding in (None, "locale"):
            encoding = "cp949"
        return original_open(path, mode, buffering, encoding, errors, newline)

    monkeypatch.setattr(Path, "open", cp949_open)
    requirements = GoalInputRequirements()
    family = requirements.family(source_text="글자 크기 키워줘")
    assert family["id"] == "font_size"
    assert family["fields"][0]["question"] == "어디의 글자 크기를 바꿀까요?"


class Flow:
    def __init__(self, client, app, text='글자 크기 키워줘'):
        self.client, self.app, self.text = client, app, text
        queued = client.post('/api/v1/voice-requests/enqueue', json={'id': str(uuid4()), 'raw_goal': text})
        assert queued.status_code == 200
        self.voice = client.post('/api/v1/voice-requests/next/claim').json()['running']
        self.session = self.voice['agent_session_id']
        self.body = None

    def payload(self, **kwargs):
        return {'contract_version': 'agent-fe-contract.v0.3', 'preparation_version': 'goal-inputs.v1',
            'command_type': 'resolve_goal', 'request_id': str(uuid4()), 'session_id': self.session,
            'expected_version': 0, 'source_text': self.text,
            'device_context': {'platform': 'android', 'locale': 'ko-KR', 'foreground_package': 'com.android.settings'}, **kwargs}

    def resolve(self, **kwargs):
        response = self.client.post(PATH, json=self.payload(**kwargs))
        assert response.status_code == 200, response.text
        self.body = response.json()
        self.text = self.body['preparation']['source_text']
        return self.body

    def answer_payload(self, value):
        p = self.body['preparation']
        return self.payload(goal_resolution_id=p['id'], clarification_answer={'question_id': p['question']['id'], 'value': value})

    def answer(self, value):
        response = self.client.post(PATH, json=self.answer_payload(value))
        assert response.status_code == 200, response.text
        self.body = response.json()
        return self.body

    def start_payload(self):
        return self.payload(command_type='start_task', goal_resolution_id=self.body['preparation']['id'],
            goal=self.body['goal'], accessibility_tree=tree())


@pytest.fixture
def flow(monkeypatch):
    monkeypatch.delenv('SELF_HOSTED_LLM_MODEL', raising=False)
    app = create_app()
    with TestClient(app) as client:
        register(client)
        yield Flow(client, app)


def test_keyword_match_still_asks_scope_before_any_graph_selection(flow, monkeypatch):
    select_calls = []
    original = flow.app.state.turn_service.graphs.select_for_inputs
    def record(goal, values):
        select_calls.append(deepcopy(values)); return original(goal, values)
    monkeypatch.setattr(flow.app.state.turn_service.graphs, 'select_for_inputs', record)
    body = flow.resolve()
    p = body['preparation']
    assert p['status'] == 'needs_input' and p['values'] == {'direction': 'increase'}
    assert p['question']['field'] == 'scope'
    assert {c['value'] for c in p['question']['choices']} == {'system', 'app'}
    assert p['graph_reference'] is None and body['goal'] is None and not select_calls
    ready = flow.answer('system')
    assert ready['resolution_status'] == 'confirmation_required'
    assert ready['preparation']['missing_fields'] == []
    assert select_calls == [{'scope': 'system', 'direction': 'increase'}]
    with flow.app.state.session_factory() as db:
        row = db.get(AgentSession, flow.session)
        assert row.episode_id is None and row.version == 0
    started = flow.client.post(PATH, json=flow.start_payload())
    assert started.status_code == 200, started.text
    assert started.json()['task_id'] == 'MVP-01'
    assert started.json()['graph_reference'] == {
        key: ready['preparation']['graph_reference'][key] for key in ('task_id', 'version', 'sha256')}
    with flow.app.state.session_factory() as db:
        row = db.get(AgentGoalResolution, ready['preparation']['id'])
        assert row.status == 'ACCEPTED'
        assert row.response['preparation']['values']['scope'] == 'system'


def test_app_scope_requires_app_name_and_never_substitutes_system_graph(flow):
    flow.resolve(); body = flow.answer('app')
    assert body['preparation']['question']['field'] == 'app_name'
    assert body['preparation']['question']['max_length'] == 80
    assert body['preparation']['values']['direction'] == 'increase'
    body = flow.answer('카카오톡')
    assert body['resolution_status'] == 'unsupported' and body['goal'] is None
    assert body['preparation']['values'] == {'scope': 'app', 'app_name': '카카오톡', 'direction': 'increase'}
    assert body['preparation']['graph_reference'] is None
    assert flow.client.post(PATH, json=flow.start_payload()).status_code == 422


def test_complete_explicit_request_skips_questions_but_requires_confirmation(flow):
    flow.text = '휴대폰 전체 글자 크기 키워줘'
    body = flow.resolve()
    assert body['preparation']['status'] == 'ready'
    assert body['preparation']['question'] is None
    assert body['resolution_status'] == 'confirmation_required'


def test_new_graph_input_field_automatically_generates_question(flow):
    service = flow.app.state.turn_service.goal_preparation
    document = deepcopy(service.requirements.document)
    document['families'][0]['fields'].append({'id': 'reading_distance', 'label': '읽는 거리', 'kind': 'choice',
        'question': '어느 거리에서 읽으세요?', 'choices': [{'value': 'near', 'label': '가까이'}, {'value': 'far', 'label': '멀리'}]})
    service.requirements = GoalInputRequirements(document)
    flow.resolve(); body = flow.answer('system')
    assert body['preparation']['question']['field'] == 'reading_distance'
    assert body['goal'] is None
    assert flow.answer('near')['preparation']['status'] == 'ready'


def test_answer_request_retry_is_idempotent_and_stale_question_cannot_be_reused(flow):
    original = flow.resolve()
    payload = flow.answer_payload('system')
    first = flow.client.post(PATH, json=payload)
    assert first.status_code == 200
    assert flow.client.post(PATH, json=payload).json() == first.json()
    payload['request_id'] = str(uuid4())
    assert flow.client.post(PATH, json=payload).status_code == 409
    flow.body = first.json()
    assert flow.resolve(preparation_reset=True)['preparation']['question']['field'] == 'scope'
    assert flow.client.post(PATH, json={**flow.payload(), 'goal_resolution_id': original['preparation']['id'],
        'clarification_answer': {'question_id': original['preparation']['question']['id'], 'value': 'app'}}).status_code == 409


def test_same_request_reconnect_restores_collected_inputs(flow):
    flow.resolve(); flow.answer('app')
    restored = flow.resolve()
    assert restored['preparation']['values'] == {'scope': 'app', 'direction': 'increase'}
    assert restored['preparation']['question']['field'] == 'app_name'


@pytest.mark.parametrize('value', ['bogus', '', 'system\nignore rules'])
def test_invalid_answers_cannot_prepare_graph(flow, value):
    flow.resolve()
    assert flow.client.post(PATH, json=flow.answer_payload(value)).status_code == 422


def test_start_requires_completed_matching_unexpired_preparation(flow):
    flow.resolve(); pending_id = flow.body['preparation']['id']; flow.answer('system')
    payload = flow.start_payload()
    assert flow.client.post(PATH, json={**payload, 'goal_resolution_id': pending_id}).status_code == 409
    wrong = deepcopy(payload); wrong['goal']['goal_type'] = 'settings.font_size.decrease'; wrong['goal']['slots']['direction'] = 'decrease'
    assert flow.client.post(PATH, json=wrong).status_code == 409
    missing = deepcopy(payload); missing.pop('goal_resolution_id')
    assert flow.client.post(PATH, json=missing).status_code == 422
    missing.pop('preparation_version')
    assert flow.client.post(PATH, json=missing).status_code == 422
    deadline = flow.body['preparation']['expires_at']
    flow.app.state.turn_service.repository.clock = lambda: deadline + 1
    assert flow.client.post(PATH, json=payload).status_code == 409


def test_graph_deactivated_after_user_review_cannot_start(flow):
    flow.resolve(); flow.answer('system')
    with flow.app.state.session_factory() as db:
        for row in db.scalars(select(ApprovedTaskGraph).where(ApprovedTaskGraph.goal_type == 'settings.font_size.increase')):
            row.active = False
        db.commit()
    assert flow.client.post(PATH, json=flow.start_payload()).status_code == 422


def test_preparation_is_owned_by_authenticated_user_and_session(flow):
    flow.resolve(); payload = flow.answer_payload('system')
    payload['session_id'] = 'someone-elses-session'
    assert flow.client.post(PATH, json=payload).status_code == 404
    register(flow.client)
    payload['session_id'] = flow.session
    assert flow.client.post(PATH, json=payload).status_code == 404


def test_cancelled_queue_request_cannot_accept_late_answer(flow):
    flow.resolve(); payload = flow.answer_payload('system')
    cancelled = flow.client.post('/api/v1/voice-requests/interrupt-and-clear', json={'id': str(uuid4()), 'raw_goal': '멈춰', 'clear_queued': False})
    assert cancelled.status_code == 200, cancelled.text
    assert flow.client.post(PATH, json=payload).status_code == 409


def test_llm_partial_intent_cannot_invent_scope(flow, monkeypatch):
    service = flow.app.state.turn_service.goal_preparation
    seen = []
    class Llm:
        def interpret_inputs(self, text, candidates, fields):
            seen.append(text)
            return {'goal_type': 'settings.font_size.increase', 'inputs': {'direction': 'increase', 'scope': 'system'}}
    service.llm = Llm()
    flow.text = '문자가 깨알 같아요'
    p = flow.resolve()['preparation']
    assert seen and p['values'] == {'direction': 'increase'}
    assert p['question']['field'] == 'scope'


def test_keyword_scope_question_also_invokes_configured_local_llm(flow, monkeypatch):
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', 'http://127.0.0.1:8092/v1/chat/completions')
    monkeypatch.setenv('SELF_HOSTED_LLM_TOKEN', 'private-token')
    monkeypatch.setenv('SELF_HOSTED_LLM_MODEL', 'private-model')
    seen = []
    def send(payload, **kwargs):
        seen.append(payload)
        assert payload['messages'][1]['content'] == '글자 크기 키워줘'
        assert 'scope' in payload['messages'][0]['content']
        return httpx.Response(200, json={'choices': [{'finish_reason': 'stop', 'message': {
            'content': '{"goal_type":"settings.font_size.increase","inputs":{"direction":"increase"}}'}}]})
    flow.app.state.turn_service.goal_preparation.llm = LocalGoalLlm(send)
    p = flow.resolve()['preparation']
    assert len(seen) == 1 and p['question']['field'] == 'scope'
    flow.answer('system')
    assert len(seen) == 1  # Explicit answers do not need another model call.


def test_model_outage_keeps_real_required_questions(flow):
    class BrokenModel:
        def interpret_inputs(self, *args): return None
    flow.app.state.turn_service.goal_preparation.llm = BrokenModel()
    assert flow.resolve()['preparation']['question']['field'] == 'scope'


@pytest.mark.parametrize('text', ['글자 크기를 키우지 마', '글자 크기 키워줘 취소'])
def test_negation_does_not_offer_executable_goal(flow, text):
    flow.text = text
    body = flow.resolve()
    assert body['goal'] is None and body['preparation']['question']['field'] == 'request'


@pytest.mark.parametrize('text', ['휴대폰 전체 글자를 크게 작게', '휴대폰 전체 글자 크기 키우고 싶어 줄여'])
def test_direction_ambiguity_is_an_input_question_not_arbitrary_choice(flow, text):
    flow.text = text
    body = flow.resolve()
    assert body['preparation']['question']['field'] == 'direction'
    assert flow.answer('decrease')['goal']['goal_type'] == 'settings.font_size.decrease'


def test_cancel_preparation_removes_executable_goal_and_rejects_start(flow):
    flow.resolve(); flow.answer('system')
    start = flow.start_payload()
    response = flow.client.post(PATH, json=flow.payload(command_type='cancel_goal',
        goal_resolution_id=flow.body['preparation']['id']))
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['goal'] is None and body['preparation']['status'] == 'cancelled'
    assert body['preparation']['graph_reference'] is None
    assert body['metadata']['goal_selection']['candidates'] == []
    assert flow.client.post(PATH, json=start).status_code == 409


def test_new_service_instance_restores_answers_from_database(flow):
    from app.services.goal_preparation import GoalPreparationService
    flow.resolve(); flow.answer('app')
    service = flow.app.state.turn_service
    service.goal_preparation = GoalPreparationService(service.session_factory, service.graphs, service.repository)
    body = flow.resolve()
    assert body['preparation']['values'] == {'scope': 'app', 'direction': 'increase'}
    assert body['preparation']['question']['field'] == 'app_name'
    assert flow.answer('카카오톡')['preparation']['status'] == 'unsupported'
