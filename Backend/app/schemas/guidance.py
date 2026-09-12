from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class DeviceContext(BaseModel):
    manufacturer: str | None = None
    model: str | None = None
    android_version: str | None = None


class CreateSessionRequest(BaseModel):
    goal: str = Field(min_length=1, max_length=500)
    locale: str = "ko-KR"
    device_context: DeviceContext = Field(default_factory=DeviceContext)


class CreateSessionResponse(BaseModel):
    session_id: str
    task_id: str
    goal_type: str
    graph_version: str
    status: Literal["WAITING_FOR_SCREEN"]


class AccessibilityNode(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    node_id: str
    text: str | None = None
    content_description: str | None = None
    resource_id: str | None = None
    class_name: str | None = None
    role: str | None = None
    bounds: tuple[int, int, int, int] = (0, 0, 0, 0)
    clickable: bool = False
    enabled: bool = True
    selected: bool = False
    scrollable: bool = False
    checkable: bool = False
    extras: dict[str, Any] = Field(default_factory=dict)

    @field_validator("bounds")
    @classmethod
    def validate_bounds(cls, value: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
        left, top, right, bottom = value
        if right < left or bottom < top:
            raise ValueError("bounds must be [left, top, right, bottom]")
        return value


class ScreenSnapshot(BaseModel):
    package_name: str
    captured_at: datetime
    screen_width: int = Field(gt=0)
    screen_height: int = Field(gt=0)
    nodes: list[AccessibilityNode]


class ObserveRequest(BaseModel):
    snapshot: ScreenSnapshot
    confirmed: bool = False


class GuideCommandResponse(BaseModel):
    guide_command_id: str
    command_type: Literal["highlight", "gesture_hint", "confirmation", "done", "blocked"]
    action_type: Literal["tap", "drag", "scroll", "observe_only"]
    proposed_action: Literal["tap", "drag", "scroll"] | None = None
    message: str
    target_node_id: str | None = None
    target_label: str | None = None
    target_bounds: tuple[int, int, int, int] | None = Field(
        default=None,
        description="Target control rectangle in screen pixels [left, top, right, bottom]; not dp or overlay-local coordinates",
    )
    safety_level: Literal["LOW_RISK", "CONFIRM_REQUIRED", "BLOCKED"]
    requires_user_action: bool
    render_policy: dict[str, Any] = Field(default_factory=dict)


class ObserveResponse(BaseModel):
    session_id: str
    task_id: str
    step: int
    total_steps: int
    state_id: str
    state_confidence: float
    task_status: Literal["IN_PROGRESS", "WAITING_CONFIRMATION", "COMPLETED", "BLOCKED"]
    guide: GuideCommandResponse


class SessionResponse(BaseModel):
    session_id: str
    task_id: str
    goal: str
    goal_type: str
    graph_version: str
    status: str
    current_state: str | None
    step: int
    created_at: datetime
    updated_at: datetime
