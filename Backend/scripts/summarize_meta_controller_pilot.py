"""Summarize one complete frozen v3 or v4 216-episode pilot cohort.

Development runs, incomplete exports and mixed policy/patch scopes fail closed.
The output describes this scripted navigation pilot, not the proposed 252-trial
product experiment, a full UIAnchor replication or older-adult usability.
"""
import argparse
from collections import Counter
from hashlib import sha256
import json
from math import isfinite
from pathlib import Path
from statistics import mean, median
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.services.meta_controller_experiment import evaluate, metrics, percentile, validate_trial


PILOT_VERSIONS = frozenset(('native-navigation.v3', 'native-navigation.v4'))
ARMS = ('A', 'B', 'C')
CONDITIONS = {'normal': 'normal', 'noeffect': 'no_effect', 'delay': 'dispatch_delay'}


def _measured_number(value, name, *, integer=False):
    if value is None:
        return None
    if type(value) not in (int, float) or not isfinite(value) or value < 0 or integer and value != int(value):
        raise ValueError(f'Invalid measured {name}')
    return value


def validate_grid(manifest, records):
    pilot_version = manifest.get('schema_version')
    if pilot_version not in PILOT_VERSIONS:
        raise ValueError('Only a complete native-navigation.v3 or v4 manifest may be summarized')
    tasks, conditions, arms = manifest.get('tasks'), manifest.get('conditions'), manifest.get('arms')
    if not isinstance(tasks, list) or len(tasks) != 12 or manifest.get('repeat') != 2:
        raise ValueError('Final pilot requires 12 tasks and two repetitions')
    if not isinstance(conditions, list) or len(conditions) != 3 or set(conditions) != set(CONDITIONS):
        raise ValueError('Final pilot requires normal, noeffect and delay assignments')
    if not isinstance(arms, list) or len(arms) != 3 or set(arms) != set(ARMS):
        raise ValueError('Final pilot requires A, B and C')
    run_id = manifest.get('run_id')
    if not isinstance(run_id, str) or not run_id or manifest.get('task_selection_frozen_before_run') is not True:
        raise ValueError('A frozen run_id and task selection are required')
    if manifest.get('budget_ms') != 15000 or manifest.get('planner_arm') != 'live_app_task':
        raise ValueError('Final pilot requires the frozen 15-second live AppTask scope')
    catalogue = {}
    for task in tasks:
        if not isinstance(task, dict) or not task.get('task_id') or not task.get('app_id') or task['task_id'] in catalogue:
            raise ValueError('Task catalogue has duplicate or missing identities')
        catalogue[task['task_id']] = task['app_id']
    if len(set(catalogue.values())) != 4:
        raise ValueError('Final pilot requires the frozen four-app scope')
    expected = {}
    pair_ids = set()
    for repeat in range(2):
        for task, app in catalogue.items():
            for native_condition, condition in CONDITIONS.items():
                pair = f'{run_id}:{task}:{native_condition}:{repeat}'
                pair_ids.add(pair)
                for arm in ARMS:
                    expected[f'{pair}:{arm}'] = dict(pair_id=pair, task_id=task, app_id=app,
                        repeat=repeat, arm=arm, condition=condition, assigned_native_condition=native_condition)
    orders = manifest.get('scheduled_arm_orders')
    if not isinstance(orders, list) or len(orders) != len(pair_ids):
        raise ValueError('Manifest is missing its frozen arm-order schedule')
    order_ids = set()
    for order in orders:
        if not isinstance(order, dict) or order.get('pair_id') not in pair_ids or order['pair_id'] in order_ids:
            raise ValueError('Invalid or duplicate pair in frozen arm-order schedule')
        values = order.get('order')
        if not isinstance(values, list) or len(values) != 3 or set(values) != set(ARMS):
            raise ValueError('Each scheduled block must contain A/B/C once')
        order_ids.add(order['pair_id'])
    seen, trials = set(), []
    scope = {}
    versions = {}
    for raw in records:
        item = validate_trial(raw)
        identifier = item['trial_id']
        if identifier not in expected:
            raise ValueError('Unexpected trial; development or another run must not enter the final pilot')
        if identifier in seen:
            raise ValueError('Duplicate trial in final pilot')
        seen.add(identifier)
        if item.get('schema_version') != pilot_version:
            raise ValueError('Mixed instrumentation versions in final pilot')
        for key, value in expected[identifier].items():
            if item.get(key) != value:
                raise ValueError(f'Episode disagrees with frozen manifest: {key}')
        if item['source'] != 'real_device_scripted_driver' or item['driver_mode'] != 'scripted_approvals':
            raise ValueError('Mixed evidence source or driver scope in final pilot')
        if item['budget_ms'] != 15000 or item['planner_arm'] != manifest['planner_arm']:
            raise ValueError('Mixed planner or budget in final pilot')
        patch = item.get('experiment_patch_sha')
        if not isinstance(patch, str) or not patch:
            raise ValueError('Final pilot requires normalized patch provenance')
        wanted_version = f'{pilot_version}:{item["arm"]}:{patch}'
        if item['policy_version'] != wanted_version:
            raise ValueError('Policy version does not match instrumentation/arm/patch')
        versions.setdefault(item['arm'], item['policy_version'])
        if versions[item['arm']] != item['policy_version']:
            raise ValueError('Mixed policy versions in final pilot')
        for key in ('experiment_patch_sha', 'base_code_revision', 'device_scope', 'configured_model'):
            value = item.get(key)
            if not isinstance(value, str) or not value:
                raise ValueError(f'Final pilot requires {key}')
            scope.setdefault(key, value)
            if scope[key] != value:
                raise ValueError(f'Mixed final pilot context: {key}')
        trials.append(item)
    missing = sorted(set(expected) - seen)
    if missing:
        raise ValueError(f'Incomplete final pilot: {len(missing)} of 216 trials missing')
    return sorted(trials, key=lambda t: t['trial_id']), scope


def _distribution(items, field, *, predicate=None):
    population = [t for t in items if predicate is None or predicate(t)]
    values = [_measured_number(t.get(field), field) for t in population]
    measured = [v / 1000 for v in values if v is not None]
    return dict(eligible_episodes=len(population), measured_episodes=len(measured),
        unmeasured_episodes=len(values) - len(measured), mean_s=mean(measured) if measured else None,
        p50_s=median(measured) if measured else None, p95_s=percentile(measured, .95))


def _sum_measured(items, field, *, nested=False, integer=True):
    values = [_measured_number((t.get('meta_diagnostic') or {}).get(field) if nested else t.get(field),
                              field, integer=integer) for t in items]
    measured = [v for v in values if v is not None]
    return dict(total=sum(measured) if measured else None, measured_episodes=len(measured))


def _opportunities(items):
    attempts = [(t, _measured_number(t.get('native_execution_attempts'), 'native_execution_attempts', integer=True)) for t in items]
    recoveries = [(t, _measured_number((t.get('meta_diagnostic') or {}).get('recovery_count'),
                                       'recovery_count', integer=True)) for t in items]
    assigned_faults = [t for t in items if t['condition'] != 'normal']
    first_proposal_known = [(t, _measured_number(t.get('first_proposal_ms'), 'first_proposal_ms')) for t in items]
    return dict(episodes=len(items), assigned_fault_episodes=len(assigned_faults),
        realized_fault_episodes=sum(t['fault_injected'] for t in assigned_faults),
        realized_fault_rate_per_assigned_fault=sum(t['fault_injected'] for t in assigned_faults) / len(assigned_faults) if assigned_faults else None,
        episodes_reaching_native_dispatch=sum(v is not None and v > 0 for _, v in attempts),
        native_dispatch_count=_sum_measured(items, 'native_execution_attempts'),
        native_acceptance_count=_sum_measured(items, 'native_acceptances'),
        native_rejection_count=_sum_measured(items, 'native_rejections'),
        false_verified_offer_before_any_native_dispatch=sum(t['false_verified_offer'] is True and v == 0 for t, v in attempts),
        false_verified_offer_with_unknown_dispatch_count=sum(t['false_verified_offer'] is True and v is None for t, v in attempts),
        false_verified_offer_without_action_proposal=sum(t['false_verified_offer'] is True and v is None for t, v in first_proposal_known),
        controller_recovery_count=_sum_measured(items, 'recovery_count', nested=True),
        episodes_with_controller_recovery=sum(v is not None and v > 0 for _, v in recoveries),
        verified_goal_after_any_controller_recovery=sum(v is not None and v > 0 and t['verified_success'] for t, v in recoveries),
        controller_rejected_state_count=_sum_measured(items, 'blocked_count', nested=True),
        event_buffer_occupancy_at_end=_sum_measured(items, 'event_count', nested=True),
        caveat='Pre-dispatch false offers have no execution/recovery exposure. Counts after recovery are descriptive, not its causal success rate; event-buffer occupancy is not event coverage.')


def _arm_summary(items):
    result = metrics(items)
    result['mean_penalized_time_s'] = result['mean_penalized_time_ms'] / 1000 if items else None
    result['goal_observed_time'] = _distribution(items, 'goal_observed_ms', predicate=lambda t: t['verified_success'])
    result['runtime_verified_offer_time'] = _distribution(items, 'runtime_verified_offer_ms', predicate=lambda t: t['runtime_verified_offer'] is True)
    result['runtime_done_time'] = _distribution(items, 'runtime_done_ms', predicate=lambda t: t['declared_done'])
    result['first_action_proposal_time'] = _distribution(items, 'first_proposal_ms')
    result['server_provider_work_ms'] = _sum_measured(items, 'server_provider_work_ms', integer=False)
    result['episodes_with_provider_work_outside_window'] = sum(t.get('provider_work_exceeds_episode_elapsed') is True for t in items)
    result['opportunities'] = _opportunities(items)
    result['setup_statuses'] = dict(sorted(Counter(t.get('setup_status', 'unreported') for t in items).items()))
    result['runtime_outcomes'] = dict(sorted(Counter(t.get('outcome', 'unreported') for t in items).items()))
    return result


def summarize(manifest, records, *, bootstrap=2000):
    trials, scope = validate_grid(manifest, records)
    comparison = evaluate(trials, bootstrap=bootstrap)
    groups = comparison['evidence_groups']
    if len(groups) != 1:
        raise ValueError('Final pilot must have one evidence/planner/driver scope')
    summary = dict(schema_version='meta-controller-pilot-summary.v1',
        instrumentation_version=manifest['schema_version'], run_id=manifest['run_id'],
        completeness=dict(expected_episodes=216, actual_episodes=len(trials), complete=True,
            tasks=12, apps=4, assigned_conditions=3, repetitions=2, arms=list(ARMS)),
        frozen_scope=scope, budget_s=15,
        arm_order_scheme=manifest.get('arm_order_scheme', 'UNREPORTED'),
        overall={arm: _arm_summary([t for t in trials if t['arm'] == arm]) for arm in ARMS},
        ready_only_auxiliary={arm: _arm_summary([t for t in trials if t['arm'] == arm and t.get('setup_status') == 'ready']) for arm in ARMS},
        assigned_conditions=[dict(condition=condition,
            by_arm={arm: _arm_summary([t for t in trials if t['arm'] == arm and t['condition'] == condition]) for arm in ARMS})
            for condition in CONDITIONS.values()],
        applications=[dict(app_id=app,
            by_arm={arm: _arm_summary([t for t in trials if t['arm'] == arm and t['app_id'] == app]) for arm in ARMS})
            for app in sorted({t['app_id'] for t in trials})],
        strict_matched_comparisons=[c for c in groups[0]['paired'] if (c['left'], c['right']) in {('A', 'B'), ('B', 'C')}],
        go_no_go=comparison['go_no_go'],
        evidence_status=dict(original_252_trial_product_design='NOT_COMPLETED_BY_THIS_PILOT',
            settings_value_change_108='NOT_RUN', full_uianchor_reproduction=False,
            lsposed_and_trained_reflector='NOT_IMPLEMENTED', always_reflect_arm_D='NOT_IMPLEMENTED',
            formal_holdout_apps='NOT_ESTABLISHED', older_adult_user_study='NOT_RUN',
            independent_wrong_target_judgment='MEASURED_ONLY_WHERE_EXPLICITLY_RECORDED'),
        limitations=[
            'This is 216 scripted navigation episodes (144 A/B plus 72 C), not the originally proposed 252-trial product experiment.',
            'All assigned trials, including setup, transport and provider failures, remain in primary denominators.',
            'Assigned-condition tables use intention to treat; realized injection is an opportunity diagnostic, not an exclusion criterion.',
            'The dispatch-delay condition delays native execution by 5.5 seconds; it does not simulate UI rendering delay.',
            'Goal observation, runtime verified offer, first proposal and user-confirmed DONE are different events and clocks.',
            'Unavailable final oracle reads are unknown, not observed absence. Earlier stable positive goal evidence remains distinct from final-read availability.',
            'No user-result confirmation is simulated; zero false DONE alone is not evidence of correct completion grounding.',
            'False verified offers before any native dispatch do not test MC execution/recovery mechanisms.',
            'Recovery/dispatch counts expose whether the mechanism was exercised; subsequent success is not a causal recovery rate.',
            'Strict matching may exclude changing start-state hashes; excluded episodes remain in all-trial summaries.',
            'The 15-second budget, single phone, deterministic order and shallow public-navigation goals limit generalization.',
            'App names appearing in this pilot do not establish formal held-out generalization.',
            'This is a nonroot MC-v0 adaptation, not UIAnchor LSPosed, model training or its complete reflector architecture.',
            'Scripted approvals do not measure middle/older-adult performance, understanding, satisfaction or trust.',
            'Server provider work may extend beyond an episode; null latency/safety measurements are not zeros.',
            'Episode timing starts after scripted setup; setup/navigation preparation is not end-to-end user waiting time.',
            'Go/no-go flags are scoped data gates; research-question interpretations require reviewing exposure, uncertainty and limitations.'])
    return summary, comparison


def _value(value, digits=2):
    return 'unmeasured' if value is None else f'{value:.{digits}f}' if isinstance(value, float) else str(value)


def _table(by_arm):
    lines = ['| Arm | Goal/N | Failure-inclusive mean (s) | Verified offers | False offers/adjudicated offers | Model calls (measured/N) |',
             '|---|---:|---:|---:|---:|---:|']
    for arm in ARMS:
        m = by_arm[arm]
        lines.append(f'| {arm} | {m["verified_successes"]}/{m["trials"]} | {_value(m["mean_penalized_time_s"])} | '
            f'{m["runtime_verified_offers"]} | {m["false_verified_offers"]}/{m["adjudicated_verified_offers"]} | '
            f'{_value(m["model_calls_total"])} ({m["model_calls_measured_trials"]}/{m["trials"]}) |')
    return lines


def render_summary(summary):
    lines = ['# Complete MetaController native navigation pilot', '',
        f'Run: `{summary["run_id"]}`; instrumentation `{summary["instrumentation_version"]}`; **216/216** scheduled episodes, 12 tasks, four apps, A/B/C, two repetitions.',
        f'Budget: {summary["budget_s"]} seconds per episode. Scope: scripted approvals on one phone.', '',
        f'Deployment gate: **{summary["go_no_go"]["deployment"]}**. '
        f'Experiment continuation gate: **{summary["go_no_go"]["experiment_continuation"]}**.', '',
        '## All assigned episodes', ''] + _table(summary['overall'])
    lines += ['', 'Actual goal observation, runtime verification offers and first action proposals are distinct:', '',
        '| Arm | Goal-observed p50 s (measured/successes) | Verified-offer p50 s (measured/offers) | First-proposal p50 s (measured/N) | Runtime DONE count |',
        '|---|---:|---:|---:|---:|']
    for arm, m in summary['overall'].items():
        g, o, p = m['goal_observed_time'], m['runtime_verified_offer_time'], m['first_action_proposal_time']
        lines.append(f'| {arm} | {_value(g["p50_s"])} ({g["measured_episodes"]}/{g["eligible_episodes"]}) | '
            f'{_value(o["p50_s"])} ({o["measured_episodes"]}/{o["eligible_episodes"]}) | '
            f'{_value(p["p50_s"])} ({p["measured_episodes"]}/{p["eligible_episodes"]}) | {m["runtime_done_time"]["eligible_episodes"]} |')
    lines += ['', 'Final-read availability is separate from earlier stable positive goal evidence:', '',
        '| Arm | Final oracle observable | Final oracle unavailable | Coverage unreported | Positive goal evidence despite unavailable final read |',
        '|---|---:|---:|---:|---:|']
    for arm, m in summary['overall'].items():
        lines.append(f'| {arm} | {m["final_oracle_observable_trials"]} | {m["final_oracle_unobservable_trials"]} | '
            f'{m["final_oracle_coverage_unmeasured_trials"]} | {m["positive_goal_evidence_with_final_unobservable"]} |')
    for group in summary['assigned_conditions']:
        lines += ['', f'## Assigned condition: {group["condition"]}', '',
                  'All assigned episodes are included regardless of whether fault injection was reached.', ''] + _table(group['by_arm'])
    for group in summary['applications']:
        lines += ['', f'## App: {group["app_id"]}', ''] + _table(group['by_arm'])
    lines += ['', '## Mechanism opportunities', '',
        '| Arm | Dispatch-exposed episodes | Fault injected/assigned | False offers before any dispatch | Recovery count (measured/N) | Goal after recovery |',
        '|---|---:|---:|---:|---:|---:|']
    for arm, m in summary['overall'].items():
        o = m['opportunities']
        lines.append(f'| {arm} | {o["episodes_reaching_native_dispatch"]} | '
            f'{o["realized_fault_episodes"]}/{o["assigned_fault_episodes"]} | '
            f'{o["false_verified_offer_before_any_native_dispatch"]} | '
            f'{_value(o["controller_recovery_count"]["total"])} ({o["controller_recovery_count"]["measured_episodes"]}/{m["trials"]}) | '
            f'{o["verified_goal_after_any_controller_recovery"]} |')
    lines += ['', 'Pre-dispatch false offers are upstream failures without execution/recovery exposure. '
        'Goal after recovery is descriptive and cannot establish causal recovery effectiveness.', '', '## Strict matched comparisons', '']
    for c in summary['strict_matched_comparisons']:
        lines += [f'**{c["right"]} − {c["left"]}**: {c["paired_episodes"]} matched episode pairs, '
            f'{c["task_clusters"]} task clusters, {len(c["rejected_pairs"])} rejected/incomplete pairs.',
            '```json', json.dumps(dict(difference=c['difference_right_minus_left'],
                cluster_bootstrap_95ci=c['cluster_bootstrap_95ci']), indent=2), '```', '']
        reasons = Counter(r['reason'] for r in c['rejected_pairs'])
        if reasons:
            lines += ['Rejected-pair reasons: `' + json.dumps(dict(reasons), sort_keys=True) + '`.', '']
    lines += ['Full rejected pair IDs, missing fields and context mismatches are retained in `pilot-summary.json` and `comparison.json`.', '',
              '## Limits and unmeasured scope', ''] + ['- ' + s for s in summary['limitations']]
    return '\n'.join(lines) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--input', type=Path, required=True, help='Normalized measured JSONL from the joiner')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--bootstrap', type=int, default=2000)
    args = parser.parse_args(argv)
    try:
        raw_manifest = args.manifest.read_bytes()
        raw_input = args.input.read_bytes()
        records = [json.loads(line) for line in raw_input.decode('utf-8').splitlines() if line.strip()]
        summary, comparison = summarize(json.loads(raw_manifest), records, bootstrap=args.bootstrap)
        summary['input_sha256'] = dict(manifest=sha256(raw_manifest).hexdigest(), normalized_episodes=sha256(raw_input).hexdigest())
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, report in (('pilot-summary.json', summary), ('comparison.json', comparison)):
        (args.output_dir / name).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (args.output_dir / 'pilot-summary.md').write_text(render_summary(summary), encoding='utf-8')
    print(json.dumps(dict(report=str(args.output_dir / 'pilot-summary.md'),
                          episodes=summary['completeness']['actual_episodes'], gates=summary['go_no_go'])))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
