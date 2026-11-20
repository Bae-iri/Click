from copy import deepcopy

import pytest

from scripts.audit_meta_controller_settings_provider import audit


def sample(client_state='observed',server_state='observed',status=200):
    client_calls=1 if client_state=='observed' else 0 if client_state=='not_started' else None
    server_calls=1 if server_state=='observed' else 0 if server_state=='not_started' else None
    trace=dict(request_ref='1'*24,task_ref='a'*24,http_status=status,model_call_state=client_state,
        model_calls=client_calls,model_wait_ms=12 if client_calls==1 else 0 if client_calls==0 else None)
    episode=dict(schema_version='click.native-settings.v1',evidence_mode='live_native_settings',
        episode_id='episode',pair_id='pair',arm='A',condition='accepted_no_effect',task_ref='a'*24,request_traces=[trace],planner_requests=1,
        actual_model_calls=1 if client_calls==1 else 0)
    terminal=dict(schema_version='local-meta-controller-settings-facade.v1',evidence_mode='live_native_settings',
        event='request_terminal',request_ref='1'*24,task_ref='a'*24,server_ref='2'*24,http_status=status,
        model_call_state=server_state,model_calls=server_calls,model_wait_ms=12 if server_calls==1 else 0 if server_calls==0 else None,
        response_delivery='sent',service_state='returned')
    return [episode],[terminal]


def test_direct_join_counts_actual_service_attempt_even_when_native_navigation_is_rejected():
    rows,terminal=sample('unknown','observed',422)
    terminal[0].update(error_code='unsafe_navigation_proposal',provider_error=False,plan_or_parse_error=True)
    result=audit(rows,terminal)
    assert result['status']=='DIRECT_JOIN_RECONCILED' and result['direct_join_complete']
    assert result['provider_model_calls']==1 and result['blocked_navigation_proposals']==1
    assert result['model_call_count_complete'] is True
    assert result['request_associations'][0]['condition']=='accepted_no_effect'
    assert result['request_associations'][0]['plan_or_parse_error'] is True


def test_pre_service_rejection_is_known_zero_and_service_exception_is_unknown():
    rows,terminal=sample('not_started','not_started',403)
    assert audit(rows,terminal)['provider_model_calls']==0
    rows,terminal=sample('unknown','unknown',500);terminal[0]['service_state']='raised'
    result=audit(rows,terminal)
    assert result['direct_join_complete'] and not result['model_call_count_complete']
    assert result['unknown_model_call_requests']==1 and result['provider_model_calls']==0
    assert result['status']=='DIRECT_JOIN_COMPLETE_MODEL_COUNTS_UNKNOWN'


@pytest.mark.parametrize('mutation,issue',[
    (lambda rows,logs:logs.clear(),'missing_terminal_records'),
    (lambda rows,logs:logs.append(deepcopy(logs[0])),'duplicate_server_request_ref'),
    (lambda rows,logs:logs[0].update(task_ref='b'*24),'request_task_ref_mismatch'),
    (lambda rows,logs:logs[0].update(request_ref='3'*24),'orphan_terminal_records'),
    (lambda rows,logs:logs[0].update(http_status=502),'http_status_mismatch'),
])
def test_missing_duplicate_cross_task_or_orphan_records_cannot_pass(mutation,issue):
    rows,terminal=sample();mutation(rows,terminal);result=audit(rows,terminal)
    assert not result['direct_join_complete'] and issue in result['issues']


def test_client_timeout_does_not_erase_server_call_or_make_failure_a_zero_cost_success():
    rows,terminal=sample('unknown','observed');rows[0]['request_traces'][0]['http_status']=None
    terminal[0]['response_delivery']='client_disconnected'
    result=audit(rows,terminal)
    assert result['direct_join_complete'] and result['provider_model_calls']==1
    assert result['model_call_count_complete'] is True


def test_missing_wait_is_distinct_from_unknown_call_count():
    rows,terminal=sample('unknown','observed');terminal[0]['model_wait_ms']=None
    result=audit(rows,terminal)
    assert result['model_call_count_complete'] and not result['model_wait_measurement_complete']
    assert result['status']=='DIRECT_JOIN_COMPLETE_TIMING_UNKNOWN'


def test_invalid_boolean_call_count_or_mixed_synthetic_schema_cannot_pass():
    rows,terminal=sample();terminal[0]['model_calls']=True
    result=audit(rows,terminal);assert not result['direct_join_complete']
    rows,terminal=sample();terminal[0]['schema_version']='local-meta-controller-synthetic-facade.v2'
    assert not audit(rows,terminal)['direct_join_complete']
    rows[0]['evidence_mode']='live_model_synthetic_closed_loop'
    with pytest.raises(ValueError,match='native Settings'):audit(rows,terminal)


def test_duplicate_client_identity_or_false_confirmed_zero_is_detected():
    rows,terminal=sample();rows[0]['request_traces']*=2;rows[0]['planner_requests']=2;rows[0]['actual_model_calls']=2
    assert 'duplicate_client_request_ref' in audit(rows,terminal)['issues']
    rows,terminal=sample('not_started','observed')
    assert 'confirmed_zero_mismatch' in audit(rows,terminal)['issues']
