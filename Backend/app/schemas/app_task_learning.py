"""Device-reported experience, without input values, labels, coordinates or images."""
from typing import Literal, Annotated
from pydantic import BaseModel, ConfigDict, Field, model_validator
from app.schemas.app_task import TaskAction

Digest = Annotated[str, Field(pattern=r'^[0-9a-f]{64}$')]
Package = Annotated[str, Field(pattern=r'^[\w]+(?:\.[\w]+)+$', max_length=200)]


class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid')


class TaskEnvironment(Strict):
    package: Package
    app_version: str = Field(pattern=r'^(?:[0-9]{1,20}|unknown)$')
    android_api: int = Field(ge=26, le=100)


class ExperienceAction(Strict):
    tool: Literal['tap','set_text','scroll_forward','scroll_backward','back','open_app']
    target_key: Digest
    role: Literal['button','text_field','list','tab','toggle','none']
    expectation: Literal['screen_changed','target_visible','input_present','selection_changed','package_opened']
    expected_label_key: str = Field(default='', pattern=r'^(?:[0-9a-f]{64})?$')
    expected_package: str = Field(default='', max_length=200)
    advances_subgoal: bool = False


class TaskExperience(Strict):
    protocol_version: Literal['app-task-experience.v1'] = 'app-task-experience.v1'
    event_id: str = Field(pattern=r'^[0-9a-f-]{36}$')
    task_id: str = Field(pattern=r'^[0-9a-f-]{36}$')
    sequence: int = Field(ge=1, le=66)
    observed_at_ms: int = Field(ge=0)
    goal_key: Digest
    subgoal_key: Digest
    environment: TaskEnvironment
    screen_key: Digest
    after_key: Digest
    after_package: str = Field(max_length=200)
    kind: Literal['attempt','result']
    starts_task: bool = False
    action: ExperienceAction | None = None
    outcome: Literal['verified','rejected','unconfirmed','stale','protected','user_confirmed','stopped']
    action_observed: bool = False
    condition_met: bool = False

    @model_validator(mode='after')
    def evidence(self):
        if self.kind == 'result':
            if self.action is not None or self.outcome not in {'user_confirmed','stopped'}:
                raise ValueError('Invalid user result')
            return self
        if self.action is None or self.outcome in {'user_confirmed','stopped'}:
            raise ValueError('Missing attempt binding')
        a = self.action
        if (a.tool == 'set_text' and (a.role != 'text_field' or a.expectation != 'input_present')
                or a.tool.startswith('scroll_') and a.role != 'list'
                or a.tool == 'open_app' and (a.expectation != 'package_opened' or not a.expected_package)
                or a.tool == 'tap' and a.role not in {'button','tab','toggle'}):
            raise ValueError('Incompatible native capability')
        if self.outcome == 'verified' and (not self.action_observed or not self.condition_met):
            raise ValueError('Unobserved success')
        if a.expectation == 'target_visible' and not a.expected_label_key:
            raise ValueError('Missing expected target')
        return self


class TaskExperienceBatch(Strict):
    protocol_version: Literal['app-task-experience-batch.v1'] = 'app-task-experience-batch.v1'
    session_id: str = Field(min_length=1, max_length=200)
    consent_id: str = Field(min_length=1, max_length=36)
    events: list[TaskExperience] = Field(min_length=1, max_length=20)


class TaskExperienceReceipt(Strict):
    protocol_version: Literal['app-task-experience-receipt.v1'] = 'app-task-experience-receipt.v1'
    status: Literal['accepted','ineligible']
    acknowledged: list[str]


class TaskMemoryResponse(Strict):
    protocol_version: Literal['app-task-memory.v1'] = 'app-task-memory.v1'
    request_id: str
    observation_id: str
    fingerprint: str
    status: Literal['remembered','needs_data','disabled','unavailable'] = 'needs_data'
    collection_enabled: bool = False
    consent_id: str | None = None
    consent_recorded_at_ms: int | None = None
    action: TaskAction | None = None
    evidence: list[dict] = Field(default_factory=list)
    tactics: list[dict] = Field(default_factory=list)
    # This is device-reported evidence, never an independent evaluation or authority.
    requires_confirmation: Literal[True] = True


class TaskUserResult(Strict):
    request_id: str = Field(min_length=1,max_length=100)
    session_id: str = Field(min_length=1,max_length=200)
    task_id: str = Field(pattern=r'^[0-9a-f-]{36}$')
    outcome: Literal['user_confirmed']


class TaskUserResultReceipt(Strict):
    request_id: str
    session_id: str
    task_id: str
    status: Literal['confirmed'] = 'confirmed'
    technical_source: Literal['user_reported'] = 'user_reported'
