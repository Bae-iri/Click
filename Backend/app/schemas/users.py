from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class UserResponse(BaseModel):
    id: str
    name: str | None
    created_at: datetime


class RecordConsentRequest(BaseModel):
    purpose: Literal["conversation_storage", "model_training"]
    consented: bool
    policy_version: str = Field(min_length=1, max_length=30)


class ConsentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    purpose: str
    consented: bool
    policy_version: str
    recorded_at: datetime
