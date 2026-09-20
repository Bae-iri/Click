from __future__ import annotations

import base64
import binascii
import os
from collections.abc import Callable

import httpx

from app.schemas.transcription import TranscriptionRequest


class InvalidAudio(ValueError):
    pass


class TranscriptionUnavailable(RuntimeError):
    pass


class TranscriptionTimedOut(TimeoutError):
    pass


class InvalidTranscriptionResponse(RuntimeError):
    pass


class TranscriptionService:
    """Keeps the OpenAI credential server-side and never persists uploaded audio."""

    MODEL = "gpt-transcribe"
    MAX_AUDIO_BYTES = 650_000
    KEYWORDS = (
        "Click", "클릭", "쿠팡", "와이파이", "블루투스", "비행기 모드", "핫스팟",
        "글자 크기", "화면 밝기", "다크 모드", "접근성", "TalkBack", "장바구니",
        "로켓배송", "로켓와우", "배송 조회", "주문 취소", "반품", "교환", "상품평",
    )

    def __init__(self, sender: Callable[..., httpx.Response] | None = None):
        self._sender = sender or self._send_openai

    def transcribe(self, request: TranscriptionRequest) -> str:
        try:
            audio = base64.b64decode(request.audio_base64, validate=True)
        except (binascii.Error, ValueError) as error:
            raise InvalidAudio("audio_base64 is invalid") from error
        if not audio or len(audio) > self.MAX_AUDIO_BYTES:
            raise InvalidAudio("audio payload is empty or too large")
        if request.mime_type in {"audio/wav", "audio/x-wav"} and not audio.startswith(b"RIFF"):
            raise InvalidAudio("WAV payload has no RIFF header")

        api_key = os.getenv("OPENAI_API_KEY", "")
        if not api_key:
            raise TranscriptionUnavailable("OPENAI_API_KEY is not configured")
        extension = {
            "audio/wav": "wav", "audio/x-wav": "wav", "audio/mpeg": "mp3",
            "audio/mp4": "mp4", "audio/m4a": "m4a", "audio/webm": "webm", "audio/ogg": "ogg",
        }[request.mime_type]
        try:
            response = self._sender(
                api_key=api_key,
                audio=audio,
                filename=f"click-command.{extension}",
                mime_type=request.mime_type,
                language=request.language,
            )
        except httpx.TimeoutException as error:
            raise TranscriptionTimedOut("OpenAI transcription timed out") from error
        except httpx.HTTPError as error:
            raise TranscriptionUnavailable("OpenAI transcription is unavailable") from error
        if response.status_code < 200 or response.status_code >= 300:
            raise TranscriptionUnavailable("OpenAI transcription returned an error")
        try:
            text = response.json()["text"].strip()
        except (ValueError, KeyError, AttributeError) as error:
            raise InvalidTranscriptionResponse("OpenAI transcription response is invalid") from error
        if not text:
            raise InvalidTranscriptionResponse("OpenAI transcription response is empty")
        return text

    def _send_openai(self, *, api_key: str, audio: bytes, filename: str,
                     mime_type: str, language: str) -> httpx.Response:
        form = {
            "model": self.MODEL,
            "language": language,
            "prompt": "한국어 스마트폰 설정 및 쿠팡 사용 요청입니다.",
            "keywords[]": list(self.KEYWORDS),
        }
        with httpx.Client(timeout=httpx.Timeout(20.0, connect=5.0)) as client:
            return client.post(
                "https://api.openai.com/v1/audio/transcriptions",
                headers={"Authorization": f"Bearer {api_key}"},
                data=form,
                files={"file": (filename, audio, mime_type)},
            )
