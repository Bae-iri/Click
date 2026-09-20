import base64

import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.schemas.transcription import TranscriptionRequest
from app.services.transcription import InvalidAudio, TranscriptionService, TranscriptionUnavailable
from tests.helpers import register


def wav_payload() -> bytes:
    return b"RIFF" + b"\x00" * 44


def test_uses_gpt_transcribe_request_contract_without_persisting_audio(monkeypatch):
    captured = {}

    def sender(**kwargs):
        captured.update(kwargs)
        return httpx.Response(200, json={"text": " 글자 크기 줄여줘 "})

    monkeypatch.setenv("OPENAI_API_KEY", "test-key")
    service = TranscriptionService(sender=sender)
    request = TranscriptionRequest(audio_base64=base64.b64encode(wav_payload()).decode())

    assert service.transcribe(request) == "글자 크기 줄여줘"
    assert captured["api_key"] == "test-key"
    assert captured["filename"] == "click-command.wav"
    assert captured["language"] == "ko"
    assert captured["audio"] == wav_payload()


def test_missing_server_key_requests_android_fallback(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    service = TranscriptionService()
    request = TranscriptionRequest(audio_base64=base64.b64encode(wav_payload()).decode())

    with pytest.raises(TranscriptionUnavailable):
        service.transcribe(request)


def test_rejects_non_wav_content():
    service = TranscriptionService()
    request = TranscriptionRequest(audio_base64=base64.b64encode(b"not-a-wave").decode())

    with pytest.raises(InvalidAudio):
        service.transcribe(request)


def test_authenticated_transcription_endpoint_returns_provider_metadata():
    class FakeTranscription:
        def transcribe(self, request):
            assert request.language == "ko"
            return "쿠팡 장바구니 보여줘"

    with TestClient(create_app(transcription_service=FakeTranscription())) as client:
        body = {"audio_base64": base64.b64encode(wav_payload()).decode(), "mime_type": "audio/wav", "language": "ko"}
        assert client.post("/api/v1/speech/transcriptions", json=body).status_code == 401
        register(client)
        response = client.post("/api/v1/speech/transcriptions", json=body)

    assert response.status_code == 200
    assert response.json() == {"text": "쿠팡 장바구니 보여줘", "model": "gpt-transcribe", "provider": "openai"}


def test_openai_multipart_uses_gpt_transcribe_and_keyword_array(monkeypatch):
    captured = {}

    class FakeClient:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return None

        def post(self, url, **kwargs):
            captured["url"] = url
            captured.update(kwargs)
            return httpx.Response(200, json={"text": "ok"})

    monkeypatch.setattr(httpx, "Client", lambda **kwargs: FakeClient())
    service = TranscriptionService()
    service._send_openai(
        api_key="server-secret", audio=wav_payload(), filename="click-command.wav",
        mime_type="audio/wav", language="ko",
    )

    assert captured["url"] == "https://api.openai.com/v1/audio/transcriptions"
    assert captured["data"]["model"] == "gpt-transcribe"
    assert captured["data"]["languages[]"] == ["ko"]
    assert "language" not in captured["data"]
    assert "쿠팡" in captured["data"]["keywords[]"]
    assert captured["files"]["file"] == ("click-command.wav", wav_payload(), "audio/wav")
