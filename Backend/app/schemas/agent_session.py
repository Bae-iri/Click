from app.schemas.execution import ExecutionState
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class CancelSessionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expected_version: int = Field(ge=0)


class AgentSessionResponse(BaseModel):
    execution_state: ExecutionState | None = None
    session_id: str
    episode_id: str | None = None
    status: Literal["IN_PROGRESS", "WAITING_CONFIRMATION", "WAITING_RESULT", "WAITING_ADJUSTMENT", "PAUSED", "COMPLETED", "BLOCKED", "CANCELLED", "EXPIRED"]
    task_outcome: dict | None = None
    outcome_history: list[dict] = Field(default_factory=list)
    version: int
    task_id: str | None
    state_id: str | None
    last_guide_command_id: str | None
    created_at: int
    updated_at: int
    expires_at: int
    processing: bool
    requires_fresh_observation: bool
    requires_goal: bool
    result_response: Literal["accepted", "rejected"] | None = None
    result_prompt: dict | None = None
    follow_up: dict | None = None
    browsing: bool = False
