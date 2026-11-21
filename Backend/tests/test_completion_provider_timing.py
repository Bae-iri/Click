"""Controlled timing fixtures; no live provider or native-efficacy claim."""
from copy import deepcopy
from hashlib import sha256
import pytest
from scripts.summarize_completion_provider_timing import summarize
from tests.test_meta_controller_public_apps_analysis import fixtures, REVISION2
from tests.test_meta_controller_settings_v2_analysis import failure


def ref(value):return sha256(value.encode()).hexdigest()[:24]


def cohort(extended=False):
    original,_,_=fixtures();templates={r['task']:r for r in original if r['condition']=='normal' and r['arm']=='A'}
    assignments=[];rows=[];associated=[]
    for block,(repeat,policy) in enumerate([('r1','gemini'),('r1','jev'),('r2','jev'),('r2','gemini'),('r3','gemini'),('r3','jev')]):
        for task,template in templates.items():
            pair=f'block{block}/{task}/normal';episode=pair+':A';task_ref=ref(episode)
            a=dict(episode_id=episode,pair_id=pair,task=task,package=template['package'],arm='A',condition='normal',repeat=repeat,completion_policy=policy,block_id=f'block{block}')
            assignments.append(a)
            if block>=4 and not extended:continue
            row=deepcopy(template);row.update({k:a[k] for k in ('episode_id','pair_id','task','package','arm','condition')});joint=2000 if policy=='gemini' else 1000
            trace=dict(request_ref=ref(episode+'request'),task_ref=task_ref,model_call_state='observed',model_calls=1,model_wait_ms=100 if policy=='gemini' else 20,
                queue_ms=0,work_ms=110,total_ms=110,planning_mode='result_only',completion_provider=policy)
            row.update(task_ref=task_ref,coordinator_policy='current',arm_order=0,planner_requests=1,request_traces=[trace],actual_model_calls=1,
                actual_model_wait_ms=trace['model_wait_ms'],ms_to_joint_success=joint,elapsed_ms=joint+100,last_oracle_sample_ms=joint,
                first_runtime_verified_ms=joint-100,measurement_revision=REVISION2,native_action_trace=[],completion_evidence_traces=[dict(stage='evidence',reason='second_frame',elapsed_ms=400)])
            rows.append(row);associated.append(dict(episode_id=episode,task_ref=task_ref,request_ref=trace['request_ref'],direct_checks_passed=True,
                model_call_state='observed',model_calls=1,model_wait_ms=trace['model_wait_ms'],planning_mode='result_only',completion_provider=policy,
                recovery_context_used=False,capabilities_applied=True,allowed_actions_context_used=False,allowed_action_count=0,recovery_blocked_action_count=0))
    manifest=dict(schema_version='click.a-completion-timing-manifest.v1',budget_ms=30000,extended=extended,assignments=assignments[:24],conditional_assignments=assignments[24:],measurement_context_key='v2-context',preparation=dict(passed=True))
    return rows,manifest,dict(direct_join_complete=True,model_call_count_complete=True,request_associations=associated)


def test_primary_and_conditional_denominators_are_separate():
    rows,m,a=cohort();r=summarize(rows,m,a)
    assert r['matrix_complete'] and r['measurement_valid'] and r['scheduled_episodes']==r['observed_episodes']==24
    assert r['conditional_not_run_episodes']==12
    assert all(x['scheduled_episodes']==12 for x in r['policies'].values())
    assert all(x['scheduled_episodes']==4 for app in r['packages'].values() for x in app.values())
    assert all(x['scheduled_episodes']==2 for goal in r['goals'].values() for x in goal.values())
    assert r['matched_actual_completion_provider_pairs']==12 and r['comparison_gate']['eligible_for_next_narrow_validation']
    assert r['default_enablement_verdict']=='NO_GO_SMALL_SAMPLE_SINGLE_DEVICE'


def test_explicit_extension_is_required_for_thirty_six():
    rows,m,a=cohort(True);r=summarize(rows,m,a)
    assert r['scheduled_episodes']==36 and r['conditional_not_run_episodes']==0 and r['policies']['jev']['scheduled_episodes']==18
    m['extended']=False
    with pytest.raises(ValueError):summarize(rows,m,a)


@pytest.mark.parametrize('change',['duplicate','task','arm','package','order','missing_schedule','policy'])
def test_changed_assignment_is_rejected(change):
    rows,m,a=cohort()
    if change=='duplicate':rows.append(deepcopy(rows[0]))
    elif change=='task':rows[0]['task']='unknown'
    elif change=='arm':rows[0]['arm']='B'
    elif change=='package':rows[0]['package']='org.private.app'
    elif change=='order':m['assignments'][0],m['assignments'][6]=m['assignments'][6],m['assignments'][0]
    elif change=='missing_schedule':m['assignments'].pop()
    else:m['assignments'][0]['completion_policy']='other'
    with pytest.raises(ValueError):summarize(rows,m,a)


def test_provider_failure_stays_in_assigned_time_and_cost():
    rows,m,a=cohort();old=rows[6]
    keys=('task','package','arm','condition','episode_id','pair_id','task_ref','request_traces','planner_requests','actual_model_calls','actual_model_wait_ms','coordinator_policy','measurement_revision','schema_version','evidence_mode','arm_order','native_action_trace')
    rows[6]=failure(**{k:old[k] for k in keys},provider_errors=1);r=summarize(rows,m,a)
    assert r['measurement_valid'] and r['policies']['jev']['primary_known_failures']==1
    assert r['policies']['jev']['mean_budget_penalized_ms']==pytest.approx((11*1000+30000)/12,abs=.001)
    assert r['policies']['jev']['audited_model_calls_lower_bound']==12 and not r['comparison_gate']['eligible_for_next_narrow_validation']


@pytest.mark.parametrize('change',['missing','task','duplicate','episode','unknown'])
def test_complete_flag_cannot_hide_mismatched_request_binding(change):
    rows,m,a=cohort();items=a['request_associations']
    if change=='missing':items.pop()
    elif change=='task':items[0]['task_ref']='wrong'
    elif change=='duplicate':items.append(deepcopy(items[0]))
    elif change=='episode':items[0]['episode_id']='other'
    else:items[0].update(model_call_state='unknown',model_calls=None)
    r=summarize(rows,m,a)
    assert not r['direct_request_join_complete'] and not r['comparison_gate']['hard_gates_passed']
    assert r['policies']['gemini']['audited_model_calls_lower_bound'] is None


def test_empty_and_missing_policy_cannot_pass():
    rows,m,a=cohort();r=summarize([],m,dict(direct_join_complete=True,model_call_count_complete=True,request_associations=[]))
    assert r['unexecuted_episodes']==24 and not r['comparison_gate']['hard_gates_passed']
    rows[0]['coordinator_policy']=None;r=summarize(rows,m,a)
    assert not r['measurement_valid'] and r['observed_episodes']==24


def test_short_observation_is_unknown_not_cheap_failure():
    rows,m,a=cohort();rows[0].update(joint_success=False,ms_to_joint_success=None,elapsed_ms=15,last_oracle_sample_ms=10);r=summarize(rows,m,a)
    assert not r['measurement_valid'] and r['policies']['gemini']['primary_unknown']==1 and r['observation_issues']


def test_signed_latency_and_provider_missing_values_are_not_replaced():
    rows,m,a=cohort();rows[6]['first_runtime_verified_ms']=800;rows[6]['native_action_trace']=[dict(native_accepted=True,elapsed_ms=950)];r=summarize(rows,m,a)
    assert r['policies']['jev']['time_breakdown']['successful_stable_to_first_verified_ms']['minimum']==-100
    assert r['policies']['jev']['time_breakdown']['post_stable_native_action_count']==1
    assert r['requests']['actual_provider_requests']['jev_completion']['observed_provider_attempts']==12
    a['request_associations'][6].pop('completion_provider');r=summarize(rows,m,a)
    assert not r['measurement_valid'] and r['requests']['provider_attribution_missing_requests']==1


def test_not_started_is_not_actual_completion_exposure():
    rows,m,a=cohort()
    for row,item in zip(rows,a['request_associations']):
        row['request_traces'][0].update(model_call_state='not_started',model_calls=0,model_wait_ms=0);row['actual_model_calls']=0
        item.update(model_call_state='not_started',model_calls=0,model_wait_ms=0)
    r=summarize(rows,m,a)
    assert r['matched_actual_completion_provider_pairs']==0 and r['conditional_extension_eligible']
    assert not r['comparison_gate']['eligible_for_next_narrow_validation']
    assert r['requests']['actual_provider_requests']['provider_not_started']['api_requests']==24
    assert r['requests']['actual_provider_requests']['provider_not_started']['observed_provider_attempts']==0


def test_wrong_runtime_provider_cannot_be_hidden_by_assignment_label():
    rows,m,a=cohort();a['request_associations'][0]['completion_provider']='jev'
    r=summarize(rows,m,a)
    assert not r['measurement_valid'] and 'runtime_completion_policy_mismatch' in r['measurement_issues']


def test_reused_block_identity_cannot_overfill_six_episode_denominator():
    rows,m,a=cohort()
    for item in m['assignments'][6:12]:item['block_id']=m['assignments'][0]['block_id']
    with pytest.raises(ValueError):summarize(rows,m,a)


@pytest.mark.parametrize('field,value', [('completion_provider','gemini'),('planning_mode','goal_first'),('model_calls',0),('model_call_state','not_started'),('model_wait_ms',999),('goal_assessment','satisfied'),('input_tokens',0)])
def test_audit_cannot_fabricate_known_client_metadata(field,value):
    rows,m,a=cohort();item=a['request_associations'][6];item[field]=value
    r=summarize(rows,m,a)
    assert not r['direct_request_join_complete'] and not r['comparison_gate']['hard_gates_passed']
    assert not r['comparison_gate']['eligible_for_next_narrow_validation']


@pytest.mark.parametrize('field', ['completion_provider','planning_mode'])
@pytest.mark.parametrize('side', ['client','audit'])
def test_required_provider_metadata_presence_must_match(field,side):
    rows,m,a=cohort()
    target=rows[6]['request_traces'][0] if side=='client' else a['request_associations'][6]
    target.pop(field);r=summarize(rows,m,a)
    assert not r['direct_request_join_complete'] and not r['comparison_gate']['eligible_for_next_narrow_validation']


def test_unknown_client_can_be_resolved_by_exact_server_join_without_inventing_known_metadata():
    rows,m,a=cohort();trace=rows[6]['request_traces'][0]
    trace.update(model_call_state='unknown',model_calls=None,model_wait_ms=None)
    trace.pop('completion_provider');trace.pop('planning_mode')
    rows[6]['actual_model_calls']=0;rows[6]['unknown_model_call_requests']=1
    r=summarize(rows,m,a)
    assert r['direct_request_join_complete'] and r['policies']['jev']['audited_model_calls_lower_bound']==12


def test_actual_execution_order_must_match_frozen_prefix():
    rows,m,a=cohort();rows.reverse();r=summarize(rows,m,a)
    assert not r['measurement_valid'] and 'execution_order_mismatch' in r['measurement_issues']


def test_fabricated_jev_exposure_is_unknown_instead_of_counted_as_four_pairs():
    rows,m,a=cohort()
    for index in list(range(6,12))+list(range(12,18)):
        rows[index]['request_traces'][0]['completion_provider']='gemini'
    r=summarize(rows,m,a)
    assert not r['direct_request_join_complete']
    assert r['matched_actual_completion_provider_pairs'] is None
    assert all(p['both_actual_completion_providers_exposed'] is None for p in r['paired_outcomes'])
    assert not r['requests']['audit_binding_verified']
    assert not r['comparison_gate']['actual_completion_exposure_sufficient']
    assert not r['comparison_gate']['eligible_for_next_narrow_validation']
