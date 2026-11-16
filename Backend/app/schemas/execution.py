from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

class ExecutionContext(BaseModel):
    model_config=ConfigDict(extra='forbid')
    protocol_version: Literal['execution-state.v1']
    session_id: str=Field(min_length=1,max_length=200)
    episode_id: str | None=None
    session_version: int=Field(strict=True,ge=0)
    request_id: str=Field(min_length=1,max_length=200)
    observation_id: str | None=None

class ExecutionState(ExecutionContext):
    request_id: str | None=None
    graph_hash: str | None=None
    goal_type: str | None=None
    command_type: str
    task_status: str
    phase: str
    attempt_id: str | None=None
    guide_command_id: str | None=None
    verification_attempt_id: str | None=None
    verification_status: str | None=None
    source: Literal['turn','snapshot']
