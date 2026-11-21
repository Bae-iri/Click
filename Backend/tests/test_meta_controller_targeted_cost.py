"""Controlled analysis fixtures; no provider calls or actual cohort claims."""
from copy import deepcopy
from hashlib import sha256

import pytest

from scripts.audit_meta_controller_public_apps_provider import audit
from scripts.summarize_meta_controller_targeted_cost import targeted_summary, reference_comparison
from tests.test_meta_controller_public_apps_analysis import fixtures, SCHEMA


def selected():
    rows, manifest, ready = fixtures()
    manifest['coordinator_policies'] = {'A': 'current', 'B': 'mc_v2'}
    rows = [row for row in rows if row['condition'] == 'accepted_no_effect']
    for row in rows:
        row['coordinator_policy'] = manifest['coordinator_policies'][row['arm']]
    return rows, manifest, ready


def summarize(rows, manifest, ready, joined=None):
    return targeted_summary(rows, manifest, ready,
        audit(rows, [], episode_schema=SCHEMA) if joined is None else joined)


def test_twelve_assigned_subset_does_not_turn_twenty_four_not_run_into_failures():
    rows, manifest, ready = selected()
    result = summarize(rows, manifest, ready)
    assert result['targeted_matrix_complete'] and result['measurement_valid']
    assert result['comparison_evaluable'] and result['cost_competitive_with_A']
    assert result['targeted_scheduled_episodes'] == result['observed_episodes'] == 12
    assert result['targeted_unexecuted_episodes'] == 0
    assert result['deliberately_not_run_episodes'] == 24
    assert result['full_frozen_schedule_episodes'] == 36
    assert result['full_matrix_complete'] is False
    assert all(result['arms'][arm]['primary_successes'] == 6 for arm in ('A', 'B'))
    assert all(data[arm]['primary_successes'] == 2 for data in result['packages'].values() for arm in ('A', 'B'))
    assert result['api_attempt_delta_B_minus_A'] == 0
    assert result['targeted_execution_verdict'] == 'PASS_TARGETED_MEASUREMENT'
    assert result['default_enablement_verdict'] == 'NO_GO_TARGETED_SUBSET_ONLY'


@pytest.mark.parametrize('change', ['normal_row', 'missing_manifest_policy', 'old_manifest_policy',
    'extra_manifest_policy', 'wrong_order', 'missing_schedule'])
def test_selected_condition_frozen_schedule_and_policy_provenance_fail_closed(change):
    rows, manifest, ready = selected()
    if change == 'normal_row': rows[0]['condition'] = 'normal'
    elif change == 'missing_manifest_policy': manifest.pop('coordinator_policies')
    elif change == 'old_manifest_policy': manifest['coordinator_policies']['B'] = 'mc_v1'
    elif change == 'extra_manifest_policy': manifest['coordinator_policies']['C'] = 'mc_v2'
    elif change == 'wrong_order': rows[0]['arm_order'] = 1 - rows[0]['arm_order']
    else: manifest['scheduled_pairs'].pop()
    with pytest.raises(ValueError): summarize(rows, manifest, ready)


def test_empty_and_incomplete_selected_cohorts_cannot_pass_or_claim_default_enablement():
    rows, manifest, ready = selected()
    for observed in ([], rows[:-1]):
        result = summarize(observed, manifest, ready)
        assert result['targeted_unexecuted_episodes'] == 12 - len(observed)
        assert not result['targeted_matrix_complete']
        assert not result['comparison_evaluable'] and not result['cost_competitive_with_A']
        assert result['targeted_execution_verdict'] == 'INCOMPLETE_OR_MEASUREMENT_LIMIT'
        assert result['default_enablement_verdict'] == 'NO_GO_TARGETED_SUBSET_ONLY'


def test_unverified_call_counts_remain_unknown_and_block_the_cost_gate():
    rows, manifest, ready = selected()
    joined = audit(rows, [], episode_schema=SCHEMA)
    joined['model_call_count_complete'] = False
    result = summarize(rows, manifest, ready, joined)
    assert result['api_attempt_delta_B_minus_A'] is None
    assert not result['model_count_complete']
    assert not result['comparison_evaluable'] and not result['cost_competitive_with_A']


def previous_rows(rows):
    result=deepcopy(rows)
    for row in result:row['episode_id']='previous/'+row['episode_id']
    return result


def reference_audit(rows, b_calls):
    associations=[]
    for row in rows:
        row['task_ref']=sha256(('task/'+row['episode_id']).encode()).hexdigest()[:24]
        row['planner_requests']=b_calls if row['arm']=='B' else 1
        row['request_traces']=[]
        for index in range(row['planner_requests']):
            request=sha256((row['episode_id']+'/request/'+str(index)).encode()).hexdigest()[:24]
            trace=dict(request_ref=request,task_ref=row['task_ref'],model_call_state='observed',model_calls=1)
            row['request_traces'].append(trace)
            associations.append(dict(episode_id=row['episode_id'],**trace))
    return dict(direct_join_complete=True,model_call_count_complete=True,request_associations=associations)


def test_historical_comparison_requires_four_exposed_valid_start_matched_arms():
    rows, _, _ = selected()
    previous = previous_rows(rows)
    current_audit, old_audit = reference_audit(rows, 2), reference_audit(previous, 4)
    result = reference_comparison(rows, previous, current_audit, old_audit)
    assert result['matched_tasks'] == 6
    assert result['previous_B_calls'] == 24 and result['current_B_calls'] == 12
    assert result['previous_B_joint'] == result['current_B_joint'] == 6
    assert 'does not establish' in result['note']
    # Each distinct task fails a different part of the four-way inclusion rule.
    tasks = list(dict.fromkeys(row['task'] for row in rows))
    previous[:] = [row for row in previous if not (row['task'] == tasks[0] and row['arm'] == 'A')]
    next(row for row in previous if row['task'] == tasks[1] and row['arm'] == 'B')['initial_state_key'] = 'different-start'
    next(row for row in previous if row['task'] == tasks[2] and row['arm'] == 'A')['fault_injected'] = False
    next(row for row in rows if row['task'] == tasks[3] and row['arm'] == 'B')['setup_valid'] = False
    result = reference_comparison(rows, previous, current_audit, old_audit)
    assert result['matched_tasks'] == 2
    assert {row['task'] for row in result['tasks']} == set(tasks[4:])
    assert result['previous_B_calls'] == 8 and result['current_B_calls'] == 4


def test_unverified_or_empty_reference_cannot_be_reported_as_zero_cost():
    rows, _, _ = selected()
    previous = previous_rows(rows)
    current_audit, old_audit = reference_audit(rows, 2), reference_audit(previous, 4)
    old_audit['direct_join_complete'] = False
    assert reference_comparison(rows, previous, current_audit, old_audit) == {'status': 'REFERENCE_COUNTS_UNVERIFIED'}
    result = reference_comparison([], previous, current_audit, old_audit | {'direct_join_complete': True})
    assert result['matched_tasks'] == 0
    assert result['previous_B_calls'] is None and result['current_B_calls'] is None


def test_unknown_reference_attempt_stays_unknown_even_when_summary_metadata_claims_complete():
    rows, _, _ = selected()
    previous = previous_rows(rows)
    current_audit, old_audit = reference_audit(rows, 2), reference_audit(previous, 4)
    next(item for item in old_audit['request_associations'] if item['episode_id'].endswith(':B'))['model_call_state'] = 'unknown'
    result = reference_comparison(rows, previous, current_audit, old_audit)
    assert result['matched_tasks'] == 6
    assert result['previous_B_calls'] is None and result['current_B_calls'] is None
    assert any(item['previous_B_calls'] is None for item in result['tasks'])


@pytest.mark.parametrize('policy',[None,'mc_v1',False])
def test_missing_or_wrong_episode_policy_preserves_assigned_rows_as_measurement_limited(policy):
    rows,manifest,ready=selected();rows[0]['coordinator_policy']=policy
    result=summarize(rows,manifest,ready)
    assert result['observed_episodes']==result['targeted_scheduled_episodes']==12
    assert result['targeted_unexecuted_episodes']==0
    assert result['deliberately_not_run_episodes']==24
    assert not result['measurement_valid'] and not result['comparison_evaluable']
    assert not result['cost_competitive_with_A']
    assert len(result['policy_provenance_issues'])==1
    assert all(result['arms'][arm]['scheduled_episodes']==6 for arm in ('A','B'))


def test_setup_failure_without_runtime_policy_remains_an_assigned_unknown():
    rows,manifest,ready=selected();failed=rows[0]
    failed.update(setup_valid=False,coordinator_policy=None,oracle_status='unknown',independent_goal_reached=None,
        independent_goal_stable=None,joint_success=False,ms_to_joint_success=None)
    result=summarize(rows,manifest,ready)
    assert result['observed_episodes']==12 and result['targeted_unexecuted_episodes']==0
    assert result['arms'][failed['arm']]['primary_unknown']==1
    assert result['arms'][failed['arm']]['scheduled_episodes']==6
    assert not result['measurement_valid'] and not result['comparison_evaluable']


@pytest.mark.parametrize('damage',['missing_entries','wrong_task','wrong_request','duplicate_request','wrong_planner_count'])
def test_reference_association_mismatch_never_becomes_zero_cost(damage):
    rows,_,_=selected();previous=previous_rows(rows)
    joined=reference_audit(rows,2);old=reference_audit(previous,4)
    target=next(row for row in previous if row['arm']=='B')
    entries=[entry for entry in old['request_associations'] if entry['episode_id']==target['episode_id']]
    if damage=='missing_entries':old['request_associations']=[entry for entry in old['request_associations'] if entry not in entries]
    elif damage=='wrong_task':entries[0]['task_ref']='f'*24
    elif damage=='wrong_request':entries[0]['request_ref']='f'*24
    elif damage=='duplicate_request':entries[0]['request_ref']=entries[1]['request_ref']
    else:target['planner_requests']+=1
    result=reference_comparison(rows,previous,joined,old)
    assert result['matched_tasks']==6
    assert result['previous_B_calls'] is None and result['current_B_calls'] is None
    assert next(row for row in result['tasks'] if row['task']==target['task'])['previous_B_calls'] is None
