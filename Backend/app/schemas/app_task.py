"""Versioned, app-independent planning and grounded tool proposals."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class TaskNode(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: str = Field(min_length=1, max_length=200)
    label: str = Field(default='', max_length=80)
    resource: str = Field(default='', max_length=300)
    role: Literal['button','text_field','list','tab','toggle','text','image','unknown'] = 'unknown'
    bounds: tuple[int,int,int,int]
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False
    selected: bool = False
    checked: bool | None = None
    native_heading: bool = False
    has_value: bool = False
    viewport_hash: str = Field(default='', max_length=64)
    source: Literal['accessibility','ocr'] = 'accessibility'


class TaskScreen(BaseModel):
    model_config = ConfigDict(extra='forbid')
    status: Literal['public','protected','outside','empty']
    package: str = Field(default='', max_length=200)
    observation_id: str = Field(min_length=1, max_length=200)
    captured_at_ms: int = Field(ge=0)
    fingerprint: str = Field(pattern=r'^[0-9a-f]{64}$')
    width: int = Field(gt=0, le=20000)
    height: int = Field(gt=0, le=20000)
    keyboard_visible: bool = False
    nodes: list[TaskNode] = Field(default_factory=list, max_length=80)

    @model_validator(mode='after')
    def grounded(self):
        if self.status != 'public' and self.nodes:
            raise ValueError('Non-public observations cannot carry controls')
        if len({n.id for n in self.nodes}) != len(self.nodes):
            raise ValueError('Duplicate control identity')
        for n in self.nodes:
            l,t,r,b=n.bounds
            if not 0 <= l < r <= self.width or not 0 <= t < b <= self.height:
                raise ValueError('Control outside observation')
        return self


class TaskSubgoal(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: str = Field(pattern=r'^g[0-7]$')
    title: str = Field(min_length=1, max_length=80)
    status: Literal['pending','verified'] = 'pending'


class TaskSwitchBaseline(BaseModel):
    model_config = ConfigDict(extra='forbid')
    key: str = Field(pattern=r'^[0-9a-f]{64}$')
    checked: bool = Field(strict=True)


class TaskAttempt(BaseModel):
    model_config = ConfigDict(extra='forbid')
    fingerprint: str = Field(pattern=r'^[0-9a-f]{64}$')
    tool: str = Field(max_length=30)
    target: str = Field(default='', max_length=200)
    outcome: Literal['verified','failed','waiting','stale','protected']
    switch_before: TaskSwitchBaseline | None = None


class TaskResultCheck(BaseModel):
    """A visible final-state invariant, independent of the action used to reach it."""
    model_config = ConfigDict(extra='forbid')
    package: str = Field(pattern=r'^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+$', max_length=200)
    label: str = Field(default='', max_length=80)
    resource: str = Field(default='', max_length=300)
    role: Literal['', 'button','text_field','list','tab','toggle','text','image','unknown'] = ''
    checked: bool | None = None
    selected: bool | None = None

    @model_validator(mode='after')
    def observable(self):
        if not self.label and not self.resource:
            raise ValueError('Result needs an observable selector')
        if self.checked is not None and self.role != 'toggle':
            raise ValueError('Checked result needs a native toggle')
        if self.selected is not None and self.role not in {'tab', 'toggle', 'button'}:
            raise ValueError('Selected result needs a selectable control')
        return self


class TaskCompletion(BaseModel):
    model_config = ConfigDict(extra='forbid')
    checks: list[TaskResultCheck] = Field(min_length=1, max_length=8)

    @model_validator(mode='after')
    def one_final_app(self):
        if len({c.package for c in self.checks}) != 1:
            raise ValueError('Final checks must be observable together')
        return self


class OutcomeClause(BaseModel):
    """Immutable requested effect. Labels describe the subject, never its current value."""
    model_config = ConfigDict(extra='forbid', frozen=True)
    kind: Literal['page', 'selected', 'on', 'off', 'increase', 'decrease', 'manual']
    labels: list[str] = Field(min_length=1, max_length=5)
    metric: Literal['none', 'control', 'font_size', 'brightness', 'volume'] = 'none'
    steps: int = Field(default=0, strict=True, ge=0, le=100)
    proof_role: Literal['tab', 'text_field'] | None = None

    @model_validator(mode='after')
    def complete(self):
        numeric = self.kind in ('increase', 'decrease')
        if numeric != (self.metric != 'none') or self.steps and not numeric:
            raise ValueError('Invalid numeric outcome')
        if self.proof_role and self.kind != {'tab': 'selected', 'text_field': 'page'}[self.proof_role]:
            raise ValueError('Invalid native proof role')
        if any(not label.strip() or len(label) > 80 for label in self.labels):
            raise ValueError('Invalid outcome label')
        return self


class OutcomeGoal(BaseModel):
    model_config = ConfigDict(extra='forbid', frozen=True)
    clauses: list[OutcomeClause] = Field(min_length=1, max_length=8)
    seal: str = Field(pattern=r'^[0-9a-f]{64}$')


class TaskState(BaseModel):
    model_config = ConfigDict(extra='forbid')
    task_id: str = Field(min_length=1, max_length=100)
    revision: int = Field(default=0, ge=0, le=100)
    subgoals: list[TaskSubgoal] = Field(default_factory=list, max_length=8)
    attempts: list[TaskAttempt] = Field(default_factory=list, max_length=8)
    completion: TaskCompletion | None = None
    outcome_goal: OutcomeGoal | None = None
    @model_validator(mode='after')
    def unique(self):
        if len({s.id for s in self.subgoals}) != len(self.subgoals):
            raise ValueError('Duplicate subgoal')
        return self


class TaskApp(BaseModel):
    model_config = ConfigDict(extra='forbid')
    package: str = Field(pattern=r'^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+$', max_length=200)
    label: str = Field(min_length=1, max_length=80)


class TaskRecoveryAction(BaseModel):
    """Untrusted prior execution evidence; it can restrict, never grant, tools."""
    model_config = ConfigDict(extra='forbid')
    tool: Literal['tap','set_text','scroll_forward','scroll_backward','back','open_app']
    package: str = Field(pattern=r'^[a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+$', max_length=200)
    target_id: str | None = Field(max_length=200)
    fingerprint: str = Field(pattern=r'^[0-9a-f]{64}$')


class TaskRecovery(BaseModel):
    model_config = ConfigDict(extra='forbid')
    policy: Literal['mc_v0','mc_v1','mc_v2']
    reason: Literal['accepted_action_unconfirmed','observed_transition_unconfirmed','execution_rejected','value_contradicted']
    last_verdict: Literal['waiting','rejected','contradicted']
    waited_ms: int = Field(strict=True, ge=0, le=120000)
    attempt: int = Field(strict=True, ge=1, le=3)
    observation_id: str = Field(min_length=1, max_length=200)
    last_action: TaskRecoveryAction
    blocked_actions: list[TaskRecoveryAction] = Field(max_length=16)

    @model_validator(mode='after')
    def versioned_reason(self):
        if self.reason == 'observed_transition_unconfirmed' and (self.policy not in {'mc_v1','mc_v2'} or self.attempt > 2):
            raise ValueError('Observed-transition coordination requires mc_v1/mc_v2 and bounded progress attempts')
        return self


class AppTaskRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    protocol_version: Literal['app-task.v2'] = 'app-task.v2'
    request_id: str = Field(min_length=1, max_length=100)
    goal: str = Field(min_length=1, max_length=200)
    screen: TaskScreen
    state: TaskState
    # A read-only evidence request may precede completion of the navigation plan.
    # The default preserves clients that infer result discovery from plan state.
    rejected_outcome: bool = False
    prepare_outcome: bool = False
    planning_mode: Literal['next_action','goal_first','result_only'] = 'next_action'
    # Only apps explicitly selected on the device; never an installed-app inventory.
    apps: list[TaskApp] = Field(default_factory=list, max_length=1)
    # Optional for older v2 clients; learned selectors never cross app versions.
    environment: dict[str, str | int] = Field(default_factory=dict, max_length=3)
    path_prefix: list[str] = Field(default_factory=list,max_length=64)
    recovery: TaskRecovery | None = None

    @model_validator(mode='after')
    def valid_prefix(self):
        import re
        if any(not re.fullmatch('[0-9a-f]{64}',p) for p in self.path_prefix):
            raise ValueError('Invalid execution prefix')
        if self.recovery is not None and self.recovery.observation_id != self.screen.observation_id:
            raise ValueError('Recovery must describe the current observation')
        return self


class TaskExpectation(BaseModel):
    model_config = ConfigDict(extra='forbid')
    kind: Literal['screen_changed','target_visible','input_present','selection_changed','package_opened']
    label: str = Field(default='', max_length=80)
    package: str = Field(default='', max_length=200)
    screen_key: str = Field(default='', pattern=r'^(?:[0-9a-f]{64})?$')
    checked: bool | None = None
    selected: bool | None = None

    @model_validator(mode='after')
    def state_condition(self):
        if self.kind != 'selection_changed' and (self.checked is not None or self.selected is not None):
            raise ValueError('Native state belongs to a selection condition')
        return self


class TaskAction(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: str
    tool: Literal['tap','set_text','scroll_forward','scroll_backward','back','open_app']
    target_id: str | None = None
    bounds: tuple[int,int,int,int] | None = None
    package: str = ''
    expectation: TaskExpectation
    advances_subgoal: bool = False


class TaskPlannedStep(BaseModel):
    """An advisory later step. The device re-grounds the exact label on the screen it then
    observes, after the previous step was natively verified, and the user still approves it."""
    model_config = ConfigDict(extra='forbid')
    tool: Literal['tap'] = 'tap'
    label: str = Field(min_length=1, max_length=80)


class AppTaskResponse(BaseModel):
    protocol_version: Literal['app-task.v2'] = 'app-task.v2'
    request_id: str
    task_id: str
    observation_id: str
    fingerprint: str
    status: Literal['proposed','needs_input','confirm_result','unavailable','protected']
    message: str
    subgoals: list[TaskSubgoal] = Field(default_factory=list)
    active_subgoal: str | None = None
    action: TaskAction | None = None
    completion: TaskCompletion | None = None
    outcome_goal: OutcomeGoal | None = None
    verification_goal: OutcomeGoal | None = None
    outcome_bindings: dict[str, TaskResultCheck] = Field(default_factory=dict, max_length=8)
    planned_steps: list[TaskPlannedStep] = Field(default_factory=list, max_length=3)
    # A predicted result that is NOT proof on this screen (its label is still a menu here).
    # The device may keep it only for later screens where it is native, non-actionable proof.
    deferred_completion: TaskCompletion | None = None
    # A proposal must be approved and revalidated locally before execution.
    requires_confirmation: Literal[True] = True
    diagnostic: dict = Field(default_factory=dict)
