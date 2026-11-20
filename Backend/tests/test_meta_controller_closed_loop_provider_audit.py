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
