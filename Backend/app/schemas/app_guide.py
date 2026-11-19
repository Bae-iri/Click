"""App-independent, manual screen guidance. No graph, coordinate or execution authority."""
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field
from app.schemas.browse import BrowseScreen


class AppGuideRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    protocol_version: Literal['app-guide.v1'] = 'app-guide.v1'
    request_id: str = Field(min_length=1, max_length=200)
    goal: str = Field(min_length=1, max_length=200)
    screen: BrowseScreen
    previous_targets: list[Annotated[str, Field(max_length=80)]] = Field(default_factory=list, max_length=4)


class AppGuideResponse(BaseModel):
    protocol_version: Literal['app-guide.v1'] = 'app-guide.v1'
    request_id: str
    observation_id: str
    status: Literal['guide', 'needs_input', 'unavailable', 'protected']
    message: str
    manual_only: Literal[True] = True
    target_node_id: str | None = None
    target_bounds: tuple[int, int, int, int] | None = None
    action_kind: Literal['tap', 'scroll'] | None = None
    diagnostic: dict = Field(default_factory=dict)
