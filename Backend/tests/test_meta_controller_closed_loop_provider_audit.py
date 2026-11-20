from scripts.audit_meta_controller_closed_loop_provider import audit


def episode(arm='A', **updates):
    row = dict(evidence_mode='live_model_synthetic_closed_loop', pair_id='p', arm=arm,
        planner_requests=1, actual_model_calls=1, actual_model_wait_ms=12, gateway_errors=0)
    row.update(updates)
    return row


def record(ref='e1', req='r1', **updates):
    row = dict(evidence_mode='live_model_synthetic_closed_loop', episode_ref=ref, request_ref=req,
        model_calls=1, model_wait_ms=12, model='gemini-3.8-flash', provider_error=False, plan_or_parse_error=False)
    row.update(updates)
    return row


def test_ordinal_reconciliation_keeps_explicit_direct_join_limitation():
    result = audit([episode(), episode('B')], [record(), record('e2', 'r2')])
    assert result['status'] == 'ORDINAL_RECONCILED_WITH_EXPLICIT_LIMITATION'
    assert result['direct_request_id_join_available'] is False
    assert result['efficiency_inference_permitted'] is False
    assert result['provider_model_calls'] == 2


def test_missing_record_is_not_silently_shifted_to_another_episode():
    result = audit([episode(), episode('B')], [record()])
    assert result['status'] == 'INDETERMINATE_OR_MISMATCHED'
    assert 'provider_episode_group_count_mismatch' in result['issues']


def test_gateway_error_prevents_claiming_an_unambiguous_ordinal_join():
    result = audit([episode(gateway_errors=1)], [record()])
    assert result['status'] == 'INDETERMINATE_OR_MISMATCHED'


def test_provider_failure_is_retained_even_when_request_counts_reconcile():
    result = audit([episode()], [record(provider_error=True)])
    assert result['status'] == 'ORDINAL_RECONCILED_WITH_EXPLICIT_LIMITATION'
    assert result['provider_errors'] == 1


def test_repeated_noncontiguous_episode_or_duplicate_request_ref_is_rejected():
    result = audit([episode(), episode('B'), episode()], [record(), record('e2', 'r2'), record('e1', 'r1')])
    assert result['status'] == 'INDETERMINATE_OR_MISMATCHED'
    assert 'noncontiguous_provider_episode' in result['issues']
    assert 'missing_or_duplicate_provider_request_ref' in result['issues']


def direct_episode(request='a' * 24, task='d' * 24, **updates):
    trace = dict(request_ref=request, task_ref=task, server_ref='f' * 24,
        http_status=200, model_call_state='observed', model_calls=1, model_wait_ms=12)
    row = episode(task_ref=task, request_traces=[trace], schema_version='click.synthetic-closed-loop.v2')
    row.update(updates)
    return row


def terminal(request='a' * 24, task='d' * 24, **updates):
    row = record(ref=task, req=request, schema_version='local-meta-controller-synthetic-facade.v2',
        event='request_terminal', task_ref=task, server_ref='f' * 24, http_status=200,
        model_call_state='observed', service_state='returned', response_delivery='sent', outcome='service_response')
    row.update(updates)
    return row


def test_new_records_join_by_request_and_task_identity_instead_of_episode_order():
    a, b = direct_episode(), direct_episode('b' * 24, 'e' * 24)
    b['arm'] = 'B';b['request_traces'][0]['server_ref'] = '1' * 24
    result = audit([a, b], [terminal('b' * 24, 'e' * 24, server_ref='1' * 24), terminal()])
    assert result['status'] == 'DIRECT_JOIN_RECONCILED'
    assert result['direct_request_id_join_available'] and result['direct_join_complete']
    assert result['matched_request_records'] == 2 and result['model_call_count_complete']
    assert result['provider_model_calls'] == 2 and not result['issues']


def test_pre_provider_rejection_is_a_joinable_terminal_outcome_not_a_missing_call():
    row = direct_episode(actual_model_calls=0, gateway_errors=1)
    row['request_traces'][0].update(http_status=403, model_call_state='not_started', model_calls=0, model_wait_ms=0)
    result = audit([row], [terminal(http_status=403, model_call_state='not_started', model_calls=0,
        model_wait_ms=0, service_state='not_started', outcome='rejected', error_code='outside_frozen_public_fixture')])
    assert result['status'] == 'DIRECT_JOIN_RECONCILED' and result['provider_model_calls'] == 0
    assert result['rejected_requests'] == 1 and result['model_call_count_complete']
    assert result['efficiency_inference_permitted'] is False


def test_service_exception_keeps_unknown_provider_attempt_instead_of_zero():
    row = direct_episode(actual_model_calls=0, gateway_errors=1)
    row['request_traces'][0].update(http_status=500, model_call_state='unknown', model_calls=None, model_wait_ms=None)
    result = audit([row], [terminal(http_status=500, model_call_state='unknown', model_calls=None,
        model_wait_ms=None, service_state='raised', outcome='service_exception')])
    assert result['direct_join_complete'] and result['status'] == 'DIRECT_JOIN_COMPLETE_MODEL_COUNTS_UNKNOWN'
    assert result['unknown_model_call_requests'] == 1 and not result['model_call_count_complete']
    assert result['service_exceptions'] == 1


def test_missing_duplicate_orphan_and_cross_task_records_are_not_silently_joined():
    missing = audit([direct_episode()], [])
    assert 'missing_terminal_records' in missing['issues'] and missing['unknown_model_call_requests'] == 1
    duplicate = audit([direct_episode()], [terminal(), terminal(server_ref='2' * 24)])
    assert 'duplicate_provider_request_ref' in duplicate['issues']
    orphan = audit([direct_episode()], [terminal('b' * 24)])
    assert 'orphan_terminal_records' in orphan['issues']
    cross_task = audit([direct_episode()], [terminal(task='e' * 24)])
    assert 'request_task_ref_mismatch' in cross_task['issues']


def test_delivery_failure_still_accounts_for_provider_call_even_if_client_never_saw_it():
    row = direct_episode(actual_model_calls=0, gateway_errors=1)
    row['request_traces'][0].update(http_status=None, model_call_state='unknown', model_calls=None, model_wait_ms=None)
    result = audit([row], [terminal(response_delivery='client_disconnected')])
    assert result['direct_join_complete'] and result['provider_model_calls'] == 1
    assert result['model_call_count_complete'] and result['delivery_failures'] == 1


def test_tampered_client_observed_count_wait_or_status_is_reported():
    row = direct_episode()
    row['request_traces'][0].update(http_status=500, model_wait_ms=99)
    result = audit([row], [terminal()])
    assert 'http_status_mismatch' in result['issues'] and 'model_wait_mismatch' in result['issues']


def test_duplicate_client_request_refs_are_rejected_even_if_aggregate_counts_match():
    result = audit([direct_episode(), direct_episode()], [terminal()])
    assert 'duplicate_client_request_ref' in result['issues']


def test_known_provider_count_with_missing_timing_has_distinct_status():
    row = direct_episode(actual_model_calls=0)
    row['request_traces'][0].update(model_call_state='unknown', model_calls=None, model_wait_ms=None)
    result = audit([row], [terminal(model_wait_ms=None)])
    assert result['status'] == 'DIRECT_JOIN_COMPLETE_TIMING_UNKNOWN'
    assert result['direct_join_complete'] and result['model_call_count_complete']
    assert result['provider_model_calls'] == 1 and not result['model_wait_measurement_complete']
