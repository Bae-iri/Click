from copy import deepcopy

import pytest

from scripts.summarize_meta_controller_closed_loop import EXPECTED_PAIRS, render, summarize


def row(arm, **kwargs):
    return dict(schema_version='click.synthetic-closed-loop.v1',
        evidence_mode='controlled_full_controller_conformance', pair_id='p', arm=arm,
        task='nav_display', condition='normal', repetition=0, initial_state_key='same', setup_valid=True,
        independent_goal_reached=True, runtime_verified_offer=True, false_verified_offers=0,
        guard_violations=0, modeled_ui_ms_to_verified_offer=650, actual_model=None,
        **kwargs)


def test_constructed_mechanism_success_never_authorizes_default_enablement():
    result = summarize([row('A'), row('B')], {'passed': True})
    assert result['valid_matched_pairs'] == 1
    assert result['paired_goal_outcomes']['both'] == 1
    assert result['next_validation_stage'] == 'NO_GO_FIX_OR_CHARACTERIZE_FAILURES'
    assert result['frozen_matrix_complete'] is False
    assert result['default_enablement_verdict'] == 'NO_GO_FOR_DEFAULT_ENABLEMENT'
    assert result['real_apps_tested'] is False
    assert 'synthetic UI' in render(result)


def full_matrix():
    rows = []
    for task, condition, rep in sorted(EXPECTED_PAIRS):
        for arm in ('A', 'B'):
            item = row(arm)
            item.update(pair_id=f'{task}/{condition}/{rep}', task=task, condition=condition, repetition=rep,
                primary_action_seen=True, fault_injected=condition != 'normal')
            rows.append(item)
    return rows


def test_complete_healthy_matrix_can_only_proceed_to_further_validation():
    result = summarize(full_matrix(), {'passed': True})
    assert result['frozen_matrix_complete'] and result['healthy_data']
    assert result['next_validation_stage'] == 'GO_TO_BROADER_REAL_APP_VALIDATION'
    assert result['default_enablement_verdict'] == 'NO_GO_FOR_DEFAULT_ENABLEMENT'


def test_zero_realized_fault_exposure_is_not_tested_and_blocks_recovery_gate():
    rows = full_matrix()
    for item in rows:
        item['fault_injected'] = False
    result = summarize(rows, {'passed': True})
    assert result['next_validation_stage'] == 'NO_GO_FIX_OR_CHARACTERIZE_FAILURES'
    assert all(c['recovery_comparison_status'] == 'NOT_TESTED' for c in result['fault_exposure_by_assigned_condition'].values())


@pytest.mark.parametrize('field', ['gateway_errors', 'listener_errors', 'provider_or_plan_errors'])
def test_errors_outside_normal_controls_block_efficiency_and_next_stage(field):
    rows = full_matrix()
    next(r for r in rows if r['condition'] == 'delayed_transition')[field] = 1
    result = summarize(rows, {'passed': True})
    assert result['healthy_data'] is False
    assert result['next_validation_stage'] == 'NO_GO_FIX_OR_CHARACTERIZE_FAILURES'
    assert result['efficiency_comparison_status'] == 'NOT_INTERPRETABLE_INCOMPLETE_OR_UNHEALTHY_DATA'


def test_bad_setup_is_visible_and_cannot_manufacture_arm_success():
    a, b = row('A'), row('B')
    b['initial_state_key'] = 'different'
    result = summarize([a, b], {'passed': True})
    assert result['valid_matched_pairs'] == 0 and len(result['invalid_pairs']) == 1
    assert result['next_validation_stage'] == 'NO_GO_FIX_OR_CHARACTERIZE_FAILURES'


def test_failure_and_actual_fault_exposure_are_retained():
    a, b = row('A'), row('B')
    a.update(independent_goal_reached=False, runtime_verified_offer=False, actual_model_calls=1,
        gateway_errors=1, primary_action_seen=True, fault_injected=True)
    b.update(actual_model_calls=2, primary_action_seen=False, fault_injected=False)
    result = summarize([a, b], {'passed': True})
    assert result['paired_goal_outcomes']['B_only'] == 1
    assert result['arms']['A']['gateway_errors'] == 1
    assert result['arms']['A']['fault_injected'] == 1 and result['arms']['B']['fault_injected'] == 0
    assert result['arms']['B']['actual_model_calls'] == 2


def test_unsafe_result_blocks_next_stage():
    a, b = row('A'), row('B')
    b['false_verified_offers'] = 1
    result = summarize([a, b], {'passed': True})
    assert result['next_validation_stage'] == 'NO_GO_FIX_OR_CHARACTERIZE_FAILURES'


def test_duplicates_and_mixed_evidence_modes_are_rejected():
    with pytest.raises(ValueError, match='duplicate'):
        summarize([row('A'), deepcopy(row('A'))])
    a, b = row('A'), row('B')
    b['evidence_mode'] = 'live_model_synthetic_closed_loop'
    with pytest.raises(ValueError, match='evidence mode'):
        summarize([a, b])
