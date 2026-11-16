from typing import Literal
from pydantic import BaseModel, ConfigDict, Field
from app.schemas.browse import BrowseGoal


class ClarificationAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question_id: str = Field(min_length=1, max_length=200)
    value: str = Field(min_length=1, max_length=200)


class InputChoice(BaseModel):
    value: str
    label: str
    aliases: list[str] = Field(default_factory=list)


class InputQuestion(BaseModel):
    id: str
    field: str
    label: str
    kind: Literal["choice", "text"]
    message: str
    choices: list[InputChoice] = Field(default_factory=list)
    placeholder: str | None = None
    max_length: int = Field(default=100, ge=1, le=200)


class GoalPreparation(BaseModel):
    version: Literal["goal-inputs.v1"] = "goal-inputs.v1"
    id: str
    source_text: str
    family: str | None = None
    status: Literal["needs_input", "ready", "unsupported", "cancelled"]
    values: dict[str, str] = Field(default_factory=dict)
    required_fields: list[dict] = Field(default_factory=list)
    missing_fields: list[str] = Field(default_factory=list)
    question: InputQuestion | None = None
    graph_reference: dict | None = None
    execution_mode: Literal['graph', 'browse'] = 'graph'
    browse_goal_type: Literal['coupang.product.search', 'coupang.product.details'] | None = None
    browse_goal: BrowseGoal | None = None
    expires_at: int
