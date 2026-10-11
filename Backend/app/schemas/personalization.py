from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


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
    state_difficulties: dict[str, dict[str, Any]] = Field(default_factory=dict)
    updated_at: datetime


class CreateUserRequest(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=50)
    age_group: AgeGroup = "unknown"
    occupation_category: str | None = Field(default=None, max_length=50)


class UserResponse(BaseModel):
    id: str
    name: str | None
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
        "guide_action_observed",
        "user_confused",
        "explanation_changed",
        "task_completed",
    ]
    screen_name: str | None = Field(default=None, max_length=200, description="Registered task graph state ID; never a screen title or user text")
    metadata: dict[str, Any] = Field(default_factory=dict, description="Only step, duration_ms, attempt_count (nonnegative integers <= 86400000) and explanation_depth (concise/normal/detailed)")

    @field_validator("metadata")
    @classmethod
    def validate_metadata(cls, value):
        allowed = {"step", "duration_ms", "attempt_count", "explanation_depth"}
        if value.keys() - allowed:
            raise ValueError("Unsupported event metadata")
        for key, item in value.items():
            if key == "explanation_depth":
                if not isinstance(item, str) or item not in {"concise", "normal", "detailed"}:
                    raise ValueError("Invalid explanation depth")
            elif type(item) is not int or not 0 <= item <= 86_400_000:
                raise ValueError("Invalid event metric")
        return value

    @field_validator("screen_name")
    @classmethod
    def validate_screen_name(cls, value):
        if value is not None:
            from ai_agent.runtime.tasks import TASK_GRAPHS
            states = {state for graph in TASK_GRAPHS.values() for state in graph.states}
            if value not in states:
                raise ValueError("Use a registered graph state ID")
        return value


class UIEventResponse(BaseModel):
    id: str
    user_id: str
    event_type: str
    profile: UIProfileResponse
