from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, field_validator, model_validator

from app.schemas.guidance import GuideCommandResponse


class AccessibilityNode(BaseModel):
    """Android AccessibilityNode payload accepted at the BE/Agent boundary."""

    model_config = ConfigDict(populate_by_name=True, extra="allow")

    node_id: str
    package_name: str | None = None
    class_name: str | None = None
    text: str | None = None
    content_description: str | None = None
    resource_id: str | None = None
    bounds_in_screen: tuple[int, int, int, int] = Field(
        default=(0, 0, 0, 0),
        validation_alias=AliasChoices("bounds_in_screen", "bounds"),
    )
    clickable: bool = False
    enabled: bool = True
    selected: bool = False
    scrollable: bool = False
    checkable: bool = False
    role: str | None = None
    extras: dict[str, Any] = Field(default_factory=dict)

    @field_validator("bounds_in_screen")
    @classmethod
    def validate_bounds(cls, value: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
        left, top, right, bottom = value
        if right < left or bottom < top:
            raise ValueError("bounds_in_screen must be [left, top, right, bottom]")
        return value


class AccessibilityTree(BaseModel):
    model_config = ConfigDict(extra="allow")

    package_name: str | None = None
    captured_at: datetime | None = None
    screen_width: int = Field(default=1, gt=0)
    screen_height: int = Field(default=1, gt=0)
    nodes: list[AccessibilityNode]

    @model_validator(mode="after")
    def require_package_name(self) -> "AccessibilityTree":
        if self.package_name is None:
            self.package_name = next(
                (node.package_name for node in self.nodes if node.package_name),
                None,
            )
        if self.package_name is None:
            raise ValueError("package_name is required on the tree or at least one node")
        return self


class AgentTurnRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=200)
    session_id: str = Field(min_length=1, max_length=200)
    user_id: str | None = Field(default=None, min_length=36, max_length=36)
    raw_goal: str | None = Field(default=None, min_length=1, max_length=500)
    accessibility_tree: AccessibilityTree
    user_confirmation: Literal["accepted", "rejected"] | None = None
    user_action_event: "UserActionEventRequest | None" = None


class UserActionEventRequest(BaseModel):
    action_type: Literal[
        "tap_completed", "drag_completed", "back_navigated", "manual_override", "observe_only"
    ]
    guide_command_id: str | None = None
    target_node_id: str | None = None
    target_bounds: tuple[int, int, int, int] | None = None
    occurred_at_ms: int | None = None


class AgentTurnResponse(BaseModel):
    request_id: str
    session_id: str
    task_id: str
    step: int
    total_steps: int
    state_id: str
    state_confidence: float
    task_status: Literal["IN_PROGRESS", "WAITING_CONFIRMATION", "COMPLETED", "BLOCKED"]
    guide_command: GuideCommandResponse
    metadata: dict[str, Any] = Field(default_factory=dict)
