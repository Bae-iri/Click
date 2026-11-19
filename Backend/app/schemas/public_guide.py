"""A manual public-login guide. Credential fields never enter this contract."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator
from app.schemas.browse import BrowseNode, BrowseScreen, BrowseCommandRequest, BrowseResponse
from app.schemas.goal_preparation import InputQuestion
from app.schemas.public_guide_goal import PublicGuideGoal

class PublicGuideNode(BrowseNode):
    option_id: str = Field(min_length=1, max_length=60)

# Closed, content-free observations from the on-device guard. Older apps omit this.
PRIVACY_REASONS = {
    'snapshot_missing': ('unrecognized', None),
    'empty_accessibility_tree': ('unrecognized', None),
    'no_visible_nodes': ('unrecognized', None),
    'no_public_options': ('unrecognized', None),
    'ambiguous_public_options': ('unrecognized', None),
    'public_options_limit': ('unrecognized', None),
    'outside_gov24_app': ('outside_app', None),
    'external_auth_app': ('handoff', 'external_auth'),
    'foreign_package_node': ('sensitive', None),
    'inactive_window_node': ('sensitive', None),
    'password_field': ('handoff', 'credential_input'),
    'sensitive_input_field': ('handoff', 'credential_input'),
    'empty_search_field': ('handoff', 'credential_input'),
    'consent_prompt': ('handoff', 'consent_required'),
    'certificate_prompt': ('handoff', 'certificate_selection'),
    'private_identity_label': ('handoff', 'certificate_selection'),
}

class PublicGuideScreen(BrowseScreen):
    status: Literal['public', 'outside_app', 'sensitive', 'unrecognized', 'handoff']
    nodes: list[PublicGuideNode] = Field(default_factory=list, max_length=24)
    handoff_reason: Literal['credential_input', 'certificate_selection', 'external_auth', 'consent_required'] | None = None
    privacy_reason: Literal[
        'snapshot_missing', 'empty_accessibility_tree', 'no_visible_nodes', 'no_public_options',
        'ambiguous_public_options', 'public_options_limit', 'outside_gov24_app', 'external_auth_app',
        'foreign_package_node', 'inactive_window_node', 'password_field', 'sensitive_input_field',
        'empty_search_field', 'consent_prompt', 'certificate_prompt', 'private_identity_label',
    ] | None = None

    @model_validator(mode='after')
    def private_boundary(self):
        if (self.status == 'handoff') != (self.handoff_reason is not None):
            raise ValueError('Handoff screens contain only a stable reason')
        if self.query_hash is not None:
            raise ValueError('Login guide has no query content')
        if self.privacy_reason is not None and PRIVACY_REASONS[self.privacy_reason] != (self.status, self.handoff_reason):
            raise ValueError('Privacy reason must match the local guard status')
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
    diagnostic: dict | None = None
    experiment: dict | None = None
    protocol_version: Literal['public-guide.v1'] = 'public-guide.v1'
    outcome: Literal['in_progress','user_reported','cancelled','blocked','unknown','handoff','verified_navigation']
    action_kind: Literal['login_navigation'] | None = None
    subgoal: Literal['open_login','choose_method','choose_provider','handoff','find_resident_service','resident_service','find_service','service_entry'] = 'open_login'
    service_label: str | None = None
    baseline_graph_id: str | None = None
    graph_state: str | None = None
    question: InputQuestion | None = None
