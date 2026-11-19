"""Adversarial postcondition tests: observation gaps are not failed attempts."""
from dataclasses import replace
from hashlib import sha256
import pytest

from ai_agent.runtime.verification_conditions import (
    ActionProof, Condition, ConditionType as Kind, ElementEvidence as E, Frame,
    Outcome, evaluate, summarize_legacy,
)
from ai_agent.runtime.types import VerificationResult, VerificationStatus as Legacy

P = ('com.android.settings',)
TARGET = E('target', P[0], 7, 'slider', value=1.0, value_source='system.font_scale', unit='scale')
HEADING = E('heading', P[0], 7, 'text', label='글자 크기', independent_heading=True)
BASE = Frame('session:episode', 'before', 1000, P[0], 7, (HEADING, TARGET), complete=True)
ACTION = ActionProof(BASE.scope, BASE.observation_id, 'target', 1500, 'event')
CURRENT = replace(BASE, observation_id='after', captured_at_ms=2000,
                  elements=(HEADING, replace(TARGET, value=1.2)))
VALUE = Condition(Kind.VALUE_CHANGED, P, 'target', direction='increase', value_source='system.font_scale')
SCREEN = Condition(Kind.SCREEN_ENTERED, P, 'target', titles=('글자 크기',), roles=('slider',))


def check(condition=VALUE, current=CURRENT, **kwargs):
    return evaluate(condition, current, baseline=BASE, action=ACTION, approved=True, **kwargs)


@pytest.mark.parametrize('kind', list(Kind))
def test_no_condition_can_be_approved_by_model_output_alone(kind):
    conditions = {
        Kind.SCREEN_ENTERED: SCREEN,
        Kind.ELEMENT_VISIBLE: Condition(Kind.ELEMENT_VISIBLE, P, 'target'),
        Kind.STATE_CHANGED: Condition(Kind.STATE_CHANGED, P, 'target', expected_state=True),
        Kind.VALUE_CHANGED: VALUE,
        Kind.INPUT_COMPLETED: Condition(Kind.INPUT_COMPLETED, P, 'target', expected_digest='a'*64),
        Kind.TASK_COMPLETED: Condition(Kind.TASK_COMPLETED, checks=(SCREEN,)),
    }
    result = evaluate(conditions[kind], CURRENT)
    assert result.status == Outcome.UNKNOWN
    assert not result.to_dict()['counts_as_failure']


def test_screen_arrival_needs_independent_heading_and_core_control():
    assert check(SCREEN).status == Outcome.SUCCESS
    menu_heading = replace(HEADING, independent_heading=False, role='button')
    assert check(SCREEN, replace(CURRENT, elements=(menu_heading, CURRENT.elements[1]))).status != Outcome.SUCCESS
    assert check(SCREEN, replace(CURRENT, elements=(HEADING,))).status != Outcome.SUCCESS
    assert check(SCREEN, replace(CURRENT, elements=(replace(HEADING, window_id=8), CURRENT.elements[1]))).status != Outcome.SUCCESS


def test_element_visibility_is_not_action_execution_or_task_completion():
    condition = Condition(Kind.ELEMENT_VISIBLE, P, 'target')
    result = evaluate(condition, CURRENT, approved=True)
    assert result.status == Outcome.SUCCESS
    assert result.condition_type == Kind.ELEMENT_VISIBLE
    assert 'task_completed' not in result.to_dict()


@pytest.mark.parametrize('change,reason', [
    ({'elements': ()}, 'incomplete_observation'),
    ({'complete': False}, 'incomplete_observation'),
    ({'scope': 'other-episode'}, 'stale_or_unbound_baseline'),
    ({'observation_id': 'before'}, 'stale_or_unbound_baseline'),
    ({'captured_at_ms': 1000}, 'stale_or_unbound_baseline'),
    ({'captured_at_ms': 1400}, 'uncorrelated_action'),
    ({'active_window_id': 8}, 'target_context_changed'),
])
def test_missing_stale_or_cross_episode_evidence_never_counts_as_failure(change,reason):
    result = check(current=replace(CURRENT, **change))
    assert result.status == Outcome.UNKNOWN
    assert result.reason == reason
    assert not result.to_dict()['counts_as_failure']


def test_action_wait_and_loading_wait_are_distinct():
    waiting = evaluate(VALUE, CURRENT, baseline=BASE, approved=True)
    assert waiting.status == Outcome.WAITING and waiting.waiting_for == 'user'
    loading = check(current=replace(CURRENT, loading=True))
    assert loading.status == Outcome.WAITING and loading.waiting_for == 'observation'


def test_native_numeric_change_requires_same_setting_source_and_unit():
    assert check().status == Outcome.SUCCESS
    for kwargs in ({'value_source': 'slider.position'}, {'unit': 'percent'}, {'value': float('nan')},
                   {'maximum': 7}, {'unit': None}, {'value': True}, {'native': False}):
        result = check(current=replace(CURRENT, elements=(HEADING, replace(CURRENT.elements[1], **kwargs))))
        assert result.status == Outcome.UNKNOWN
        assert not result.to_dict()['counts_as_failure']


def test_native_checked_state_requires_correlated_change_not_just_matching_state():
    before = replace(TARGET, role='toggle', checked=False)
    current = replace(before, checked=True)
    condition = Condition(Kind.STATE_CHANGED, P, 'target', expected_state=True)
    assert evaluate(condition, replace(CURRENT, elements=(current,)), baseline=replace(BASE, elements=(before,)),
        action=ACTION, approved=True).status == Outcome.SUCCESS
    assert evaluate(condition, replace(CURRENT, elements=(current,)), baseline=replace(BASE, elements=(current,)),
        action=ACTION, approved=True).status != Outcome.SUCCESS


def test_valid_input_is_not_proof_of_the_requested_input_or_submission():
    digest = sha256(b'expected query').hexdigest()
    condition = Condition(Kind.INPUT_COMPLETED, P, 'target', expected_digest=digest)
    field = replace(TARGET, role='text_field', text_digest=sha256(b'wrong query').hexdigest(), input_valid=True)
    assert check(condition, replace(CURRENT, elements=(field,))).status != Outcome.SUCCESS
    matched = replace(field, text_digest=digest)
    assert check(condition, replace(CURRENT, elements=(matched,))).status == Outcome.SUCCESS
    assert check(condition, replace(CURRENT, elements=(replace(matched, input_valid=False),))).status != Outcome.SUCCESS
    assert digest not in str(check(condition, replace(CURRENT, elements=(matched,))).to_dict())


def test_failed_requires_two_stable_post_action_observations():
    unchanged = replace(CURRENT, elements=BASE.elements)
    first = check(current=unchanged)
    assert first.status == Outcome.WAITING
    settled = replace(unchanged, observation_id='settled', captured_at_ms=2600)
    failed = check(current=settled, previous=unchanged)
    assert failed.status == Outcome.FAILED and failed.to_dict()['counts_as_failure']
    gap = replace(unchanged, complete=False, elements=())
    assert check(current=settled, previous=gap).status != Outcome.FAILED


def test_task_completion_rechecks_all_current_conditions_instead_of_historical_success():
    marker = Condition(Kind.ELEMENT_VISIBLE, P, 'target')
    composite = Condition(Kind.TASK_COMPLETED, checks=(SCREEN, marker))
    assert check(composite).status == Outcome.SUCCESS
    no_title = replace(CURRENT, elements=(CURRENT.elements[1],))
    assert check(composite, no_title).status != Outcome.SUCCESS


@pytest.mark.parametrize('blocker', ['permission_required', 'authentication_required', 'app_restriction'])
def test_known_blockers_are_not_failed_attempts(blocker):
    result = check(current=replace(CURRENT, blocker=blocker))
    assert result.status == Outcome.BLOCKED
    assert not result.to_dict()['counts_as_failure']
    assert result.to_dict()['next_action'] == 'handoff_or_replan'


def test_ambiguous_target_never_becomes_success():
    assert check(current=replace(CURRENT, elements=(*CURRENT.elements, CURRENT.elements[1]))).status == Outcome.UNKNOWN


@pytest.mark.parametrize('old,new,wait', [
    (Legacy.SUCCESS, Outcome.SUCCESS, None),
    (Legacy.WAITING_FOR_ACTION, Outcome.WAITING, 'user'),
    (Legacy.WAITING_FOR_OBSERVATION, Outcome.WAITING, 'observation'),
    (Legacy.UNKNOWN, Outcome.UNKNOWN, None),
    (Legacy.NO_CHANGE, Outcome.FAILED, None),
    (Legacy.WRONG_STATE, Outcome.FAILED, None),
])
def test_legacy_projection_preserves_status_and_does_not_expose_free_text(old,new,wait):
    result = VerificationResult(old, 'state', evidence='PRIVATE_EMAIL@example.com', count_attempt=True)
    metadata = summarize_legacy(result)
    assert metadata['status'] == new.value and metadata['waiting_for'] == wait
    assert 'PRIVATE' not in str(metadata)
    assert metadata['counts_as_failure'] == (new == Outcome.FAILED)


def test_done_without_confirmed_completion_is_not_success():
    assert summarize_legacy(VerificationResult(Legacy.DONE, 's'))['status'] == 'UNKNOWN'


@pytest.mark.parametrize('build', [
    lambda: Condition('invented'),
    lambda: Condition(Kind.SCREEN_ENTERED, P, 'target'),
    lambda: Condition(Kind.VALUE_CHANGED, P, 'target', direction='increase'),
    lambda: Condition(Kind.INPUT_COMPLETED, P, 'target', expected_digest='plain input'),
    lambda: Condition(Kind.TASK_COMPLETED),
    lambda: Condition(Kind.TASK_COMPLETED, checks=(Condition(Kind.TASK_COMPLETED, checks=(SCREEN,)),)),
])
def test_unknown_or_uncheckable_conditions_are_rejected(build):
    with pytest.raises(ValueError): build()


def test_changed_value_observed_before_delayed_action_cannot_be_credited_to_it():
    earlier=replace(CURRENT,observation_id='pre-event',captured_at_ms=1400)
    assert check(previous=earlier).status==Outcome.UNKNOWN
    assert check(previous=earlier).reason=='change_precedes_action'


def test_stale_protected_screen_is_not_a_new_blocker():
    old=replace(BASE,blocker='authentication_required')
    assert check(current=old).status==Outcome.UNKNOWN


def test_string_true_is_not_server_approval():
    assert evaluate(SCREEN,CURRENT,approved='true').status==Outcome.UNKNOWN


def test_planner_condition_round_trip_remains_unapproved():
    spec=Condition.from_dict({'kind':'task_completed','checks':[SCREEN.to_dict(),VALUE.to_dict()]})
    assert spec.to_dict()=={'kind':'task_completed','checks':[SCREEN.to_dict(),VALUE.to_dict()]}
    assert evaluate(spec,CURRENT,baseline=BASE,action=ACTION).status==Outcome.UNKNOWN


@pytest.mark.parametrize('spec', [
    {'kind':'value_changed','packages':list(P),'target_key':'target','direction':'increase','value_source':'system.font_scale','code':'return True'},
    {'kind':'invented'},
    {'kind':'screen_entered','packages':'com.android.settings','target_key':'target'},
    {'kind':'element_visible','packages':list(P),'target_key':'target','approved':True},
    {'kind':'task_completed','checks':[{'kind':'task_completed','checks':[]}]},
])
def test_planner_contract_rejects_generated_code_approval_flags_and_unbounded_shapes(spec):
    with pytest.raises(ValueError):Condition.from_dict(spec)
