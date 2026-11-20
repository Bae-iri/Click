"""Joining cannot turn absent/cancelled provider work into zero device latency."""
from hashlib import sha256

import pytest

from scripts.join_meta_controller_measurements import join_measurements
from app.services.meta_controller_experiment import evaluate
from tests.test_meta_controller_experiment import trial


CONTEXT = dict(device_scope='Note20 Android13 test scope', base_code_revision='base', patch_sha='patch',
               configured_model='gemini-3.8-flash')


def episode(arm='A', **changes):
    return trial(arm, runtime_task_id='runtime-' + arm, elapsed_ms=100, setup_status='ready',
        model_calls=None, model_wait_ms=None, observed_model_calls=0, **changes)


def call(arm='A', **changes):
    return dict(episode_ref=sha256(('runtime-' + arm).encode()).hexdigest()[:24],
        request_ref='request-' + arm, model='gemini-3.8-flash', model_calls=1,
        model_wait_ms=50, request_total_handler_ms=51, provider_error=False,
        plan_or_parse_error=False, reason='grounded_tool_proposal', **changes)


def test_actual_calls_and_model_are_joined_by_hashed_runtime_id():
    normalized, audit = join_measurements([episode()], [call()], CONTEXT)
    item = normalized[0]
    assert item['model_calls'] == 1 and item['model_wait_ms'] == 50
    assert item['server_provider_work_ms'] == 50 and item['model_version'] == 'gemini-3.8-flash'
    assert item['wrong_target_count'] is None and 'runtime_task_id' not in item
    assert audit['matched_provider_rows'] == 1


def test_late_provider_work_is_cost_evidence_but_not_episode_wait():
    calls = [call(), {**call(), 'request_ref': 'second', 'model_wait_ms': 90}]
    normalized, audit = join_measurements([episode()], calls, CONTEXT)
    assert normalized[0]['model_calls'] == 2
    assert normalized[0]['model_wait_ms'] is None
    assert normalized[0]['server_provider_work_ms'] == 140
    assert audit['episodes_with_provider_work_outside_window'] == 1


def test_incomplete_capture_missing_calls_stays_unknown():
    normalized, audit = join_measurements([episode()], [], CONTEXT)
    assert normalized[0]['model_calls'] is None and normalized[0]['model_version'] is None
    assert normalized[0]['model_wait_ms'] is None
    assert audit['episodes_with_unknown_model_calls'] == 1


def test_complete_capture_can_establish_zero_calls_without_claiming_model_execution():
    normalized, _ = join_measurements([episode()], [], {**CONTEXT, 'provider_capture_complete': True})
    assert normalized[0]['model_calls'] == 0 and normalized[0]['model_wait_ms'] == 0
    assert normalized[0]['model_version'] == 'not_called'


def test_setup_failure_keeps_failure_denominator_and_does_not_forge_start_state():
    item = episode()
    item.update(runtime_task_id=None, setup_status='failed', elapsed_ms=0,
                oracle_success=False, declared_done=False)
    item.pop('initial_state_hash')
    normalized, _ = join_measurements([item], [], CONTEXT)
    assert normalized[0]['model_calls'] == 0 and normalized[0]['initial_state_hash'] is None
    assert normalized[0]['oracle_success'] is None and normalized[0]['reported_oracle_success'] is False
    assert normalized[0]['oracle_measurement_status'] == 'NOT_EVALUATED_SETUP_FAILURE'
    assert normalized[0]['penalized_time_ms'] == normalized[0]['budget_ms']


def test_dispatch_delay_is_explicit_and_realized_injection_does_not_break_itt_pair():
    a = episode('A', condition='delay', fault_injected=False)
    b = episode('B', condition='delay', fault_injected=True)
    items, _ = join_measurements([a, b], [call('B')], {**CONTEXT, 'provider_capture_complete': True})
    assert all(t['condition'] == 'dispatch_delay' for t in items)
    assert items[0]['intervention_seed'] == items[1]['intervention_seed']
    comparison = evaluate(items, bootstrap=0)['evidence_groups'][0]['paired'][0]
    assert comparison['paired_episodes'] == 1
    assert comparison['realized_injection_mismatch_pairs'] == 1


def test_provider_errors_and_parse_errors_are_separate_and_unmatched_calls_audited():
    calls = [{**call(), 'provider_error': True, 'provider_http_status': 503},
             {**call('B'), 'plan_or_parse_error': True}]
    items, audit = join_measurements([episode()], calls, CONTEXT)
    assert items[0]['provider_error'] and items[0]['provider_error_count'] == 1
    assert items[0]['plan_or_parse_error_count'] == 0
    assert audit['unmatched_provider_rows'] == 1


def test_duplicate_episodes_and_provider_requests_are_rejected():
    with pytest.raises(ValueError, match='duplicate trial_id'):
        join_measurements([episode(), episode()], [], CONTEXT)
    with pytest.raises(ValueError, match='Duplicate provider'):
        join_measurements([episode()], [call(), call()], CONTEXT)


def test_multiple_actual_model_routes_do_not_get_fake_single_version():
    calls = [call(), {**call(), 'request_ref': 'other', 'model': 'gemini-other'}]
    items, _ = join_measurements([episode()], calls, CONTEXT)
    assert items[0]['model_version'] is None
    assert items[0]['models_observed'] == ['gemini-3.8-flash', 'gemini-other']


def test_cli_writes_normalized_episodes_and_join_audit(tmp_path):
    import json
    from scripts.join_meta_controller_measurements import main
    paths = {key: tmp_path / (key + '.json') for key in ('episodes', 'calls', 'context', 'output')}
    paths['episodes'].write_text(json.dumps(episode()) + '\n')
    paths['calls'].write_text(json.dumps(call()) + '\n')
    paths['context'].write_text(json.dumps(CONTEXT))
    assert main(['--episodes', str(paths['episodes']), '--provider-calls', str(paths['calls']),
        '--context', str(paths['context']), '--output', str(paths['output'])]) == 0
    assert json.loads(paths['output'].read_text())['model_calls'] == 1
    assert json.loads((tmp_path / 'join-audit.json').read_text())['matched_provider_rows'] == 1


def test_join_preserves_offer_endpoint_and_ambiguous_raw_oracle_without_claiming_success():
    raw = episode('B', runtime_verified_offer=True, false_verified_offer=None,
        oracle_final_match_count=2, oracle_success=True, declared_done=False)
    items, _ = join_measurements([raw], [call('B')], CONTEXT)
    assert items[0]['reported_oracle_success'] is True and items[0]['oracle_success'] is None
    report = evaluate(items, bootstrap=0)
    assert report['evidence_groups'][0]['by_arm']['B']['unknown_verified_offers'] == 1
    assert not report['evidence_groups'][0]['by_arm']['B']['verified_successes']
