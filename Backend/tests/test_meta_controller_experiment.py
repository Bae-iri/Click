"""Independent oracle, provenance and paired analysis guard the experiment claim."""
import pytest

from app.services.meta_controller_experiment import evaluate, protocol, validate_trial


def trial(arm='A', **changes):
    item = dict(trial_id='task:1:' + arm, pair_id='task:1', task_id='task', app_id='app',
        condition='normal', arm=arm, source='real_device_scripted_driver', elapsed_ms=100,
        budget_ms=1000, oracle_success=True, declared_done=True, independent_oracle=True,
        oracle_source='independent_ui_read', policy_version='candidate.v1',
        driver_mode='scripted_navigation', fault_injected=False, planner_arm='existing_app_task',
        initial_state_hash='state', intervention_seed=1, device_scope='device',
        model_version='not_called', base_code_revision='base')
    return {**item, **changes}


def test_plan_is_balanced_and_has_no_fabricated_results():
    manifest = protocol(include_ablation=True)
    assert len(manifest['trials']) == 324
    assert sum(t['phase'] == 'settings' for t in manifest['trials']) == 108
    assert sum(t['phase'] == 'multi_app' for t in manifest['trials']) == 144
    assert sum(t['phase'] == 'ablation' for t in manifest['trials']) == 72
    for phase in ('settings', 'multi_app', 'ablation'):
        first = [t['arm'] for t in manifest['trials'] if t['phase'] == phase and t['position'] == 1]
        assert first.count(first[0]) == len(first) // 2
    assert all(t['status'] == 'NOT_RUN' and 'oracle_success' not in t for t in manifest['trials'])
    assert all(t['app_id'] is None for t in manifest['trials'] if t['phase'] == 'multi_app')
    assert protocol(include_ablation=True) == manifest
    assert len({t['trial_id'] for t in manifest['trials']}) == 324


def test_ablation_has_same_pair_ids_and_seeds_as_preselected_ab_trials():
    trials = protocol(include_ablation=True)['trials']
    lookup = {(t['pair_id'], t['arm']): t for t in trials}
    for t in trials:
        if t['phase'] == 'ablation':
            assert t['intervention_seed'] == lookup[(t['pair_id'], 'B')]['intervention_seed']


def test_failure_and_unknown_oracle_stay_in_denominator_at_full_budget():
    records = [trial('A'), trial('B', oracle_success=False, elapsed_ms=10),
               trial('B', trial_id='task:2:B', pair_id='task:2', oracle_success=None, elapsed_ms=5)]
    report = evaluate(records, bootstrap=0)
    b = report['evidence_groups'][0]['by_arm']['B']
    assert b['trials'] == 2 and b['success_rate'] == 0
    assert b['mean_penalized_time_ms'] == 1000
    assert b['false_done'] == 1 and b['unverifiable_done'] == 1
    assert report['go_no_go']['experiment_continuation'] == 'STOP_AND_FIX_SAFETY'


def test_controller_self_report_cannot_be_independent_gold():
    with pytest.raises(ValueError, match='not an independent oracle'):
        validate_trial(trial(oracle_source='runtime_verdict'))
    item = validate_trial(trial(independent_oracle=False, oracle_source='runtime_verdict'))
    assert not item['verified_success'] and item['unverifiable_done']


def test_missing_model_measurement_is_null_not_zero():
    report = evaluate([trial()], bootstrap=0)
    a = report['evidence_groups'][0]['by_arm']['A']
    assert a['model_calls_total'] is None and a['model_calls_measured_trials'] == 0
    assert a['model_wait_ms_total'] is None


def test_paired_effect_uses_independent_task_clusters_and_seeded_bootstrap():
    records = [trial('A', oracle_success=False, declared_done=False), trial('B'),
        trial('A', trial_id='second:A', pair_id='second', task_id='second', elapsed_ms=200),
        trial('B', trial_id='second:B', pair_id='second', task_id='second', elapsed_ms=100)]
    comparison = evaluate(records, bootstrap=200)['evidence_groups'][0]['paired'][0]
    assert comparison['paired_episodes'] == 2 and comparison['task_clusters'] == 2
    assert comparison['difference_right_minus_left'] == dict(success_rate_pp=50, mean_penalized_time_ms=-500)
    assert comparison == evaluate(records, bootstrap=200)['evidence_groups'][0]['paired'][0]
    assert comparison['cluster_bootstrap_95ci']['success_rate_pp'] == [0, 100]


def test_different_start_or_missing_context_prevents_pairing():
    a, b = trial('A'), trial('B', initial_state_hash='other')
    comparison = evaluate([a, b], bootstrap=0)['evidence_groups'][0]['paired'][0]
    assert comparison['paired_episodes'] == 0
    assert comparison['rejected_pairs'][0]['mismatched_fields'] == ['initial_state_hash']
    a.pop('device_scope')
    comparison = evaluate([a, trial('B')], bootstrap=0)['evidence_groups'][0]['paired'][0]
    assert comparison['paired_episodes'] == 0
    assert 'device_scope' in comparison['rejected_pairs'][0]['missing_fields']


def test_evidence_types_and_pipelines_are_not_pooled():
    records = [trial('A'), trial('B', source='controlled_contract'),
               trial('B', trial_id='graph:B', planner_arm='graph')]
    report = evaluate(records, bootstrap=0)
    assert len(report['evidence_groups']) == 3
    assert all(g['paired'][0]['paired_episodes'] == 0 for g in report['evidence_groups'])
    assert report['go_no_go']['deployment'] == 'NO_GO'


@pytest.mark.parametrize('changes', [dict(elapsed_ms=float('nan')), dict(elapsed_ms=-1),
    dict(model_calls=True), dict(budget_ms=0), dict(recovery_successes=1),
    dict(started_at_ms=10, ended_at_ms=5), dict(started_at_ms=0, ended_at_ms=5),
    dict(model_wait_ms=101)])
def test_invalid_measurements_are_rejected(changes):
    with pytest.raises(ValueError):
        validate_trial(trial(**changes))


def test_duplicate_episode_or_arm_is_rejected():
    with pytest.raises(ValueError, match='Duplicate trial_id'):
        evaluate([trial(), trial()], bootstrap=0)
    second = trial(trial_id='other')
    with pytest.raises(ValueError, match='Duplicate A trial'):
        evaluate([trial(), second], bootstrap=0)


def test_over_budget_success_is_not_completion_within_budget():
    item = validate_trial(trial(elapsed_ms=1001))
    assert not item['verified_success'] and item['penalized_time_ms'] == 1000


def test_invalid_multi_app_bank_is_rejected():
    with pytest.raises(ValueError, match='12 distinct'):
        protocol(app_tasks=[])
    with pytest.raises(ValueError, match='four apps'):
        protocol(app_tasks=[dict(task_id=str(i), app_id='one') for i in range(12)])


def test_empty_report_never_claims_experiment_was_run():
    report = evaluate([], bootstrap=0)
    assert report['episode_count'] == 0
    assert report['go_no_go']['experiment_continuation'] == 'NEEDS_EPISODES'


def pilot_trials(*, improvement=True):
    return [trial(arm, trial_id=f'{task}:{i}:{arm}', pair_id=f'{task}:{i}', task_id=task,
                  elapsed_ms=50 if arm == 'B' and improvement else 100, wrong_target_count=0,
                  runtime_verified_offer=False)
            for task in ('one', 'two') for i in range(3) for arm in ('A', 'B')]


def test_controlled_or_unmatched_data_cannot_receive_continuation_go():
    report = evaluate([trial()], bootstrap=0)
    assert report['go_no_go']['experiment_continuation'] == 'NEEDS_MATCHED_DEVICE_DATA'
    records = [{**t, 'source': 'controlled_contract'} for t in pilot_trials()]
    assert evaluate(records)['go_no_go']['experiment_continuation'] == 'NEEDS_MATCHED_DEVICE_DATA'


def test_measured_paired_benefit_can_only_advance_experiment_not_deployment():
    decision = evaluate(pilot_trials(), bootstrap=200)['go_no_go']
    assert decision['experiment_continuation'] == 'GO_EXPANDED_DEVICE_VALIDATION'
    assert decision['deployment'] == 'NO_GO'
    assert decision['scope'] == 'opt_in_native_policy_pilot'


def test_no_observed_benefit_does_not_pass_pilot_gate():
    decision = evaluate(pilot_trials(improvement=False), bootstrap=200)['go_no_go']
    assert decision['experiment_continuation'] == 'NO_GO_NO_OBSERVED_BENEFIT'


def test_unmeasured_wrong_targets_and_no_bootstrap_need_more_evidence():
    records = pilot_trials()
    records[0]['wrong_target_count'] = None
    assert evaluate(records)['go_no_go']['experiment_continuation'] == 'NEEDS_MATCHED_DEVICE_DATA'
    assert evaluate(pilot_trials(), bootstrap=0)['go_no_go']['experiment_continuation'] == 'NEEDS_MORE_EFFICACY_DATA'


def test_versions_of_same_arm_are_not_silently_pooled():
    records = pilot_trials()
    records[-1]['policy_version'] = 'different'
    with pytest.raises(ValueError, match='Different versions'):
        evaluate(records)


@pytest.mark.parametrize('field', ['experiment_patch_sha', 'patch_sha'])
def test_patch_metadata_requires_same_patch_and_both_present(field):
    a, b = trial('A', **{field: 'one'}), trial('B', **{field: 'two'})
    comparison = evaluate([a, b], bootstrap=0)['evidence_groups'][0]['paired'][0]
    assert not comparison['paired_episodes'] and field in comparison['rejected_pairs'][0]['mismatched_fields']
    b.pop(field)
    comparison = evaluate([a, b], bootstrap=0)['evidence_groups'][0]['paired'][0]
    assert field in comparison['rejected_pairs'][0]['missing_fields']


def test_false_verified_offer_is_separate_from_unconfirmed_done_and_blocks_pilot():
    records = [trial('A', declared_done=False, runtime_verified_offer=True,
                     false_verified_offer=False, oracle_final_match_count=1),
               trial('B', declared_done=False, runtime_verified_offer=True,
                     false_verified_offer=True, oracle_success=False, oracle_final_match_count=0)]
    report = evaluate(records, bootstrap=0)
    b = report['evidence_groups'][0]['by_arm']['B']
    assert b['runtime_verified_offers'] == 1 and b['false_verified_offers'] == 1
    assert b['false_done'] == 0
    assert report['go_no_go']['experiment_continuation'] == 'NO_GO_FIX_COMPLETION_GROUNDING'


def test_ambiguous_oracle_cannot_establish_success_or_a_false_offer():
    item = trial('B', declared_done=False, runtime_verified_offer=True,
                 false_verified_offer=None, oracle_success=True, oracle_final_match_count=2)
    report = evaluate([item], bootstrap=0)
    b = report['evidence_groups'][0]['by_arm']['B']
    assert not b['verified_successes'] and b['ambiguous_oracle_trials'] == 1
    assert b['unknown_oracle_trials'] == 1 and b['unknown_verified_offers'] == 1
    assert not b['false_verified_offers']


def test_unmeasured_offers_stay_unknown_and_invalid_flags_are_rejected():
    old = validate_trial(trial())
    assert old['runtime_verified_offer'] is None and old['false_verified_offer'] is None
    with pytest.raises(ValueError, match='observed runtime verified offer'):
        validate_trial(trial(false_verified_offer=True))
    with pytest.raises(ValueError, match='boolean or null'):
        validate_trial(trial(runtime_verified_offer='true'))


def test_current_arm_false_offer_is_reported_without_misattributing_it_to_candidate():
    records = [trial('A', declared_done=False, runtime_verified_offer=True,
                     false_verified_offer=True, oracle_success=False, oracle_final_match_count=0),
               trial('B', declared_done=False, runtime_verified_offer=False)]
    report = evaluate(records, bootstrap=0)
    assert report['evidence_groups'][0]['by_arm']['A']['false_verified_offers'] == 1
    assert report['evidence_groups'][0]['by_arm']['B']['false_verified_offers'] == 0
    assert report['go_no_go']['experiment_continuation'] != 'NO_GO_FIX_COMPLETION_GROUNDING'


def test_missing_offer_safety_endpoint_cannot_receive_pilot_go():
    records = pilot_trials()
    records[0].pop('runtime_verified_offer')
    assert evaluate(records, bootstrap=200)['go_no_go']['experiment_continuation'] == 'NEEDS_MATCHED_DEVICE_DATA'


@pytest.mark.parametrize('success', [None, False])
def test_unavailable_oracle_is_not_absence_or_false_offer(success):
    raw = trial('B', declared_done=False, oracle_success=success, oracle_observable=False,
        oracle_final_match_count=None, runtime_verified_offer=True, false_verified_offer=True)
    item = validate_trial(raw)
    assert item['oracle_success'] is None and item['false_verified_offer'] is None
    assert item['unknown_verified_offer'] and item['false_verified_offer_evidence_rejected']
    report = evaluate([item], bootstrap=0)
    b = report['evidence_groups'][0]['by_arm']['B']
    assert b['final_oracle_unobservable_trials'] == 1 and not b['false_verified_offers']
    assert report['go_no_go']['experiment_continuation'] != 'NO_GO_FIX_COMPLETION_GROUNDING'


def test_stable_positive_goal_survives_unavailable_final_read_but_offer_remains_unknown():
    item = trial('B', declared_done=False, oracle_success=True, goal_observed_ms=50,
        oracle_observable=False, oracle_final_match_count=None,
        runtime_verified_offer=True, false_verified_offer=None)
    report = evaluate([item], bootstrap=0)
    b = report['evidence_groups'][0]['by_arm']['B']
    assert b['verified_successes'] == 1 and b['unknown_verified_offers'] == 1
    assert b['positive_goal_evidence_with_final_unobservable'] == 1


def test_explicit_observed_zero_can_adjudicate_false_offer_even_if_whole_episode_goal_unknown():
    item = validate_trial(trial('B', declared_done=False, oracle_success=None,
        oracle_observable=True, oracle_final_match_count=0,
        runtime_verified_offer=True, false_verified_offer=True))
    assert item['false_verified_offer'] is True
    assert evaluate([item], bootstrap=0)['go_no_go']['experiment_continuation'] == 'NO_GO_FIX_COMPLETION_GROUNDING'


def test_legacy_independent_false_oracle_can_adjudicate_absence_without_final_read_fields():
    item = validate_trial(trial('B', declared_done=False, oracle_success=False,
        runtime_verified_offer=True, false_verified_offer=True))
    assert item['false_verified_offer'] is True
    assert validate_trial(item)['false_verified_offer'] is True


def test_unknown_oracle_without_explicit_zero_cannot_adjudicate_false_offer():
    item = validate_trial(trial('B', declared_done=False, oracle_success=None,
        runtime_verified_offer=True, false_verified_offer=True))
    assert item['false_verified_offer'] is None


def test_cli_exports_unrun_manifest_and_actual_episode_report(tmp_path):
    import json
    from scripts.run_meta_controller_experiment import main
    assert main(['--plan', '--include-ablation', '--output-dir', str(tmp_path / 'plan')]) == 0
    manifest = json.loads((tmp_path / 'plan/protocol.json').read_text())
    assert len(manifest['trials']) == 324
    source = tmp_path / 'measured.jsonl'
    source.write_text('\n'.join(json.dumps(t) for t in (trial('A'), trial('B'))))
    assert main(['--input', str(source), '--output-dir', str(tmp_path / 'measured'), '--bootstrap', '0']) == 0
    assert 'scripted_navigation' in (tmp_path / 'measured/comparison.md').read_text()
    assert json.loads((tmp_path / 'measured/comparison.json').read_text())['episode_count'] == 2
