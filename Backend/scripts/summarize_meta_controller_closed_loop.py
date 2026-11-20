"""Summarize matched, synthetic closed-loop trials without real-app claims."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
from statistics import median

TASKS = frozenset(('nav_settings', 'nav_display', 'select_history', 'open_search', 'nested_advanced', 'nav_help'))
CONDITIONS = frozenset(('normal', 'accepted_no_effect', 'delayed_transition'))
EXPECTED_PAIRS = frozenset((task, condition, repetition) for task in TASKS
    for condition in CONDITIONS for repetition in range(2))


def _total(rows, key):
    return sum(row.get(key, 0) or 0 for row in rows)


def _arm(rows):
    times = [r['modeled_ui_ms_to_verified_offer'] for r in rows
             if r.get('runtime_verified_offer') and r.get('modeled_ui_ms_to_verified_offer') is not None]
    return dict(episodes=len(rows), goals=sum(r.get('independent_goal_reached') is True for r in rows),
        verified_offers=sum(r.get('runtime_verified_offer') is True for r in rows),
        false_verified_offers=_total(rows, 'false_verified_offers'), guard_violations=_total(rows, 'guard_violations'),
        actual_model_calls=_total(rows, 'actual_model_calls'), actual_model_wait_ms=round(_total(rows, 'actual_model_wait_ms'), 3),
        planner_requests=_total(rows, 'planner_requests'), accepted_actions=_total(rows, 'accepted_actions'),
        recovery_count=_total(rows, 'recovery_count'), needs_input_presentations=_total(rows, 'needs_input_presentations'),
        canonical_rejections=_total(rows, 'canonical_rejections'), gateway_errors=_total(rows, 'gateway_errors'),
        unsupported_tools=_total(rows, 'unsupported_tools'),
        listener_errors=_total(rows, 'listener_errors'), provider_or_plan_errors=_total(rows, 'provider_or_plan_errors'),
        primary_action_seen=sum(r.get('primary_action_seen') is True for r in rows),
        fault_injected=sum(r.get('fault_injected') is True for r in rows),
        modeled_ui_median_ms_to_verified_offer=median(times) if times else None)


def summarize(rows, readiness=None):
    if not rows:
        raise ValueError('No episode rows')
    modes = {r.get('evidence_mode') for r in rows}
    if len(modes) != 1 or modes - {'controlled_full_controller_conformance', 'live_model_synthetic_closed_loop'}:
        raise ValueError('One explicit synthetic evidence mode per analysis required')
    mode = next(iter(modes))
    grouped = defaultdict(dict)
    for row in rows:
        if row.get('schema_version') != 'click.synthetic-closed-loop.v1' or row.get('arm') not in {'A', 'B'}:
            raise ValueError('Invalid closed-loop episode schema/arm')
        pair = row.get('pair_id')
        if not pair or row['arm'] in grouped[pair]:
            raise ValueError('Missing pair or duplicate arm')
        grouped[pair][row['arm']] = row
    pairs = []
    invalid = []
    for name, pair in sorted(grouped.items()):
        if set(pair) != {'A', 'B'}:
            invalid.append(dict(pair_id=name, reason='missing_arm'))
            continue
        a, b = pair['A'], pair['B']
        if not a.get('setup_valid') or not b.get('setup_valid') or a.get('initial_state_key') != b.get('initial_state_key'):
            invalid.append(dict(pair_id=name, reason='unmatched_or_invalid_setup'))
            continue
        if any(a.get(k) != b.get(k) for k in ('task', 'condition', 'repetition')):
            raise ValueError('Pair metadata mismatch')
        pairs.append(pair)
    valid_rows = [r for p in pairs for r in p.values()]
    actual_pairs = [(p['A']['task'], p['A']['condition'], p['A']['repetition']) for p in pairs]
    matrix_complete = len(rows) == 72 and len(pairs) == 36 and len(set(actual_pairs)) == 36 and set(actual_pairs) == EXPECTED_PAIRS
    data_healthy = not invalid and all(r.get('setup_valid') and not any(r.get(field, 0) for field in
        ('canonical_rejections', 'gateway_errors', 'listener_errors', 'provider_or_plan_errors')) for r in rows)
    both_verified = [p for p in pairs if p['A'].get('runtime_verified_offer') and p['B'].get('runtime_verified_offer')]
    deltas = [p['B']['modeled_ui_ms_to_verified_offer'] - p['A']['modeled_ui_ms_to_verified_offer'] for p in both_verified]
    paired = Counter()
    for p in pairs:
        a, b = p['A'].get('independent_goal_reached') is True, p['B'].get('independent_goal_reached') is True
        paired['both' if a and b else 'A_only' if a else 'B_only' if b else 'neither'] += 1
    exposure = {}
    for condition in sorted(CONDITIONS - {'normal'}):
        counts = Counter()
        for p in pairs:
            if p['A']['condition'] != condition:
                continue
            a, b = p['A'].get('fault_injected') is True, p['B'].get('fault_injected') is True
            counts['both_exposed' if a and b else 'A_only_exposed' if a else 'B_only_exposed' if b else 'neither_exposed'] += 1
        exposure[condition] = {k: counts[k] for k in ('both_exposed', 'A_only_exposed', 'B_only_exposed', 'neither_exposed')}
        exposure[condition]['recovery_comparison_status'] = 'DESCRIPTIVE_SYNTHETIC_ONLY' if counts['both_exposed'] else 'NOT_TESTED'
    conditions = {}
    for condition in sorted({r['condition'] for r in rows}):
        subset = [r for r in valid_rows if r['condition'] == condition]
        conditions[condition] = {arm: _arm([r for r in subset if r['arm'] == arm]) for arm in ('A', 'B')}
    arms = {arm: _arm([r for r in valid_rows if r['arm'] == arm]) for arm in ('A', 'B')}
    unsafe = _total(rows, 'false_verified_offers') or _total(rows, 'guard_violations')
    readiness_ok = isinstance(readiness, dict) and readiness.get('passed') is True
    normal_ok = all(r.get('independent_goal_reached') and not r.get('canonical_rejections')
        for r in valid_rows if r.get('condition') == 'normal')
    normal_present = any(r.get('condition') == 'normal' for r in valid_rows)
    exposure_present = all(exposure[c]['both_exposed'] > 0 for c in CONDITIONS - {'normal'})
    next_stage = bool(readiness_ok and matrix_complete and data_healthy and not unsafe and normal_present and normal_ok and exposure_present)
    return dict(schema_version='click.synthetic-closed-loop-summary.v1', evidence_mode=mode,
        real_apps_tested=False, total_episodes=len(rows), valid_matched_pairs=len(pairs), invalid_pairs=invalid,
        readiness_passed=readiness_ok, frozen_matrix_complete=matrix_complete, healthy_data=data_healthy,
        expected_episodes=72, expected_matched_pairs=36, arms=arms, conditions=conditions,
        fault_exposure_by_assigned_condition=exposure,
        efficiency_comparison_status='DESCRIPTIVE_HEALTHY_SYNTHETIC_DATA_ONLY' if matrix_complete and data_healthy and not unsafe else 'NOT_INTERPRETABLE_INCOMPLETE_OR_UNHEALTHY_DATA',
        paired_goal_outcomes={k: paired[k] for k in ('both', 'A_only', 'B_only', 'neither')},
        paired_both_verified_count=len(both_verified),
        paired_median_modeled_ui_delta_B_minus_A_ms=median(deltas) if deltas else None,
        actual_models=sorted({r['actual_model'] for r in rows if r.get('actual_model')}),
        default_enablement_verdict='NO_GO_FOR_DEFAULT_ENABLEMENT',
        next_validation_stage='GO_TO_BROADER_REAL_APP_VALIDATION' if next_stage else 'NO_GO_FIX_OR_CHARACTERIZE_FAILURES',
        reasons=['Synthetic fixtures do not establish generality, real-app success, user benefit or wall-clock speed.',
            'Controlled planner knows fixture topology; its recovery benefit is constructed mechanism conformance.'
            if mode == 'controlled_full_controller_conformance' else
            'Gemini decisions use evolving synthetic observations; findings remain synthetic and model variability is retained.',
            'Assigned fault conditions and actual first-primary fault exposure are reported separately.',
            'A next-stage GO requires the complete 72/36 matrix, healthy data in every condition, successful normal controls and matched fault exposure in both injected conditions.',
            'Modeled UI waits and actual cloud call latency use separate clocks and must not be described as observed real-app latency.',
            'All assigned matched episodes are included; model/provider/gateway failures are reported, not silently removed.',
            'Two repetitions of six constructed workflows do not support population-level significance claims.'])


def render(summary):
    lines = [f"Evidence: {summary['evidence_mode']} (synthetic UI)",
        f"Episodes: {summary['total_episodes']}; matched pairs: {summary['valid_matched_pairs']}", '',
        '| Assigned condition | Arm | Goal | Verified | Calls | Planner requests | Injected faults | UI median ms |',
        '|---|---:|---:|---:|---:|---:|---:|---:|']
    for condition, data in summary['conditions'].items():
        for arm, row in data.items():
            lines.append(f"| {condition} | {arm} | {row['goals']}/{row['episodes']} | {row['verified_offers']} | "
                f"{row['actual_model_calls']} | {row['planner_requests']} | {row['fault_injected']} | {row['modeled_ui_median_ms_to_verified_offer']} |")
    lines += ['', f"Default enablement: {summary['default_enablement_verdict']}",
        f"Next validation stage: {summary['next_validation_stage']}",
        f"Complete frozen matrix: {summary['frozen_matrix_complete']}; healthy data: {summary['healthy_data']}",
        f"Efficiency comparison: {summary['efficiency_comparison_status']}", '',
        '| Assigned fault | Both exposed | A only | B only | Neither | Interpretation |',
        '|---|---:|---:|---:|---:|---|']
    for condition, counts in summary['fault_exposure_by_assigned_condition'].items():
        lines.append(f"| {condition} | {counts['both_exposed']} | {counts['A_only_exposed']} | {counts['B_only_exposed']} | {counts['neither_exposed']} | {counts['recovery_comparison_status']} |")
    lines += ['']
    lines += ['- ' + reason for reason in summary['reasons']]
    return '\n'.join(lines) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--episodes', type=Path, required=True)
    parser.add_argument('--readiness', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--markdown', type=Path)
    args = parser.parse_args(argv)
    rows = [json.loads(line) for line in args.episodes.read_text().splitlines() if line.strip()]
    readiness = json.loads(args.readiness.read_text()) if args.readiness else None
    result = summarize(rows, readiness)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    if args.markdown:
        args.markdown.write_text(render(result))
    print(json.dumps(dict(episodes=len(rows), pairs=result['valid_matched_pairs'],
        evidence_mode=result['evidence_mode'], next_validation_stage=result['next_validation_stage'])))


if __name__ == '__main__':
    main()
