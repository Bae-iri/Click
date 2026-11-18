"""A manual public-login guide. Credential fields never enter this contract."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator
from app.schemas.browse import BrowseNode, BrowseScreen, BrowseCommandRequest, BrowseResponse
from app.schemas.goal_preparation import InputQuestion

class PublicGuideGoal(BaseModel):
    model_config = ConfigDict(extra='forbid')
    goal_type: Literal['gov24.login', 'gov24.resident_copy.open'] = 'gov24.login'

class PublicGuideNode(BrowseNode):
    option_id: str = Field(min_length=1, max_length=60)

class PublicGuideScreen(BrowseScreen):
    status: Literal['public', 'outside_app', 'sensitive', 'unrecognized', 'handoff']
    nodes: list[PublicGuideNode] = Field(default_factory=list, max_length=24)
    handoff_reason: Literal['credential_input', 'certificate_selection', 'external_auth', 'consent_required'] | None = None

    @model_validator(mode='after')
    def private_boundary(self):
        if (self.status == 'handoff') != (self.handoff_reason is not None):
            raise ValueError('Handoff screens contain only a stable reason')
        if self.query_hash is not None:
            raise ValueError('Login guide has no query content')
        return self

class PublicGuideRequest(BrowseCommandRequest):
    protocol_version: Literal['public-guide.v1'] = 'public-guide.v1'
    screen: PublicGuideScreen | None = None
    answer: str | None = Field(default=None, min_length=1, max_length=160)
    choice: str | None = Field(default=None, min_length=1, max_length=60)
    question_id: str | None = Field(default=None, min_length=1, max_length=100)

    @model_validator(mode='after')
    def choice_binding(self):
        answered = self.answer is not None or self.choice is not None
        if self.answer is not None and self.choice is not None:
            raise ValueError('Use either speech/text or a choice button')
        if answered != (self.question_id is not None) or answered and self.command != 'observe':
            raise ValueError('An answer requires the current screen and question')
        return self

class PublicGuideResponse(BrowseResponse):
    protocol_version: Literal['public-guide.v1'] = 'public-guide.v1'
    outcome: Literal['in_progress','user_reported','cancelled','blocked','unknown','handoff','verified_navigation']
    action_kind: Literal['login_navigation'] | None = None
    subgoal: Literal['open_login','choose_method','choose_provider','handoff','find_resident_service','resident_service'] = 'open_login'
    question: InputQuestion | None = None
