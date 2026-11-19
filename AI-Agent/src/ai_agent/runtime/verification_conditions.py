"""Closed, observation-based success conditions shared by graph and future planners.

The evaluator runs on trusted local evidence, not on model scores or generated
claims. These contracts do not enable input capture or authorize data export.
"""
from dataclasses import dataclass
from enum import Enum
from math import isfinite
import re


class ConditionType(str, Enum):
    SCREEN_ENTERED = 'screen_entered'
    ELEMENT_VISIBLE = 'element_visible'
    STATE_CHANGED = 'state_changed'
    VALUE_CHANGED = 'value_changed'
    INPUT_COMPLETED = 'input_completed'
    TASK_COMPLETED = 'task_completed'


class Outcome(str, Enum):
    SUCCESS = 'SUCCESS'
    WAITING = 'WAITING'
    UNKNOWN = 'UNKNOWN'
    FAILED = 'FAILED'
    BLOCKED = 'BLOCKED'


NEXT_ACTION = {Outcome.SUCCESS: 'advance', Outcome.WAITING: 'wait',
               Outcome.UNKNOWN: 'observe_or_ask_user', Outcome.FAILED: 'retry_selection',
               Outcome.BLOCKED: 'handoff_or_replan'}
BLOCKERS = frozenset({'permission_required', 'authentication_required', 'app_restriction'})


@dataclass(frozen=True)
class Assessment:
    status: Outcome
    reason: str
    condition_type: ConditionType | None = None
    waiting_for: str | None = None

    def to_dict(self):
        # No labels, text, field values, node IDs, or model prose in this wire object.
        return {'schema_version': 'verification-assessment.v1', 'status': self.status.value,
                'condition_type': self.condition_type.value if self.condition_type else None,
                'reason_code': self.reason, 'waiting_for': self.waiting_for,
                'next_action': NEXT_ACTION[self.status],
                'counts_as_failure': self.status == Outcome.FAILED}


@dataclass(frozen=True)
class Condition:
    """Server-approved expectation. A model proposal alone is not an approval.

    screen: independent heading AND core control in the same app/window.
    input: exact local digest only; syntactic validity alone is not equality.
    task: conjunction rechecked on the current observation (not old success flags).
    """
    kind: ConditionType
    packages: tuple[str, ...] = ()
    target_key: str | None = None
    titles: tuple[str, ...] = ()
    roles: tuple[str, ...] = ()
    expected_state: bool | None = None
    direction: str | None = None
    value_source: str | None = None
    expected_digest: str | None = None
    checks: tuple['Condition', ...] = ()

    def __post_init__(self):
        if not isinstance(self.kind, ConditionType): raise ValueError('Unknown verification type')
        if self.kind == ConditionType.TASK_COMPLETED:
            if not 1 <= len(self.checks) <= 16 or any(not isinstance(c, Condition) or c.kind == self.kind for c in self.checks):
                raise ValueError('Task completion needs bounded atomic postconditions')
            return
        if (not isinstance(self.packages, tuple) or not self.packages
                or any(not isinstance(p, str) or not p for p in self.packages)
                or not isinstance(self.target_key, str) or not self.target_key):
            raise ValueError('Missing observation binding')
        if self.kind == ConditionType.SCREEN_ENTERED and (not self.titles or not self.roles):
            raise ValueError('Screen requires heading and core control')
        if self.kind == ConditionType.STATE_CHANGED and type(self.expected_state) is not bool:
            raise ValueError('Expected native checked state required')
        if self.kind == ConditionType.VALUE_CHANGED and (
                self.direction not in {'increase', 'decrease'} or not self.value_source):
            raise ValueError('Direction and numeric source required')
        if self.kind == ConditionType.INPUT_COMPLETED and not re.fullmatch(r'[a-f0-9]{64}', self.expected_digest or ''):
            raise ValueError('Local exact-value digest required')


    @classmethod
    def from_dict(cls, value):
        """Parse planner data without treating its expectation as approved evidence."""
        allowed = {'kind', 'packages', 'target_key', 'titles', 'roles', 'expected_state',
                   'direction', 'value_source', 'expected_digest', 'checks'}
        if not isinstance(value, dict) or not set(value) <= allowed or 'kind' not in value:
            raise ValueError('Invalid condition contract')
        data = dict(value)
        data['kind'] = ConditionType(data['kind'])
        for field in ('packages', 'titles', 'roles'):
            items = data.get(field, [])
            if (not isinstance(items, list) or len(items) > 16
                    or any(not isinstance(v, str) or not v or len(v) > 256 for v in items)):
                raise ValueError('Invalid condition identities')
            data[field] = tuple(items)
        for field in ('target_key', 'direction', 'value_source', 'expected_digest'):
            if field in data and data[field] is not None and (
                    not isinstance(data[field], str) or not data[field] or len(data[field]) > 256):
                raise ValueError('Invalid condition parameter')
        children = data.pop('checks', [])
        if (not isinstance(children, list) or len(children) > 16
                or any(not isinstance(c, dict) or c.get('kind') == ConditionType.TASK_COMPLETED.value for c in children)):
            raise ValueError('Invalid completion checks')
        if data['kind'] != ConditionType.TASK_COMPLETED and children:
            raise ValueError('Atomic condition cannot contain checks')
        data['checks'] = tuple(cls.from_dict(c) for c in children)
        return cls(**data)

    def to_dict(self):
        # Contains expectations only. Never write field digests to logs or metadata.
        result = {'kind': self.kind.value}
        for field in ('packages', 'target_key', 'titles', 'roles', 'expected_state',
                      'direction', 'value_source', 'expected_digest'):
            value = getattr(self, field)
            if value is not None and value != ():
                result[field] = list(value) if isinstance(value, tuple) else value
        if self.checks: result['checks'] = [c.to_dict() for c in self.checks]
        return result


@dataclass(frozen=True)
class ElementEvidence:
    target_key: str
    package: str
    window_id: int
    role: str
    visible: bool = True
    enabled: bool = True
    label: str = ''
    independent_heading: bool = False
    checked: bool | None = None
    value: float | None = None
    value_source: str | None = None
    unit: str | None = None
    minimum: float | None = None
    maximum: float | None = None
    text_digest: str | None = None
    input_valid: bool | None = None
    native: bool = True


@dataclass(frozen=True)
class Frame:
    scope: str  # session + episode, created by the executor
    observation_id: str
    captured_at_ms: int
    package: str
    active_window_id: int
    elements: tuple[ElementEvidence, ...] = ()
    complete: bool = False
    loading: bool = False
    blocker: str | None = None  # executor-supplied closed code; never model text


@dataclass(frozen=True)
class ActionProof:
    scope: str
    baseline_observation_id: str
    target_key: str
    occurred_at_ms: int
    event_id: str


def _numeric(value):
    return type(value) in (int, float) and isfinite(value)


def _clock(frame):
    return bool(frame and frame.scope and frame.observation_id
                and type(frame.captured_at_ms) is int and frame.captured_at_ms >= 0)


def _targets(condition, frame):
    return tuple(e for e in frame.elements if e.visible and e.native
        and e.target_key == condition.target_key and e.package == frame.package
        and e.package in condition.packages and e.window_id == frame.active_window_id
        and (not condition.roles or e.role in condition.roles))


def _same_snapshot(a, b):
    return (a.package, a.active_window_id, a.elements) == (b.package, b.active_window_id, b.elements)


def evaluate(condition, current, *, baseline=None, previous=None, action=None,
             approved=False, settle_ms=500):
    """Evaluate an already-reviewed condition; UNKNOWN cannot increment failures.

    `approved` is set by the server after goal/condition review, never accepted
    from an LLM or client flag. No generated predicate, expression or code runs.
    """
    def result(status, reason, wait=None): return Assessment(status, reason, condition.kind, wait)
    if approved is not True: return result(Outcome.UNKNOWN, 'condition_not_approved')
    if type(settle_ms) is not int or settle_ms < 0: raise ValueError('Invalid settling interval')
    if not _clock(current): return result(Outcome.UNKNOWN, 'missing_observation_identity')
    if previous is not None and (not _clock(previous) or previous.scope != current.scope
            or previous.observation_id == current.observation_id
            or previous.captured_at_ms >= current.captured_at_ms):
        return result(Outcome.UNKNOWN, 'stale_or_unbound_observation')
    if baseline is not None and (not _clock(baseline) or baseline.scope != current.scope
            or baseline.observation_id == current.observation_id
            or baseline.captured_at_ms >= current.captured_at_ms):
        return result(Outcome.UNKNOWN, 'stale_or_unbound_baseline')
    if action is not None and (not baseline or not action.event_id
            or action.scope != current.scope or action.baseline_observation_id != baseline.observation_id
            or type(action.occurred_at_ms) is not int
            or not baseline.captured_at_ms < action.occurred_at_ms < current.captured_at_ms):
        return result(Outcome.UNKNOWN, 'uncorrelated_action')
    if current.blocker in BLOCKERS: return result(Outcome.BLOCKED, current.blocker)
    if current.blocker is not None: return result(Outcome.UNKNOWN, 'unknown_blocker')
    if current.loading: return result(Outcome.WAITING, 'screen_loading', 'observation')
    if not current.complete or not current.elements:
        return result(Outcome.UNKNOWN, 'incomplete_observation')
    if condition.kind == ConditionType.TASK_COMPLETED:
        # This checks final state invariants. An old subgoal-success bit is never enough.
        children = [evaluate(c, current, baseline=baseline, previous=previous, action=action,
                             approved=True, settle_ms=settle_ms) for c in condition.checks]
        for state in (Outcome.BLOCKED, Outcome.UNKNOWN, Outcome.FAILED, Outcome.WAITING):
            failed = next((child for child in children if child.status == state), None)
            if failed: return result(state, failed.reason, failed.waiting_for)
        return result(Outcome.SUCCESS, 'final_postconditions_observed')
    if current.package not in condition.packages:
        return result(Outcome.UNKNOWN, 'unexpected_app')
    targets = _targets(condition, current)
    if len(targets) > 1: return result(Outcome.UNKNOWN, 'ambiguous_target')
    target = targets[0] if targets else None
    observed = False
    if condition.kind == ConditionType.SCREEN_ENTERED:
        headings = [e for e in current.elements if e.native and e.visible
            and e.package == current.package and e.window_id == current.active_window_id
            and e.independent_heading and e.label in condition.titles]
        observed = bool(headings and target and target.enabled)
    elif condition.kind == ConditionType.ELEMENT_VISIBLE:
        observed = target is not None
    else:
        if action is None: return result(Outcome.WAITING, 'no_correlated_action', 'user')
        if action.target_key != condition.target_key:
            return result(Outcome.UNKNOWN, 'action_target_mismatch')
        if baseline.package != current.package or baseline.active_window_id != current.active_window_id:
            return result(Outcome.UNKNOWN, 'target_context_changed')
        before = _targets(condition, baseline)
        if not baseline.complete or len(before) != 1 or target is None: return result(Outcome.UNKNOWN, 'target_unobservable')
        before = before[0]
        if previous and baseline.captured_at_ms < previous.captured_at_ms <= action.occurred_at_ms:
            earlier = _targets(condition, previous)
            if len(earlier) == 1 and any(getattr(earlier[0], key) != getattr(before, key)
                    for key in ('value', 'checked', 'text_digest')):
                return result(Outcome.UNKNOWN, 'change_precedes_action')
        if condition.kind == ConditionType.STATE_CHANGED:
            if type(before.checked) is not bool or type(target.checked) is not bool:
                return result(Outcome.UNKNOWN, 'native_state_unobservable')
            observed = before.checked is not condition.expected_state and target.checked is condition.expected_state
        elif condition.kind == ConditionType.VALUE_CHANGED:
            if (not _numeric(before.value) or not _numeric(target.value)
                    or before.value_source != condition.value_source or target.value_source != condition.value_source
                    or not before.unit or before.unit != target.unit or not baseline.complete):
                return result(Outcome.UNKNOWN, 'numeric_source_unobservable')
            if before.minimum != target.minimum or before.maximum != target.maximum:
                return result(Outcome.UNKNOWN, 'numeric_range_changed')
            for element in (before, target):
                if ((element.minimum is not None and (not _numeric(element.minimum) or element.value < element.minimum))
                        or (element.maximum is not None and (not _numeric(element.maximum) or element.value > element.maximum))):
                    return result(Outcome.UNKNOWN, 'numeric_value_out_of_range')
            observed = target.value > before.value if condition.direction == 'increase' else target.value < before.value
        elif condition.kind == ConditionType.INPUT_COMPLETED:
            if not re.fullmatch(r'[a-f0-9]{64}', target.text_digest or ''):
                return result(Outcome.UNKNOWN, 'input_value_unobservable')
            observed = target.text_digest == condition.expected_digest and target.input_valid is not False
    if observed: return result(Outcome.SUCCESS, 'postcondition_observed')
    if action is None: return result(Outcome.WAITING, 'no_correlated_action', 'user')
    if (previous and previous.complete and previous.elements
            and previous.captured_at_ms > action.occurred_at_ms
            and current.captured_at_ms - previous.captured_at_ms >= settle_ms
            and _same_snapshot(previous, current)):
        return result(Outcome.FAILED, 'settled_postcondition_not_met')
    return result(Outcome.WAITING, 'awaiting_settled_observation', 'observation')


def summarize_legacy(result, *, blocker=None):
    """Expose five-state semantics without changing the established runtime enum.

    Existing correlated/native validators remain authoritative. This adapter
    cannot upgrade a legacy unknown, guide, model score or timeout to success.
    """
    if blocker in BLOCKERS:
        return Assessment(Outcome.BLOCKED, blocker).to_dict()
    if result is None:
        return Assessment(Outcome.UNKNOWN, 'verification_not_available').to_dict()
    status = result.status.value
    kind = None
    reason = result.evidence or 'insufficient_evidence'
    if reason in {'state_reached', 'observed_progress', 'settings_page_visible'}: kind = ConditionType.SCREEN_ENTERED
    elif reason in {'target_value_increased', 'target_value_decreased'}: kind = ConditionType.VALUE_CHANGED
    elif reason in {'target_checked_on', 'target_checked_off', 'native_switch_already_set', 'wifi_already_set',
                    'target_mode_mute', 'target_mode_vibrate', 'target_mode_sound'}: kind = ConditionType.STATE_CHANGED
    mapping = {'SUCCESS': Outcome.SUCCESS, 'WAITING_FOR_ACTION': Outcome.WAITING,
               'WAITING_FOR_OBSERVATION': Outcome.WAITING, 'UNKNOWN': Outcome.UNKNOWN,
               'NO_CHANGE': Outcome.FAILED, 'WRONG_STATE': Outcome.FAILED}
    outcome = mapping.get(status, Outcome.UNKNOWN)
    if status == 'DONE' and result.task_completed: outcome = Outcome.SUCCESS
    # Evidence strings are internal legacy codes, but do not forward arbitrary ones.
    reason = reason if reason in LEGACY_REASONS else 'insufficient_evidence'
    wait = 'user' if status == 'WAITING_FOR_ACTION' else 'observation' if status == 'WAITING_FOR_OBSERVATION' else None
    summary = Assessment(outcome, reason, kind, wait).to_dict()
    summary['counts_as_failure'] = bool(outcome == Outcome.FAILED and result.count_attempt)
    summary['task_completed'] = bool(outcome == Outcome.SUCCESS and result.task_completed)
    return summary


LEGACY_REASONS = frozenset({
    'unknown_transition', 'unsupported_verification_predicate', 'observation_precedes_action',
    'transient_screen_unavailable', 'state_reached', 'observed_progress', 'no_correlated_action',
    'missing_observation_timing', 'mode_choice_unobservable', 'system_mode_unobservable',
    'native_switch_unobservable', 'setting_switch_identity_changed', 'target_checked_on',
    'target_checked_off', 'wifi_state_transition', 'wifi_state_unobservable',
    'target_value_unobservable', 'target_value_out_of_range', 'target_range_changed',
    'applied_setting_unobservable', 'target_value_increased', 'target_value_decreased',
    'target_value_changed_opposite_direction', 'settled_postcondition_not_met',
    'settled_wrong_state', 'observation_timeout', 'awaiting_settled_observation',
    'settings_page_visible', 'native_switch_already_set', 'wifi_already_set',
    'target_mode_mute', 'target_mode_vibrate', 'target_mode_sound',
    'commerce_page_visible', 'commerce_effect_observed', 'commerce_manual_result_required',
    'manual_result_required', 'insufficient_evidence',
})
