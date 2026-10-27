"""Authenticated API → private worker → keyword miss → local LLM → confirmation."""
import json
from uuid import uuid4

import httpx
from fastapi.testclient import TestClient

from app.main import create_app
from app.clients.self_hosted import InferenceEndpoint
from app.models.agent_session import AgentSession
from inference.qwen_stt import create_app as create_worker
from tests.helpers import register
from tests.test_transcription import audio_request


def test_private_input_flow_keeps_resolution_non_executing(monkeypatch):
    token = 'private-inference-test-token-' + 'x' * 32
    monkeypatch.setenv('SELF_HOSTED_STT_URL', 'http://our-worker/v1/transcriptions')
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN', token)
    monkeypatch.setenv('SELF_HOSTED_LLM_URL', 'https://our-llm/v1/chat/completions')
    monkeypatch.setenv('SELF_HOSTED_LLM_TOKEN', token)
    monkeypatch.setenv('SELF_HOSTED_LLM_MODEL', 'our-model')
    calls = []
    phrase = '휴대폰 전체 문자가 깨알 같아요'
    with TestClient(create_worker(lambda: None, lambda model, pcm: phrase)) as worker:
        def private_post(endpoint, payload, *, timeout):
            calls.append(endpoint.url)
            if endpoint.url == 'http://our-worker/v1/transcriptions':
                return worker.post('/v1/transcriptions', json=payload, headers={'Authorization': 'Bearer ' + endpoint.token})
            assert endpoint.url == 'https://our-llm/v1/chat/completions'
            assert payload['messages'][1]['content'] == phrase
            assert 'audio_base64' not in json.dumps(payload)
            return httpx.Response(200, json={'choices': [{'finish_reason': 'stop',
                'message': {'content': '{"goal_type":"settings.font_size.increase","inputs":{"direction":"increase"}}'}}]})
        monkeypatch.setattr(InferenceEndpoint, 'post', private_post)
        app = create_app()
        with TestClient(app) as client:
            register(client)
            stt = client.post('/api/v1/speech/transcriptions', json=audio_request().model_dump())
            assert stt.status_code == 200 and stt.json()['provider'] == 'self_hosted'
            assert client.post('/api/v1/voice-requests/enqueue', json={'id': str(uuid4()), 'raw_goal': stt.json()['text']}).status_code == 200
            claimed = client.post('/api/v1/voice-requests/next/claim').json()['running']
            session = claimed['agent_session_id']
            result = client.post('/api/v1/agent/commands', json={
                'contract_version': 'agent-fe-contract.v0.3', 'command_type': 'resolve_goal',
                'preparation_version': 'goal-inputs.v1',
                'request_id': str(uuid4()), 'session_id': session, 'expected_version': 0,
                'source_text': stt.json()['text'], 'device_context': {'platform': 'android', 'locale': 'ko-KR'}})
            assert result.status_code == 200, result.text
            body = result.json()
            assert body['resolution_status'] == 'confirmation_required'
            assert body['goal']['slots'] == {'setting': 'font_size', 'direction': 'increase'}
            assert body['preparation']['values'] == {'scope': 'system', 'direction': 'increase'}
            assert body['preparation']['graph_reference']['task_id'] == 'MVP-01'
            assert 'guide_command' not in body
            with app.state.session_factory() as db:
                row = db.get(AgentSession, session)
                assert row.version == 0 and row.episode_id is None
    assert calls == ['http://our-worker/v1/transcriptions', 'https://our-llm/v1/chat/completions']
