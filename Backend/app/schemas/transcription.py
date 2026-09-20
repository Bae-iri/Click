from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class TranscriptionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    audio_base64: str = Field(min_length=4, max_length=900_000)
    mime_type: Literal[
        "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp4", "audio/m4a", "audio/webm", "audio/ogg"
    ] = "audio/wav"
    language: Literal["ko"] = "ko"


class TranscriptionResponse(BaseModel):
    text: str
    model: Literal["gpt-transcribe"] = "gpt-transcribe"
    provider: Literal["openai"] = "openai"
