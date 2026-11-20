"""Synthetic analysis fixtures; these do not assert any actual multi-app result."""
from copy import deepcopy

import pytest

from scripts.audit_meta_controller_public_apps_provider import audit
from scripts.summarize_meta_controller_public_apps import CONDITIONS, REVISION2, render, summarize
from tests.test_meta_controller_public_apps_experiment import manifest_data, PACKAGES
from tests.test_meta_controller_settings_v2_analysis import row, failure


SCHEMA='click.native-public-apps.v1'
MODE='live_native_public_apps'


def fixtures():
    catalogue=manifest_data();schedule=[];rows=[]
    for condition in sorted(CONDITIONS):
        for task in catalogue['tasks']:
            pair=f"public/{task['task_id']}/{condition}";arms=['A','B'] if len(schedule)%2==0 else ['B','A']
            schedule.append(dict(pair_id=pair,task=task['task_id'],package=task['package'],condition=condition,arms=arms))
            for position,arm in enumerate(arms):
                rows.append(row(task=task['task_id'],condition=condition,arm=arm,schema_version=SCHEMA,evidence_mode=MODE,
                    pair_id=pair,episode_id=pair+':'+arm,package=task['package'],arm_order=position))
    manifest=dict(schema_version=SCHEMA,evidence_mode=MODE,budget_ms=30000,measurement_revision=REVISION2,
        measurement_context_key='v2-context',catalogue=catalogue,scheduled_pairs=schedule)
    ready=dict(schema_version=SCHEMA,passed=True,measurement_revision=REVISION2,measurement_context_key='v2-context')
    return rows,manifest,ready


def summary(rows,manifest,ready):return summarize(rows,manifest,ready,audit(rows,[],episode_schema=SCHEMA))


def test_exact_three_app_matrix_retains_shared_equal_observation_rules():
    rows,manifest,ready=fixtures();result=summary(rows,manifest,ready)
    assert result['measurement_valid'] and result['frozen_matrix_complete']
    assert result['distinct_apps_tested']==3 and result['participants_tested']==0
    assert result['schema_version']=='click.native-public-apps-summary.v1'
    assert result['arms']['A']['primary_successes']==18
    assert set(result['packages'])==set(PACKAGES)
    assert all(result['packages'][p]['B']['primary_successes']==6 for p in PACKAGES)
    assert result['default_enablement_verdict']=='NO_GO_SMALL_SAMPLE_NO_HUMAN_VALIDATION'
    assert 'First stable-goal median' in render(result)


def test_classified_failure_remains_in_assigned_denominator_and_cost():
    rows,manifest,ready=fixtures();original=rows[0]
    rows[0]=failure(**{key:original[key] for key in ('task','condition','arm','schema_version','evidence_mode',
        'pair_id','episode_id','package','arm_order')},provider_errors=1)
    result=summary(rows,manifest,ready)
    assert result['measurement_valid'] and result['arms'][original['arm']]['primary_successes']==17
    assert result['arms'][original['arm']]['mean_budget_penalized_ms']==pytest.approx((17*1000+30000)/18,abs=.001)


@pytest.mark.parametrize('change',['wrong_package','wrong_arm_order','wrong_pair','wrong_task','wrong_schedule_package','missing_schedule'])
def test_task_and_app_scope_cannot_be_relabelled_after_freeze(change):
    rows,manifest,ready=fixtures()
    if change=='wrong_package':rows[0]['package']=PACKAGES[2]
    elif change=='wrong_arm_order':rows[0]['arm_order']=1-rows[0]['arm_order']
    elif change=='wrong_pair':rows[0]['pair_id']='not-frozen'
    elif change=='wrong_task':rows[0]['task']='not-frozen'
    elif change=='wrong_schedule_package':manifest['scheduled_pairs'][0]['package']=PACKAGES[2]
    else:manifest['scheduled_pairs'].pop()
    with pytest.raises(ValueError):summary(rows,manifest,ready)


def test_nonjoint_short_window_is_unknown_and_stops_normal_gate():
    rows,manifest,ready=fixtures();rows=[r for r in rows if r['condition']=='normal'];old=rows[0]
    rows[0]=failure(**{key:old[key] for key in ('task','condition','arm','schema_version','evidence_mode',
        'pair_id','episode_id','package','arm_order')},elapsed_ms=15,last_oracle_sample_ms=10)
    result=summary(rows,manifest,ready)
    assert not result['measurement_valid'] and result['normal_stratum_gate']=='STOP_MEASUREMENT_INVALID'
    assert result['arms'][old['arm']]['primary_unknown']==1


def test_empty_frozen_cohort_has_unknown_times_and_no_advance_gate():
    _,manifest,ready=fixtures();result=summary([],manifest,ready)
    assert result['unexecuted_episodes']==36
    assert result['speed_cost_success_gate']['mean_penalized_delta_B_minus_A_ms'] is None
    assert not result['speed_cost_success_gate']['eligible_for_repeated_validation']


def test_speed_cost_success_gate_requires_exposed_recovery_and_no_regression():
    rows,manifest,ready=fixtures()
    index=next(i for i,r in enumerate(rows) if r['condition']=='accepted_no_effect' and r['arm']=='A')
    old=rows[index];rows[index]=failure(**{key:old[key] for key in ('task','condition','arm','schema_version',
        'evidence_mode','pair_id','episode_id','package','arm_order')})
    result=summary(rows,manifest,ready)
    assert result['speed_cost_success_gate']['eligible_for_repeated_validation']
    assert result['next_validation_stage']=='GO_TO_REPEATED_READ_ONLY_VALIDATION'
    # A different app's B failure must not be hidden by this exposed recovery pair.
    other=next(i for i,r in enumerate(rows) if r['package']==PACKAGES[2] and r['arm']=='B')
    old=rows[other];rows[other]=failure(**{key:old[key] for key in ('task','condition','arm','schema_version',
        'evidence_mode','pair_id','episode_id','package','arm_order')})
    result=summary(rows,manifest,ready)
    assert PACKAGES[2] in result['speed_cost_success_gate']['app_primary_regressions']
    assert not result['speed_cost_success_gate']['eligible_for_repeated_validation']


def test_progress_only_exposed_joint_success_is_not_relabelled_as_a_failure():
    rows,manifest,ready=fixtures()
    index=next(i for i,r in enumerate(rows) if r['condition']=='delayed_transition' and r['arm']=='A')
    old=rows[index];rows[index]=failure(**{key:old[key] for key in ('task','condition','arm','schema_version',
        'evidence_mode','pair_id','episode_id','package','arm_order')})
    counterpart=next(r for r in rows if r['pair_id']==old['pair_id'] and r['arm']=='B')
    counterpart.update(recovery_count=0,progress_rescue_count=1,failure_replan_reservations=0)
    result=summary(rows,manifest,ready)
    assert result['b_only_exposed_success_pairs']==1
    assert result['speed_cost_success_gate']['eligible_for_repeated_validation']


def test_settings_cohorts_cannot_be_mixed_with_public_apps():
    rows,manifest,ready=fixtures();mixed=deepcopy(rows);mixed[0]['schema_version']='click.native-settings.v2'
    with pytest.raises(ValueError):audit(mixed,[])
    with pytest.raises(ValueError):summary(mixed,manifest,ready)


def test_paid_progress_and_failure_coordination_are_attributed_only_from_joined_observed_calls():
    rows,manifest,ready=fixtures();chosen=rows[0]
    chosen.update(planner_requests=1,actual_model_calls=1,request_traces=[dict(request_ref='1'*24,
        task_ref=chosen['task_ref'],server_ref='2'*24,http_status=200,model_call_state='observed',
        model_calls=1,model_wait_ms=17)])
    terminal=dict(schema_version='local-meta-controller-public-apps-facade.v1',evidence_mode=MODE,
        event='request_terminal',request_ref='1'*24,task_ref=chosen['task_ref'],server_ref='2'*24,
        http_status=200,model_call_state='observed',model_calls=1,model_wait_ms=17,
        recovery_context_used=True,capabilities_applied=True,allowed_actions_context_used=True,
        allowed_action_count=2,recovery_blocked_action_count=0,recovery_attempt=1,
        recovery_policy='mc_v1',recovery_reason='observed_transition_unconfirmed')
    joined=audit(rows,[terminal]);assert joined['status']=='DIRECT_JOIN_RECONCILED'
    result=summarize(rows,manifest,ready,joined)
    context=result['arms'][chosen['arm']]['audited_payload_context_evidence']
    assert context['observed_calls_by_recovery_reason']=={'observed_transition_unconfirmed':1}
    assert context['recovery_reason_missing_observed_attempts']==0
    terminal['recovery_policy']='mc_v0'
    joined=audit(rows,[terminal]);result=summarize(rows,manifest,ready,joined)
    assert joined['context_diagnostic_invalid_fields']
    context=result['arms'][chosen['arm']]['audited_payload_context_evidence']
    assert context['observed_calls_by_recovery_reason']=={}
    assert context['recovery_reason_missing_observed_attempts']==1
