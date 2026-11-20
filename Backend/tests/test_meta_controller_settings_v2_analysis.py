"""Version isolation and actual-wall-time observation contracts, without providers."""
from copy import deepcopy
import json
from pathlib import Path

import pytest

from scripts.audit_meta_controller_settings_provider import audit
from scripts.summarize_meta_controller_settings import CONDITIONS, TASKS, REVISION2, render, summarize


V2='click.native-settings.v2'
ROOT=Path(__file__).resolve().parents[2]


def row(task='settings_display',condition='normal',arm='A',**changes):
    pair=f'v2/{task}/{condition}'
    result=dict(schema_version=V2,evidence_mode='live_native_settings',episode_id=f'{pair}/{arm}',
        pair_id=pair,task=task,condition=condition,arm=arm,task_ref=('a' if arm=='A' else 'b')*24,
        request_traces=[],planner_requests=0,actual_model_calls=0,unknown_model_call_requests=0,
        setup_valid=True,initial_state_key='same-public-frame',measurement_context_key='v2-context',
        budget_ms=30000,elapsed_ms=1100,ms_to_joint_success=1000,joint_success=True,
        oracle_status='known',independent_goal_reached=True,independent_goal_stable=True,
        runtime_verified_offer=True,ever_runtime_verified_offer=True,ever_independent_goal_stable=True,
        first_independent_stable_goal_ms=900,first_runtime_verified_ms=850,
        oracle_samples=8,oracle_known_samples=7,oracle_unknown_samples=1,last_oracle_sample_ms=1000,
        false_verified_offers=0,guard_violations=0,unexpected_state_changes=0,
        pending_native_actions_at_end=0,pending_gateway_callback_at_end=False,settings_guard_observable=True,
        cleanup_oracle_status='known',goal_present_after_cleanup=True,
        fault_injected=condition!='normal',delayed_dispatch_accepted=condition=='delayed_transition',
        recovery_count=int(arm=='B' and condition!='normal'))
    result.update(changes)
    return result


def failure(**changes):
    values=dict(elapsed_ms=30010,joint_success=False,ms_to_joint_success=None,independent_goal_reached=False,
        independent_goal_stable=False,runtime_verified_offer=False,ever_runtime_verified_offer=False,
        ever_independent_goal_stable=False,first_independent_stable_goal_ms=None,first_runtime_verified_ms=None,
        oracle_samples=100,oracle_known_samples=100,oracle_unknown_samples=0,last_oracle_sample_ms=29900,
        goal_present_after_cleanup=False)
    values.update(changes)
    return row(**values)


def manifest():
    return dict(schema_version=V2,evidence_mode='live_native_settings',budget_ms=30000,
        measurement_revision=REVISION2,measurement_context_key='v2-context')


def readiness():
    return dict(schema_version=V2,passed=True,measurement_revision=REVISION2,measurement_context_key='v2-context')


def summary(rows,**changes):
    kwargs=dict(manifest=manifest(),readiness=readiness(),request_audit=audit(rows,[],episode_schema=V2))
    kwargs.update(changes)
    return summarize(rows,**kwargs)


def matrix():
    return [row(task,condition,arm) for condition in sorted(CONDITIONS) for task in sorted(TASKS) for arm in ('A','B')]


def call_case(state='observed',**metadata):
    trace=dict(request_ref='1'*24,task_ref='a'*24,http_status=422,model_call_state='unknown',
        model_calls=None,model_wait_ms=None)
    rows=[failure(arm='A',condition='accepted_no_effect',planner_requests=1,request_traces=[trace],
        unknown_model_call_requests=1),failure(arm='B',condition='accepted_no_effect')]
    terminal=dict(schema_version='local-meta-controller-settings-facade.v1',evidence_mode='live_native_settings',
        event='request_terminal',request_ref='1'*24,task_ref='a'*24,server_ref='2'*24,http_status=422,
        model_call_state=state,model_calls=1 if state=='observed' else None,model_wait_ms=17 if state=='observed' else None,
        error_code='unsafe_navigation_proposal',service_failure_kind='plan',plan_or_parse_error=True,
        recovery_context_used=True,capabilities_applied=True,allowed_actions_context_used=True,
        allowed_action_count=2,recovery_blocked_action_count=1,recovery_attempt=1)
    terminal.update(metadata)
    return rows,[terminal]


def test_frozen_v1_audit_summary_and_markdown_are_byte_identical():
    base=ROOT/'docs/reviews/2026-11-20-native-settings'
    read=lambda name:[json.loads(line) for line in (base/name).read_text().splitlines() if line.strip()]
    audited=audit(read('episodes.jsonl'),read('provider-terminal.jsonl'))
    assert json.dumps(audited,ensure_ascii=False,indent=2)+'\n'==(base/'request-audit.json').read_text()
    result=summarize(read('episodes.jsonl'),json.loads((base/'manifest.json').read_text()),
        json.loads((base/'readiness.json').read_text()),audited)
    assert json.dumps(result,ensure_ascii=False,indent=2)+'\n'==(base/'summary.json').read_text()
    assert render(result)==(base/'summary.md').read_text()


def test_v2_classified_failure_remains_assigned_and_penalized_with_full_observation():
    rows=matrix();index=next(i for i,r in enumerate(rows) if r['condition']=='accepted_no_effect' and r['arm']=='A')
    original=rows[index];rows[index]=failure(task=original['task'],condition=original['condition'],arm='A',provider_errors=1)
    result=summary(rows)
    assert result['schema_version']=='click.native-settings-summary.v2'
    assert result['measurement_valid'] and result['frozen_matrix_complete']
    assert result['arms']['A']['primary_successes']==17 and result['arms']['A']['provider_errors']==1
    assert result['arms']['A']['mean_budget_penalized_ms']==pytest.approx((17*1000+30000)/18,abs=.001)
    assert result['normal_stratum_gate']=='PROCEED_TO_FROZEN_FAULT_STRATA'
    assert result['default_enablement_verdict']=='NO_GO_ONE_APP_SMALL_SAMPLE'


def test_v2_terminal_failure_cannot_hide_early_observation_termination():
    rows=[r for r in matrix() if r['condition']=='normal']
    old=rows[0];rows[0]=failure(task=old['task'],arm=old['arm'],elapsed_ms=15,last_oracle_sample_ms=10)
    result=summary(rows)
    assert not result['measurement_valid'] and result['normal_stratum_gate']=='STOP_MEASUREMENT_INVALID'
    assert 'nonjoint_observation_ended_early' in result['v2_observation_issues'][0]['issues']
    assert result['arms']['A']['primary_unknown']==1


def test_v2_transient_unknown_samples_are_not_false_negatives_or_false_offers():
    rows=[row(arm='A'),row(arm='B')];result=summary(rows)
    assert result['measurement_valid'] and result['arms']['A']['primary_successes']==1
    assert result['arms']['A']['oracle_unknown_samples']==1
    assert result['arms']['A']['oracle_unknown_sample_fraction']==1/8
    assert result['arms']['A']['false_verified_offers']==0
    rows[0].update(oracle_known_samples=0,oracle_unknown_samples=8)
    result=summary(rows)
    assert result['arms']['A']['primary_unknown']==1 and not result['measurement_valid']
    assert result['arms']['A']['primary_known_failures']==0


@pytest.mark.parametrize('changes',[
    dict(first_independent_stable_goal_ms=1050),
    dict(first_runtime_verified_ms=1050),
    dict(ever_runtime_verified_offer=False),
    dict(last_oracle_sample_ms=30001),
    dict(joint_success=False),
    dict(oracle_known_samples=8),
    dict(cleanup_oracle_status='unknown'),
])
def test_v2_contradictory_timeline_or_oracle_metadata_is_unknown_not_success(changes):
    result=summary([row(arm='A',**changes),row(arm='B')])
    assert not result['measurement_valid'] and result['arms']['A']['primary_successes']==0
    assert result['arms']['A']['primary_unknown']==1


def test_v2_cleanup_presence_cannot_become_first_arrival_or_primary_success():
    result=summary([failure(arm='A',goal_present_after_cleanup=True),failure(arm='B')])
    a=result['arms']['A']
    assert result['measurement_valid'] and a['primary_successes']==0
    assert a['goal_present_after_cleanup']==1 and a['first_independent_stable_goal_ms_median'] is None
    assert a['mean_budget_penalized_ms']==30000


def test_v2_revoked_offer_remains_secondary_and_cannot_produce_joint_success():
    result=summary([failure(arm='A',ever_runtime_verified_offer=True,first_runtime_verified_ms=850),failure(arm='B')])
    assert result['measurement_valid'] and result['arms']['A']['primary_successes']==0
    assert result['arms']['A']['ever_runtime_verified_offers']==1
    assert result['arms']['A']['first_runtime_verified_ms_median']==850


def test_v2_post_model_http_reject_keeps_cost_and_condition_forwarding_evidence():
    rows,terminal=call_case();audited=audit(rows,terminal);result=summary(rows,request_audit=audited)
    assert audited['schema_version']=='click.native-settings-provider-audit.v2'
    assert audited['provider_model_calls']==1 and result['measurement_valid']
    for metrics in (result['arms']['A'],result['conditions']['accepted_no_effect']['A']):
        assert metrics['audited_model_calls_lower_bound']==1 and metrics['audited_server_navigation_blocks']==1
        evidence=metrics['audited_payload_context_evidence']
        assert evidence['recovery_context_forwarded_attempts']==1
        assert evidence['trusted_capabilities_applied_attempts']==1
        assert evidence['allowed_actions_context_forwarded_attempts']==1
        assert evidence['allowed_action_count_sum']==2 and evidence['removed_blocked_pair_count_sum']==1
        assert metrics['primary_successes']==0


def test_v2_unknown_call_and_untyped_metadata_never_prove_model_forwarding():
    rows,terminal=call_case(state='unknown');audited=audit(rows,terminal)
    result=summary(rows,request_audit=audited)
    evidence=result['arms']['A']['audited_payload_context_evidence']
    assert audited['direct_join_complete'] and not audited['model_call_count_complete']
    assert evidence['recovery_context_forwarded_attempts']==0 and evidence['unknown_model_state_requests']==1
    rows,terminal=call_case(allowed_action_count=True,recovery_context_used=False)
    audited=audit(rows,terminal);assert audited['direct_join_complete']
    assert {x['field'] for x in audited['context_diagnostic_invalid_fields']}=={'allowed_action_count','recovery_attempt'}
    assert 'recovery_attempt' not in audited['request_associations'][0]
    evidence=summary(rows,request_audit=audited)['arms']['A']['audited_payload_context_evidence']
    assert evidence['metadata_missing_or_invalid_observed_attempts']==1
    assert evidence['recovery_context_forwarded_attempts']==0


def test_versions_never_mix_and_v2_requires_its_own_readiness_and_audit():
    v1=row(schema_version='click.native-settings.v1');v2=row(arm='B')
    with pytest.raises(ValueError,match='Mixed'):audit([v1,v2],[])
    with pytest.raises(ValueError,match='Mixed'):summarize([v1,v2],manifest(),readiness(),{})
    with pytest.raises(ValueError,match='version mismatch'):summary([v2],manifest={'schema_version':'click.native-settings.v1'})
    result=summary([v2],request_audit={'schema_version':'click.native-settings-provider-audit.v1','direct_join_complete':True})
    assert not result['direct_request_join_complete']
    result=summary([v2],readiness={**readiness(),'measurement_context_key':'another-cohort'})
    assert not result['readiness_passed'] and not result['measurement_valid']
    assert audit([],[],episode_schema=V2)['schema_version']=='click.native-settings-provider-audit.v2'
