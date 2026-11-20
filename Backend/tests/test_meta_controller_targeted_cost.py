"""Controlled analysis fixtures; no provider calls or actual cohort claims."""
from copy import deepcopy

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
    'extra_manifest_policy', 'old_episode_policy', 'missing_episode_policy', 'wrong_order', 'missing_schedule'])
def test_selected_condition_frozen_schedule_and_policy_provenance_fail_closed(change):
    rows, manifest, ready = selected()
    if change == 'normal_row': rows[0]['condition'] = 'normal'
    elif change == 'missing_manifest_policy': manifest.pop('coordinator_policies')
    elif change == 'old_manifest_policy': manifest['coordinator_policies']['B'] = 'mc_v1'
    elif change == 'extra_manifest_policy': manifest['coordinator_policies']['C'] = 'mc_v2'
    elif change == 'old_episode_policy': rows[0]['coordinator_policy'] = 'mc_v1'
    elif change == 'missing_episode_policy': rows[0].pop('coordinator_policy')
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


def reference_audit(rows, b_calls):
    return dict(direct_join_complete=True, model_call_count_complete=True,
        request_associations=[dict(episode_id=row['episode_id'], model_call_state='observed', model_calls=1)
            for row in rows for _ in range(b_calls if row['arm'] == 'B' else 1)])


def test_historical_comparison_requires_four_exposed_valid_start_matched_arms():
    rows, _, _ = selected()
    previous = deepcopy(rows)
    for row in previous: row['episode_id'] = 'previous/' + row['episode_id']
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
    current_audit, old_audit = reference_audit(rows, 2), reference_audit(rows, 4)
    old_audit['direct_join_complete'] = False
    assert reference_comparison(rows, rows, current_audit, old_audit) == {'status': 'REFERENCE_COUNTS_UNVERIFIED'}
    result = reference_comparison([], rows, current_audit, reference_audit(rows, 4))
    assert result['matched_tasks'] == 0
    assert result['previous_B_calls'] is None and result['current_B_calls'] is None


def test_unknown_reference_attempt_stays_unknown_even_when_summary_metadata_claims_complete():
    rows, _, _ = selected()
    current_audit, old_audit = reference_audit(rows, 2), reference_audit(rows, 4)
    next(item for item in old_audit['request_associations'] if item['episode_id'].endswith(':B'))['model_call_state'] = 'unknown'
    result = reference_comparison(rows, rows, current_audit, old_audit)
    assert result['matched_tasks'] == 6
    assert result['previous_B_calls'] is None and result['current_B_calls'] is None
    assert any(item['previous_B_calls'] is None for item in result['tasks'])
