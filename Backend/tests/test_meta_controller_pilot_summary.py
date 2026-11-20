"""The final pilot report requires its complete frozen grid and exposes nonexposure."""
from copy import deepcopy

import pytest

from scripts.summarize_meta_controller_pilot import summarize, validate_grid, render_summary
from tests.test_meta_controller_experiment import trial


def complete_pilot(version='native-navigation.v3'):
    tasks = [dict(task_id=f't{i}', app_id=f'app.{i // 3}') for i in range(12)]
    manifest = dict(schema_version=version, run_id='final', tasks=tasks,
        conditions=['normal', 'noeffect', 'delay'], repeat=2, arms=['A', 'B', 'C'], budget_ms=15000,
        planner_arm='live_app_task', task_selection_frozen_before_run=True, scheduled_arm_orders=[])
    records = []
    for r in range(2):
        for task in tasks:
            for native, condition in [('normal', 'normal'), ('noeffect', 'no_effect'), ('delay', 'dispatch_delay')]:
                pair = f'final:{task["task_id"]}:{native}:{r}'
                manifest['scheduled_arm_orders'].append(dict(pair_id=pair, order=['A', 'B', 'C']))
                for arm in manifest['arms']:
                    records.append(trial(arm, schema_version=version, trial_id=pair + ':' + arm,
                        pair_id=pair, task_id=task['task_id'], app_id=task['app_id'], repeat=r,
                        assigned_native_condition=native, condition=condition, budget_ms=15000,
                        planner_arm='live_app_task', driver_mode='scripted_approvals',
                        configured_model='gemini-3.8-flash', experiment_patch_sha='patch',
                        policy_version=version + ':' + arm + ':patch',
                        runtime_verified_offer=False, false_verified_offer=False, declared_done=False,
                        setup_status='ready', first_proposal_ms=10, goal_observed_ms=20,
                        runtime_verified_offer_ms=None, runtime_done_ms=None,
                        native_execution_attempts=1, native_acceptances=1, native_rejections=0,
                        fault_injected=condition != 'normal', model_calls=1,
                        meta_diagnostic=dict(recovery_count=0, blocked_count=0, event_count=0)))
    return manifest, records


def test_complete_grid_reports_all_assigned_conditions_and_keeps_clock_events_distinct():
    manifest, records = complete_pilot()
    summary, comparison = summarize(manifest, records, bootstrap=0)
    assert summary['completeness']['actual_episodes'] == 216
    assert all(m['trials'] == 72 for m in summary['overall'].values())
    assert all(c['by_arm']['A']['trials'] == 24 for c in summary['assigned_conditions'])
    assert all(c['by_arm']['A']['trials'] == 18 for c in summary['applications'])
    a = summary['overall']['A']
    assert a['goal_observed_time']['p50_s'] == .02
    assert a['first_action_proposal_time']['p50_s'] == .01
    assert a['runtime_verified_offer_time']['p50_s'] is None
    assert a['runtime_done_time']['eligible_episodes'] == 0
    assert len(summary['strict_matched_comparisons']) == 2
    text = render_summary(summary)
    assert '216/216' in text and 'dispatch_delay' in text and 'upstream failures' in text


@pytest.mark.parametrize('change,match', [
    (lambda m, r: r.pop(), 'Incomplete final pilot'),
    (lambda m, r: r.append(deepcopy(r[0])), 'Duplicate trial'),
    (lambda m, r: r[0].update(schema_version='native-navigation.v2'), 'Mixed instrumentation'),
    (lambda m, r: r[0].update(experiment_patch_sha='different'), 'Policy version'),
    (lambda m, r: r[0].update(trial_id='dev-smoke:A'), 'Unexpected trial'),
    (lambda m, r: m['scheduled_arm_orders'].pop(), 'arm-order schedule')])
def test_missing_duplicate_and_mixed_development_rows_cannot_be_final_report(change, match):
    manifest, records = complete_pilot()
    change(manifest, records)
    with pytest.raises(ValueError, match=match):
        validate_grid(manifest, records)


def test_false_offer_before_action_is_reported_without_recovery_effect_claim():
    manifest, records = complete_pilot()
    b = next(t for t in records if t['arm'] == 'B' and t['assigned_native_condition'] == 'delay')
    b.update(oracle_success=False, runtime_verified_offer=True, false_verified_offer=True,
        runtime_verified_offer_ms=80, first_proposal_ms=None, native_execution_attempts=0,
        native_acceptances=0, fault_injected=False, goal_observed_ms=None)
    summary, _ = summarize(manifest, records, bootstrap=0)
    assert summary['overall']['B']['opportunities']['false_verified_offer_before_any_native_dispatch'] == 1
    assert summary['overall']['B']['opportunities']['episodes_with_controller_recovery'] == 0
    assert summary['go_no_go']['experiment_continuation'] == 'NO_GO_FIX_COMPLETION_GROUNDING'
    assigned = next(c for c in summary['assigned_conditions'] if c['condition'] == 'dispatch_delay')
    assert assigned['by_arm']['B']['trials'] == 24
    assert assigned['by_arm']['B']['verified_successes'] == 23


def test_setup_failure_stays_in_primary_denominator_and_only_auxiliary_excludes_it():
    manifest, records = complete_pilot()
    records[0].update(setup_status='failed', oracle_success=False, elapsed_ms=0,
        initial_state_hash=None, first_proposal_ms=None, goal_observed_ms=None,
        native_execution_attempts=0, native_acceptances=0, fault_injected=False)
    summary, _ = summarize(manifest, records, bootstrap=0)
    assert summary['overall']['A']['trials'] == 72
    assert summary['overall']['A']['verified_successes'] == 71
    assert summary['ready_only_auxiliary']['A']['trials'] == 71
    assert summary['strict_matched_comparisons'][0]['paired_episodes'] == 71


def test_cli_requires_complete_data_and_writes_summary_provenance(tmp_path):
    import json
    from scripts.summarize_meta_controller_pilot import main
    manifest, records = complete_pilot()
    manifest_path, input_path, output = tmp_path / 'manifest.json', tmp_path / 'normalized.jsonl', tmp_path / 'results'
    manifest_path.write_text(json.dumps(manifest))
    input_path.write_text(''.join(json.dumps(r) + '\n' for r in records))
    assert main(['--manifest', str(manifest_path), '--input', str(input_path),
        '--output-dir', str(output), '--bootstrap', '0']) == 0
    summary = json.loads((output / 'pilot-summary.json').read_text())
    assert len(summary['input_sha256']['normalized_episodes']) == 64
    assert (output / 'comparison.json').is_file() and (output / 'pilot-summary.md').is_file()
    input_path.write_text(json.dumps(records[0]) + '\n')
    with pytest.raises(SystemExit):
        main(['--manifest', str(manifest_path), '--input', str(input_path),
              '--output-dir', str(tmp_path / 'incomplete'), '--bootstrap', '0'])
    assert not (tmp_path / 'incomplete').exists()


def test_v4_is_supported_as_one_exclusive_complete_cohort():
    manifest, records = complete_pilot('native-navigation.v4')
    summary, _ = summarize(manifest, records, bootstrap=0)
    assert summary['instrumentation_version'] == 'native-navigation.v4'
    assert 'native-navigation.v4' in render_summary(summary)
    records[0]['schema_version'] = 'native-navigation.v3'
    with pytest.raises(ValueError, match='Mixed instrumentation'):
        summarize(manifest, records, bootstrap=0)
