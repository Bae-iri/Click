from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class TranscriptionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    audio_base64: str = Field(min_length=4, max_length=900_000)
    mime_type: Literal["audio/wav", "audio/x-wav"] = "audio/wav"
    language: Literal["ko"] = "ko"


class TranscriptionResponse(BaseModel):
    text: str
    model: Literal["Qwen/Qwen3-ASR-1.7B"] = "Qwen/Qwen3-ASR-1.7B"
    provider: Literal["self_hosted"] = "self_hosted"
