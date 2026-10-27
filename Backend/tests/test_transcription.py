import base64
import io
import wave

import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.schemas.transcription import TranscriptionRequest
from app.services.transcription import (InvalidAudio, InvalidTranscriptionResponse, TranscriptionService,
    TranscriptionUnavailable, TranscriptionTimedOut)
from tests.helpers import register


def wav_payload(rate=16000, channels=1):
    out = io.BytesIO()
    with wave.open(out, 'wb') as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(b'\x01\x00' * 1600 * channels)
    return out.getvalue()


def audio_request(audio=None):
    return TranscriptionRequest(audio_base64=base64.b64encode(audio if audio is not None else wav_payload()).decode())


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv('SELF_HOSTED_STT_URL', 'http://127.0.0.1:8091/v1/transcriptions')
    monkeypatch.setenv('SELF_HOSTED_STT_TOKEN', 'test-private-token-' + 'x' * 32)


def stt_result(text='글자 크기 줄여줘'):
    return {'text': text, 'model': 'openai/whisper-large-v3', 'provider': 'self_hosted'}


def test_sends_only_korean_audio_to_private_worker(configured):
    captured = {}
    def send(payload, **kwargs):
        captured.update(payload)
        assert kwargs['timeout'] == 30
        return httpx.Response(200, json=stt_result(' 글자 크기 줄여줘 '))
    assert TranscriptionService(send).transcribe(audio_request()) == '글자 크기 줄여줘'
    assert captured == audio_request().model_dump()


def test_missing_self_hosted_config_never_falls_back_to_external_api(monkeypatch):
    monkeypatch.delenv('SELF_HOSTED_STT_URL', raising=False)
    monkeypatch.setenv('OPENAI_API_KEY', 'must-not-be-used')
    def forbidden(*args, **kwargs):
        pytest.fail('Unconfigured service attempted outbound inference')
    with pytest.raises(TranscriptionUnavailable):
        TranscriptionService(forbidden).transcribe(audio_request())


@pytest.mark.parametrize('audio', [b'not-a-wave', b'RIFF' + b'\0' * 44, wav_payload(8000),
    wav_payload(channels=2), wav_payload()[:-2], b'\0' * 650001],
    ids=['not-wav', 'invalid-header', 'wrong-rate', 'stereo', 'truncated', 'oversized'])
def test_rejects_invalid_or_unsupported_audio_before_network(audio):
    with pytest.raises(InvalidAudio):
        TranscriptionService(lambda *a, **kw: pytest.fail('must not send invalid audio')).transcribe(audio_request(audio))


@pytest.mark.parametrize('body', [{}, {'text': None}, stt_result(''), stt_result(['bad']),
    {'text': 'ok', 'model': 'gpt-transcribe', 'provider': 'openai'},
    {'text': 'ok', 'model': 'Qwen/Qwen3-ASR-1.7B', 'provider': 'self_hosted'}, []])
def test_rejects_malformed_or_wrong_provider_result(configured, body):
    with pytest.raises(InvalidTranscriptionResponse):
        TranscriptionService(lambda *a, **kw: httpx.Response(200, json=body)).transcribe(audio_request())


@pytest.mark.parametrize('status', [302, 401, 503])
def test_worker_errors_have_no_retry_or_provider_fallback(configured, status):
    with pytest.raises(TranscriptionUnavailable):
        TranscriptionService(lambda *a, **kw: httpx.Response(status)).transcribe(audio_request())


def test_timeout_is_distinct(configured):
    def timeout(*args, **kwargs):
        raise httpx.ReadTimeout('private worker timeout')
    with pytest.raises(TranscriptionTimedOut):
        TranscriptionService(timeout).transcribe(audio_request())


def test_authenticated_transcription_endpoint_returns_private_provider_metadata():
    class FakeTranscription:
        def transcribe(self, request):
            assert request.language == 'ko'
            return '쿠팡 장바구니 보여줘'
    with TestClient(create_app(transcription_service=FakeTranscription())) as client:
        body = audio_request().model_dump()
        assert client.post('/api/v1/speech/transcriptions', json=body).status_code == 401
        register(client)
        response = client.post('/api/v1/speech/transcriptions', json=body)
    assert response.status_code == 200
    assert response.json() == stt_result('쿠팡 장바구니 보여줘')


@pytest.mark.parametrize('error,status', [(TranscriptionUnavailable(), 503), (TranscriptionTimedOut(), 504),
    (InvalidTranscriptionResponse(), 502), (InvalidAudio(), 422)])
def test_endpoint_failure_contract(error, status):
    class Broken:
        def transcribe(self, request):
            raise error
    with TestClient(create_app(transcription_service=Broken())) as client:
        register(client)
        assert client.post('/api/v1/speech/transcriptions', json=audio_request().model_dump()).status_code == status
