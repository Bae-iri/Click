from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class EnqueueVoiceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID
    raw_goal: str = Field(min_length=1, max_length=500)
    priority: Literal["NORMAL", "VIP"] = "NORMAL"

    @field_validator("raw_goal")
    @classmethod
    def nonblank(cls, value):
        value = value.strip()
        if not value:
            raise ValueError("A goal is required")
        return value


class InterruptVoiceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: UUID
    raw_goal: str = Field(default="중단", min_length=1, max_length=500)
    clear_queued: bool = False


class FailVoiceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    failure_code: Literal["CLIENT_ERROR", "NETWORK_ERROR", "AGENT_ERROR", "USER_ABORTED", "USER_DECLINED"]


class CancelVoiceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: Literal["USER_ABORTED", "USER_DECLINED"]


class VoiceRequestResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    user_id: str
    raw_goal: str | None
    priority: Literal["NORMAL", "VIP"]
    status: Literal["QUEUED", "RUNNING", "COMPLETED", "CANCELLED", "FAILED"]
    agent_session_id: str | None
    sequence: int
    failure_code: str | None
    termination_reason: Literal["USER_ABORTED", "USER_DECLINED"] | None = None
    counts_as_failure: bool = False

    @model_validator(mode="after")
    def classify_outcome(self):
        # The existing database column remains readable for older clients' /fail requests.
        if self.status == "CANCELLED" and self.failure_code in {"USER_ABORTED", "USER_DECLINED"}:
            self.termination_reason = self.failure_code
            self.failure_code = None
        self.counts_as_failure = self.status == "FAILED"
        return self
    created_at: int
    updated_at: int
    expires_at: int


class VoiceQueueResponse(BaseModel):
    queue_version: int
    awaiting_replacement: bool
    request: VoiceRequestResponse | None = None
    running: VoiceRequestResponse | None = None
    queued: list[VoiceRequestResponse]
    claimed: bool = False
