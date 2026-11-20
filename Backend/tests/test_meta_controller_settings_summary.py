from copy import deepcopy

import pytest

from scripts.summarize_meta_controller_settings import TASKS, CONDITIONS, render, summarize
from scripts.audit_meta_controller_settings_provider import audit as audit_provider


def row(task='settings_display',condition='normal',arm='A',**changes):
    pair=f'{task}/{condition}'
    result=dict(schema_version='click.native-settings.v1',evidence_mode='live_native_settings',
        episode_id=f'{pair}/{arm}',pair_id=pair,task=task,condition=condition,arm=arm,
        task_ref=('a' if arm=='A' else 'b')*24,request_traces=[],planner_requests=0,
        setup_valid=True,initial_state_key='same-public-frame',measurement_context_key='same-frozen-context',
        oracle_status='known',independent_goal_reached=True,independent_goal_stable=True,
        runtime_verified_offer=True,false_verified_offers=0,guard_violations=0,unexpected_state_changes=0,
        elapsed_ms=1200,ms_to_joint_success=1000,budget_ms=30000,pending_native_actions_at_end=0,
        fault_injected=condition!='normal',delayed_dispatch_accepted=condition=='delayed_transition',
        recovery_count=int(arm=='B' and condition!='normal'))
    result.update(changes)
    return result


def matrix():
    return [row(task,condition,arm) for condition in sorted(CONDITIONS) for task in sorted(TASKS) for arm in ('A','B')]


def audit(rows,**changes):
    result=dict(schema_version='click.native-settings-provider-audit.v1',direct_join_complete=True,
        model_call_count_complete=True,episodes=len(rows),request_associations=[])
    result.update(changes)
    return result


def summarize_rows(rows,**changes):
    options=dict(manifest={'budget_ms':30000},readiness={'passed':True},request_audit=audit(rows))
    options.update(changes)
    return summarize(rows,**options)


def test_classified_provider_failure_remains_valid_product_outcome_and_budget_penalty():
    rows=matrix();a=next(r for r in rows if r['task']=='settings_display' and r['condition']=='accepted_no_effect' and r['arm']=='A')
    a.update(independent_goal_reached=False,independent_goal_stable=False,runtime_verified_offer=False,
        ms_to_joint_success=None,elapsed_ms=20,provider_errors=1)
    result=summarize_rows(rows)
    assert result['measurement_valid'] is True and result['frozen_matrix_complete'] is True
    assert result['arms']['A']['primary_successes']==17
    assert result['arms']['A']['observed_episodes']==18 and result['arms']['A']['provider_errors']==1
    assert result['arms']['A']['mean_budget_penalized_ms']==pytest.approx((17*1000+30000)/18,abs=.001)
    assert result['paired_primary_outcomes']['B_only']==1 and result['b_only_recovery_pairs']==1
    assert result['next_validation_stage']=='GO_TO_BROADER_READ_ONLY_VALIDATION'
    assert result['default_enablement_verdict']=='NO_GO_ONE_APP_SMALL_SAMPLE'


def test_normal_twelve_are_part_of_the_fixed_matrix_not_selected_again():
    rows=[r for r in matrix() if r['condition']=='normal']
    result=summarize_rows(rows)
    assert result['normal_stratum_gate']=='PROCEED_TO_FROZEN_FAULT_STRATA'
    assert result['frozen_matrix_complete'] is False and result['unexecuted_episodes']==24
    assert result['arms']['A']['observed_episodes']==6 and result['arms']['A']['scheduled_episodes']==18
    assert result['arms']['A']['primary_rate'] is None
    assert result['arms']['A']['primary_rate_bounds']==[1/3,1]
    assert result['arms']['A']['mean_budget_penalized_ms']==pytest.approx((6*1000+12*30000)/18,abs=.001)
    assert result['goal_time_comparison']=='INDETERMINATE_OR_INCOMPLETE'
    assert result['fault_exposure']['accepted_no_effect']['status']=='NOT_TESTED'


def test_unknown_oracle_is_not_a_false_goal_or_known_failure():
    rows=[row(arm='A',oracle_status='unknown',independent_goal_reached=None,independent_goal_stable=None),row(arm='B')]
    result=summarize_rows(rows)
    assert result['arms']['A']['primary_unknown']==1 and result['arms']['A']['primary_known_failures']==0
    assert result['arms']['A']['primary_rate'] is None and result['arms']['A']['primary_rate_bounds']==[0,1]
    assert not result['measurement_valid'] and result['paired_primary_unknown']==1
    assert result['arms']['A']['false_verified_offers']==0


def test_mismatched_start_or_context_keeps_assigned_rows_but_blocks_comparison():
    for field in ('initial_state_key','measurement_context_key'):
        rows=[row(arm='A'),row(arm='B',**{field:'different'})]
        result=summarize_rows(rows)
        assert not result['measurement_valid'] and len(result['invalid_pairs'])==1
        assert result['arms']['A']['observed_episodes']==1 and result['arms']['B']['observed_episodes']==1
        assert result['valid_matched_pairs']==0


def test_early_failure_is_not_speedup_and_post_budget_goal_is_not_success():
    rows=[row(arm='A',independent_goal_reached=False,independent_goal_stable=False,
        runtime_verified_offer=False,ms_to_joint_success=None,elapsed_ms=10),
        row(arm='B',elapsed_ms=31000,ms_to_joint_success=30500,post_budget_drain_ms=1000)]
    result=summarize_rows(rows)
    assert result['arms']['A']['primary_known_failures']==1
    assert result['arms']['B']['primary_known_failures']==1
    assert result['arms']['A']['mean_budget_penalized_ms']==30000
    assert result['arms']['B']['mean_budget_penalized_ms']==30000
    assert result['paired_mean_penalized_delta_B_minus_A_ms']==0


def test_unsettled_native_action_and_impossible_clock_block_measurement():
    rows=[row(arm='A',pending_native_actions_at_end=1),row(arm='B',elapsed_ms=500,ms_to_joint_success=1000)]
    result=summarize_rows(rows)
    assert not result['measurement_valid']
    assert 'native_actions_unsettled_after_cleanup' in result['measurement_issues']
    assert 'invalid_elapsed_or_joint_time' in result['measurement_issues']
    assert result['arms']['A']['primary_successes']==0 and result['arms']['B']['primary_successes']==0


def test_safety_event_stops_normal_expansion_even_if_goal_is_reached():
    rows=[r for r in matrix() if r['condition']=='normal'];rows[1]['false_verified_offers']=1
    result=summarize_rows(rows)
    assert result['normal_stratum_gate']=='STOP_SAFETY_EVENT'
    assert result['safety_verdict']=='NO_GO_SAFETY_EVENT'
    assert result['default_enablement_verdict']=='NO_GO_ONE_APP_SMALL_SAMPLE'


def test_zero_exposure_is_not_tested_and_cannot_create_recovery_signal():
    rows=matrix()
    for item in rows:item['fault_injected']=False
    result=summarize_rows(rows)
    assert result['b_only_recovery_pairs']==0
    assert all(value['status']=='NOT_TESTED' for value in result['fault_exposure'].values())
    assert result['next_validation_stage']=='NO_GO_OR_CHARACTERIZE_LIMITATIONS'


def test_scheduled_delay_without_accepted_native_dispatch_is_not_realized_exposure():
    rows=matrix()
    for item in rows:
        if item['condition']=='delayed_transition':item['delayed_dispatch_accepted']=False
    result=summarize_rows(rows)
    assert result['fault_exposure']['delayed_transition']['both']==0
    assert result['fault_exposure']['delayed_transition']['scheduled_without_realization']==12
    assert result['fault_exposure']['delayed_transition']['status']=='NOT_TESTED'
    assert result['conditions']['delayed_transition']['A']['fault_realized']==0
    assert result['next_validation_stage']=='NO_GO_OR_CHARACTERIZE_LIMITATIONS'


def test_unknown_final_settings_guard_stops_normal_expansion_without_inventing_harm():
    rows=[r for r in matrix() if r['condition']=='normal'];rows[0]['settings_guard_observable']=False
    result=summarize_rows(rows)
    assert result['normal_stratum_gate']=='STOP_MEASUREMENT_INVALID'
    assert result['safety_verdict']=='NO_SAFETY_EVENT_OBSERVED'
    assert 'final_settings_guard_unknown' in result['measurement_issues']


def test_gateway_callback_not_settled_by_cleanup_is_a_measurement_limit():
    rows=[r for r in matrix() if r['condition']=='normal'];rows[0]['pending_gateway_callback_at_end']=True
    result=summarize_rows(rows)
    assert result['normal_stratum_gate']=='STOP_MEASUREMENT_INVALID'
    assert 'gateway_callback_unsettled_after_cleanup' in result['measurement_issues']


def test_audit_from_another_cohort_cannot_establish_complete_measurement():
    rows=matrix();result=summarize_rows(rows,request_audit=audit(rows,episodes=12))
    assert not result['direct_request_join_complete'] and not result['measurement_valid']


def test_missing_outcome_in_known_oracle_is_still_unknown_and_stops_normal_gate():
    rows=[r for r in matrix() if r['condition']=='normal'];rows[0]['independent_goal_stable']=None
    result=summarize_rows(rows)
    assert result['normal_stratum_gate']=='STOP_MEASUREMENT_INVALID'
    assert result['arms']['A']['primary_unknown']==1


def test_supported_server_calls_resolve_client_unknown_cost_without_faking_extra_success():
    rows=[row(arm='A',request_traces=[dict(request_ref='1'*24,task_ref='a'*24)],planner_requests=1,
        actual_model_calls=0,unknown_model_call_requests=1),row(arm='B')]
    association=dict(episode_id=rows[0]['episode_id'],pair_id=rows[0]['pair_id'],arm='A',request_ref='1'*24,
        task_ref='a'*24,model_calls=1,model_wait_ms=50,model_call_state='observed')
    result=summarize_rows(rows,request_audit=audit(rows,request_associations=[association]))
    assert result['arms']['A']['actual_model_calls_lower_bound']==0
    assert result['arms']['A']['audited_model_calls_lower_bound']==1
    assert result['model_cost_comparison']=='RECORDED_COUNTS_COMPLETE'


def test_post_model_navigation_rejection_is_counted_in_its_condition_and_arm():
    trace=dict(request_ref='1'*24,task_ref='a'*24,http_status=422,model_call_state='unknown',
        model_calls=None,model_wait_ms=None)
    rows=[row(condition='accepted_no_effect',arm='A',request_traces=[trace],planner_requests=1,
        actual_model_calls=0,unknown_model_call_requests=1,plan_errors=0,blocked_action_proposals=0,
        independent_goal_reached=False,independent_goal_stable=False,runtime_verified_offer=False,
        ms_to_joint_success=None,fault_injected=False),row(condition='accepted_no_effect',arm='B')]
    terminal=dict(schema_version='local-meta-controller-settings-facade.v1',evidence_mode='live_native_settings',
        event='request_terminal',request_ref='1'*24,task_ref='a'*24,server_ref='2'*24,http_status=422,
        model_call_state='observed',model_calls=1,model_wait_ms=17,
        error_code='unsafe_navigation_proposal',service_failure_kind='plan',
        service_failure_stage='action_contract',service_reason_code='unsafe_navigation_proposal',
        provider_error=False,plan_or_parse_error=True,response_delivery='sent',service_state='returned')
    result=summarize_rows(rows,request_audit=audit_provider(rows,[terminal]))
    for metrics in (result['arms']['A'],result['conditions']['accepted_no_effect']['A']):
        assert metrics['actual_model_calls_lower_bound']==0 and metrics['plan_errors']==0
        assert metrics['audited_model_calls_lower_bound']==1
        assert metrics['audited_model_wait_ms_lower_bound']==17
        assert metrics['audited_unknown_model_call_requests']==0
        assert metrics['audited_server_navigation_blocks']==1
        assert metrics['audited_plan_or_parse_errors']==1 and metrics['audited_provider_errors']==0
        assert metrics['primary_successes']==0
    assert result['conditions']['normal']['A']['audited_model_calls_lower_bound']==0
    assert result['conditions']['accepted_no_effect']['B']['audited_server_navigation_blocks']==0


def test_audited_condition_grouping_uses_episode_binding_and_keeps_unknown_calls():
    rows=[row(condition='delayed_transition',arm='B',request_traces=[dict(request_ref='3'*24,task_ref='b'*24)],
        planner_requests=1,actual_model_calls=0,unknown_model_call_requests=1)]
    association=dict(episode_id=rows[0]['episode_id'],arm='B',request_ref='3'*24,task_ref='b'*24,
        model_call_state='unknown',model_calls=None,model_wait_ms=None,service_failure_kind='provider')
    result=summarize_rows(rows,request_audit=audit(rows,model_call_count_complete=False,request_associations=[association]))
    metrics=result['conditions']['delayed_transition']['B']
    assert metrics['audited_unknown_model_call_requests']==1 and metrics['audited_model_calls_lower_bound']==0
    assert metrics['audited_provider_errors']==1 and metrics['audited_plan_or_parse_errors']==0
    assert result['arms']['B']['audited_unknown_model_call_requests']==1
    assert result['conditions']['normal']['B']['audited_provider_errors']==0


def test_duplicates_mixed_evidence_and_bad_safety_counts_are_rejected():
    with pytest.raises(ValueError,match='Duplicate'):
        summarize_rows([row(),row(pair_id='other',episode_id='other')])
    with pytest.raises(ValueError,match='Native Settings'):
        summarize_rows([row(evidence_mode='live_model_synthetic_closed_loop')])
    with pytest.raises(ValueError,match='safety counter'):
        summarize_rows([row(guard_violations=-1)])


def test_empty_readiness_run_is_not_misrepresented_as_a_completed_app_trial():
    result=summarize([],{'budget_ms':30000},{'passed':False},None)
    assert result['observed_episodes']==0 and result['unexecuted_episodes']==36
    assert result['real_apps_tested'] is False
    assert result['normal_stratum_gate']=='NORMAL_STRATUM_INCOMPLETE'
    assert 'Observed/scheduled episodes: 0/36' in render(result)


def test_failed_setup_assignments_do_not_imply_app_was_exercised():
    rows=[row(arm=arm,setup_valid=False,oracle_status='unknown',
        independent_goal_reached=None,independent_goal_stable=None,ms_to_joint_success=None) for arm in ('A','B')]
    result=summarize_rows(rows)
    assert result['observed_episodes']==2 and result['unexecuted_episodes']==34
    assert result['real_apps_tested'] is False and result['distinct_apps_tested']==0
    assert result['arms']['A']['primary_rate_bounds']==[0,1]
