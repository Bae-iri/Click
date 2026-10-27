from __future__ import annotations

from collections.abc import Callable
import httpx

from app.clients.self_hosted import InferenceEndpoint
from app.schemas.transcription import STT_MODEL, TranscriptionRequest
from app.services.audio_validation import InvalidAudio, decode_pcm_wav


class TranscriptionUnavailable(RuntimeError):
    pass


class TranscriptionTimedOut(TimeoutError):
    pass


class InvalidTranscriptionResponse(RuntimeError):
    pass


class TranscriptionService:
    """Sends short Korean audio only to the configured, operator-owned Whisper worker."""

    MODEL = STT_MODEL

    def __init__(self, sender: Callable[..., httpx.Response] | None = None):
        self._sender = sender

    def transcribe(self, request: TranscriptionRequest) -> str:
        decode_pcm_wav(request.audio_base64, request.mime_type)
        try:
            endpoint = InferenceEndpoint.from_env("SELF_HOSTED_STT")
        except ValueError as error:
            raise TranscriptionUnavailable("Self-hosted STT is not configured") from error
        try:
            sender = self._sender or endpoint.post
            response = sender(request.model_dump(), timeout=30.0)
        except httpx.TimeoutException as error:
            raise TranscriptionTimedOut("Self-hosted STT timed out") from error
        except httpx.HTTPError as error:
            raise TranscriptionUnavailable("Self-hosted STT is unavailable") from error
        if response.status_code < 200 or response.status_code >= 300:
            raise TranscriptionUnavailable("Self-hosted STT returned an error")
        try:
            body = response.json()
            text = body["text"]
            if (not isinstance(text, str) or not text.strip() or len(text) > 4000
                    or body.get("model") != self.MODEL or body.get("provider") != "self_hosted"):
                raise ValueError("Unexpected STT result")
        except (ValueError, KeyError, TypeError, AttributeError) as error:
            raise InvalidTranscriptionResponse("Self-hosted STT response is invalid") from error
        return text.strip()
