"""Explicit, manual-only graphless browsing contract; no arbitrary actions or text entry."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class BrowseGoal(BaseModel):
    model_config = ConfigDict(extra='forbid')
    goal_type: Literal['coupang.product.search', 'coupang.product.details']
    query: str = Field(min_length=1, max_length=100)


class BrowseNode(BaseModel):
    model_config = ConfigDict(extra='forbid')
    node_id: str = Field(min_length=1, max_length=200)
    resource_id: str = Field(max_length=300)
    label: str = Field(default='', max_length=160)
    bounds: tuple[int, int, int, int]
    clickable: bool = False
    scrollable: bool = False


class BrowseScreen(BaseModel):
    model_config = ConfigDict(extra='forbid')
    status: Literal['public', 'outside_app', 'sensitive', 'unrecognized']
    package_name: str | None = None
    observation_id: str = Field(min_length=1, max_length=200)
    captured_at_ms: int = Field(ge=0)
    width: int = Field(gt=0, le=20000)
    height: int = Field(gt=0, le=20000)
    query_hash: str | None = Field(default=None, pattern=r'^[0-9a-f]{64}$')
    nodes: list[BrowseNode] = Field(default_factory=list, max_length=100)

    @model_validator(mode='after')
    def closed_observation(self):
        if self.status != 'public' and (self.nodes or self.query_hash):
            raise ValueError('Blocked screens cannot contain exported content')
        if len({n.node_id for n in self.nodes}) != len(self.nodes):
            raise ValueError('Duplicate node identity')
        for node in self.nodes:
            l, t, r, b = node.bounds
            if not 0 <= l < r <= self.width or not 0 <= t < b <= self.height:
                raise ValueError('Node outside screen')
        return self


class BrowseAction(BaseModel):
    model_config = ConfigDict(extra='forbid')
    guide_id: str = Field(min_length=1, max_length=100)
    observation_id: str = Field(min_length=1, max_length=200)
    node_id: str = Field(min_length=1, max_length=200)
    occurred_at_ms: int = Field(ge=0)


class BrowseCommandRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    protocol_version: Literal['coupang-browse.v1'] = 'coupang-browse.v1'
    request_id: str = Field(min_length=1, max_length=200)
    session_id: str = Field(min_length=1, max_length=200)
    expected_version: int = Field(ge=0)
    command: Literal['start', 'observe', 'cancel', 'finish', 'snapshot']
    goal_resolution_id: str | None = Field(default=None, max_length=200)
    screen: BrowseScreen | None = None
    action: BrowseAction | None = None

    @model_validator(mode='after')
    def command_shape(self):
        if self.command == 'start' and not self.goal_resolution_id:
            raise ValueError('Confirmed preparation is required')
        if self.command == 'observe' and self.screen is None:
            raise ValueError('Fresh screen required')
        if self.command in {'cancel', 'finish', 'snapshot'} and (self.screen or self.action):
            raise ValueError('Control requests contain no screen content')
        return self


class BrowseResponse(BaseModel):
    protocol_version: Literal['coupang-browse.v1'] = 'coupang-browse.v1'
    request_id: str
    session_id: str
    session_version: int
    status: Literal['IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'BLOCKED']
    message: str
    outcome: Literal['in_progress', 'verified_navigation', 'user_reported', 'cancelled', 'blocked', 'unknown']
    guide_id: str | None = None
    observation_id: str | None = None
    target_node_id: str | None = None
    target_bounds: tuple[int, int, int, int] | None = None
    action_kind: Literal['search', 'product', 'scroll'] | None = None
    step: int = 0
    manual_only: Literal[True] = True
