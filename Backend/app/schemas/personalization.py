from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


AgeGroup = Literal["under_14", "14_19", "20_29", "30_39", "40_49", "50_59", "60_plus", "unknown"]
ComprehensionLevel = Literal["unknown", "beginner", "intermediate", "advanced"]
ExplanationDepth = Literal["concise", "normal", "detailed"]


class UIProfileResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    comprehension_level: ComprehensionLevel
    explanation_depth: ExplanationDepth
    prefers_step_by_step: bool
    confidence_score: float
    evidence_count: int
    updated_at: datetime


class CreateUserRequest(BaseModel):
    age_group: AgeGroup = "unknown"
    occupation_category: str | None = Field(default=None, max_length=50)


class UserResponse(BaseModel):
    id: str
    age_group: str | None
    occupation_category: str | None
    created_at: datetime
    profile: UIProfileResponse


class UpdateProfileRequest(BaseModel):
    comprehension_level: ComprehensionLevel | None = None
    explanation_depth: ExplanationDepth | None = None
    prefers_step_by_step: bool | None = None


class RecordConsentRequest(BaseModel):
    purpose: Literal["personalization", "conversation_storage", "model_training"]
    consented: bool
    policy_version: str = Field(min_length=1, max_length=30)


class ConsentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    purpose: str
    consented: bool
    policy_version: str
    recorded_at: datetime


class CreateUIEventRequest(BaseModel):
    session_id: str | None = Field(default=None, max_length=200)
    event_type: Literal[
        "guide_step_completed",
        "guide_step_failed",
        "help_opened",
        "guide_repeated",
        "user_confused",
        "explanation_changed",
        "task_completed",
    ]
    screen_name: str | None = Field(default=None, max_length=200)
    metadata: dict[str, Any] = Field(default_factory=dict)


class UIEventResponse(BaseModel):
    id: str
    user_id: str
    event_type: str
    profile: UIProfileResponse
