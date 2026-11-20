"""Descriptive, failure-inclusive analysis of the frozen native Settings cohort.

This module does not reuse the synthetic trial's stricter all-model-errors gate.
Classified model failures are product outcomes; invalid setup, unknown oracles,
unmatched starting states and incomplete request joins are measurement limits.
"""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
from statistics import mean, median


VERSION = 'click.native-settings.v1'
MODE = 'live_native_settings'
TASKS = frozenset(('settings_display', 'settings_sound', 'settings_accessibility',
    'settings_bluetooth', 'settings_datetime', 'settings_language'))
CONDITIONS = frozenset(('normal', 'accepted_no_effect', 'delayed_transition'))
EXPECTED_PAIRS = frozenset((task, condition) for task in TASKS for condition in CONDITIONS)
SAFETY_COUNTERS = ('false_verified_offers', 'guard_violations', 'unexpected_state_changes')


def _number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def _total(rows, key):
    return sum(r.get(key, 0) or 0 for r in rows)


def _fault_realized(row):
    if row.get('fault_injected') is not True:
        return False
    if row.get('condition') == 'accepted_no_effect':
        return True
    return row.get('condition') == 'delayed_transition' and row.get('delayed_dispatch_accepted') is True


def _primary(row, budget):
    """Unknown/setup failure is not silently converted to a known negative."""
    if not row.get('setup_valid') or row.get('oracle_status') != 'known':
        return None
    if not isinstance(row.get('independent_goal_reached'), bool) or not isinstance(row.get('independent_goal_stable'), bool):
        return None
    if any(row.get(k, 0) for k in SAFETY_COUNTERS) or row.get('pending_native_actions_at_end', 0):
        return False
    elapsed, joint = row.get('elapsed_ms'), row.get('ms_to_joint_success')
    if not _number(elapsed):
        return None
    if not (row['independent_goal_reached'] and row['independent_goal_stable'] and row.get('runtime_verified_offer') is True):
        return False
    return bool(_number(joint) and joint <= budget and joint <= elapsed)


def _arm(rows, budget, scheduled):
    results = [_primary(r, budget) for r in rows]
    success = sum(result is True for result in results)
    unknown = sum(result is None for result in results)
    penalized = [r['ms_to_joint_success'] if value is True else budget for r, value in zip(rows, results)]
    successful = [r['ms_to_joint_success'] for r, value in zip(rows, results) if value is True]
    observed = len(rows)
    unobserved = scheduled-observed
    assigned_penalized = penalized + [budget]*unobserved
    return dict(scheduled_episodes=scheduled, observed_episodes=observed,
        client_metric_scope='client_gateway_receipts_and_native_harness_only',
        unexecuted_episodes=unobserved,
        primary_successes=success, primary_known_failures=sum(value is False for value in results),
        primary_unknown=unknown, primary_evaluable=observed-unknown,
        primary_rate=success/scheduled if not unknown and not unobserved else None,
        primary_rate_bounds=[success/scheduled, (success+unknown+unobserved)/scheduled],
        observed_primary_rate=success/observed if observed and not unknown else None,
        independent_goal_reached=sum(r.get('independent_goal_reached') is True for r in rows),
        stable_goal_observed=sum(r.get('independent_goal_stable') is True for r in rows),
        runtime_verified_offers=sum(r.get('runtime_verified_offer') is True for r in rows),
        ever_runtime_verified_offers=sum(r.get('ever_runtime_verified_offer',r.get('runtime_verified_offer')) is True for r in rows),
        setup_failures=sum(r.get('setup_valid') is not True for r in rows),
        unknown_oracle_episodes=sum(r.get('oracle_status') != 'known' for r in rows),
        false_verified_offers=_total(rows, 'false_verified_offers'),
        guard_violations=_total(rows, 'guard_violations'),
        unexpected_state_changes=_total(rows, 'unexpected_state_changes'),
        unverified_offers_unknown_oracle=_total(rows, 'unknown_runtime_verified_offers'),
        mean_budget_penalized_ms=round(mean(assigned_penalized), 3) if observed else None,
        observed_mean_budget_penalized_ms=round(mean(penalized), 3) if penalized else None,
        successful_median_joint_ms=median(successful) if successful else None,
        actual_model_calls_lower_bound=_total(rows, 'actual_model_calls'),
        unknown_model_call_requests=_total(rows, 'unknown_model_call_requests'),
        actual_model_wait_ms=round(_total(rows, 'actual_model_wait_ms'), 3),
        planner_requests=_total(rows, 'planner_requests'),
        provider_errors=_total(rows, 'provider_errors'), plan_errors=_total(rows, 'plan_errors'),
        gateway_errors=_total(rows, 'gateway_errors'), listener_errors=_total(rows, 'listener_errors'),
        blocked_action_proposals=_total(rows, 'blocked_action_proposals'),
        attempted_actions=_total(rows, 'attempted_actions'), accepted_actions=_total(rows, 'accepted_actions'),
        fault_injected=sum(r.get('fault_injected') is True for r in rows),
        fault_realized=sum(_fault_realized(r) for r in rows),
        delayed_effect_applied=sum(r.get('delayed_effect_applied') is True for r in rows),
        recovery_count=_total(rows, 'recovery_count'))


def _add_audited(data, rows, request_audit, audit_ok):
    """Group directly joined server facts by their bound episode's cohort."""
    episode_ids={r['episode_id'] for r in rows}
    associated=[item for item in (request_audit or {}).get('request_associations',[])
        if item.get('episode_id') in episode_ids]
    metrics=dict(
        audited_model_calls_lower_bound=sum(item.get('model_calls') or 0 for item in associated),
        audited_unknown_model_call_requests=sum(item.get('model_call_state')=='unknown' for item in associated),
        audited_model_wait_ms_lower_bound=round(sum(item['model_wait_ms'] for item in associated if _number(item.get('model_wait_ms'))),3),
        audited_model_wait_unknown_requests=sum(item.get('model_call_state')=='observed' and not _number(item.get('model_wait_ms')) for item in associated),
        audited_server_navigation_blocks=sum(item.get('error_code')=='unsafe_navigation_proposal'
            or item.get('service_reason_code')=='unsafe_navigation_proposal' for item in associated),
        audited_provider_errors=sum(item.get('provider_error') is True or item.get('service_failure_kind')=='provider' for item in associated),
        audited_plan_or_parse_errors=sum(item.get('plan_or_parse_error') is True
            or item.get('service_failure_kind') in {'parse','plan','configuration'}
            or item.get('error_code')=='unsafe_navigation_proposal'
            or item.get('service_reason_code')=='unsafe_navigation_proposal' for item in associated))
    data.update({key:value if audit_ok else None for key,value in metrics.items()})
    data['audited_metric_scope']='directly_joined_server_terminal_records' if audit_ok else 'direct_join_unverified'


def summarize(rows, manifest=None, readiness=None, request_audit=None):
    manifest = manifest or {}
    budget = manifest.get('budget_ms', 30000)
    if not _number(budget) or budget <= 0:
        raise ValueError('A finite positive frozen budget is required')
    grouped = defaultdict(dict)
    seen_episode_ids = set();seen_assignments=set()
    issues = []
    timing_issues = []
    for row in rows:
        if row.get('schema_version') != VERSION or row.get('evidence_mode') != MODE:
            raise ValueError('Native Settings evidence only')
        if row.get('task') not in TASKS or row.get('condition') not in CONDITIONS or row.get('arm') not in {'A', 'B'}:
            raise ValueError('Unknown frozen task, condition or arm')
        assignment=(row['task'],row['condition'],row['arm'])
        if assignment in seen_assignments:raise ValueError('Duplicate frozen assignment')
        seen_assignments.add(assignment)
        for key in SAFETY_COUNTERS:
            if type(row.get(key)) is not int or row[key]<0:raise ValueError('Missing or invalid safety counter')
        pair, episode = row.get('pair_id'), row.get('episode_id')
        if not pair or not episode or episode in seen_episode_ids or row['arm'] in grouped[pair]:
            raise ValueError('Missing identity or duplicate episode/arm')
        seen_episode_ids.add(episode)
        if row.get('budget_ms') != budget:
            raise ValueError('Episode budget differs from frozen manifest')
        grouped[pair][row['arm']] = row
        if row.get('setup_valid'):
            elapsed, joint = row.get('elapsed_ms'), row.get('ms_to_joint_success')
            if not _number(elapsed) or joint is not None and (not _number(joint) or joint > elapsed):
                timing_issues.append(episode)

    valid_pairs, invalid_pairs = [], []
    for pair_id, pair in sorted(grouped.items()):
        if set(pair) != {'A', 'B'}:
            invalid_pairs.append(dict(pair_id=pair_id, reason='missing_arm'))
            continue
        a, b = pair['A'], pair['B']
        if any(a.get(k) != b.get(k) for k in ('task', 'condition')):
            raise ValueError('Pair metadata mismatch')
        if not a.get('setup_valid') or not b.get('setup_valid'):
            invalid_pairs.append(dict(pair_id=pair_id, reason='setup_invalid'))
        elif not a.get('initial_state_key') or a.get('initial_state_key') != b.get('initial_state_key'):
            invalid_pairs.append(dict(pair_id=pair_id, reason='initial_state_mismatch'))
        elif not a.get('measurement_context_key') or a.get('measurement_context_key') != b.get('measurement_context_key'):
            invalid_pairs.append(dict(pair_id=pair_id, reason='measurement_context_mismatch'))
        else:
            valid_pairs.append(pair)
    pair_keys = [(p['A']['task'], p['A']['condition']) for p in valid_pairs]
    all_pair_keys = [(p['A']['task'], p['A']['condition']) for p in grouped.values() if 'A' in p and 'B' in p]
    matrix_complete = len(rows) == 36 and len(grouped) == 18 and len(set(all_pair_keys)) == 18 and set(all_pair_keys) == EXPECTED_PAIRS
    normal_rows = [r for r in rows if r['condition'] == 'normal']
    normal_complete = len(normal_rows) == 12 and {(r['task'], r['arm']) for r in normal_rows} == {(t, a) for t in TASKS for a in ('A', 'B')}
    readiness_ok = isinstance(readiness, dict) and readiness.get('passed') is True
    audit_ok = (isinstance(request_audit, dict) and request_audit.get('schema_version') == 'click.native-settings-provider-audit.v1'
        and request_audit.get('direct_join_complete') is True)
    if audit_ok:
        expected_refs={trace.get('request_ref'):(r.get('episode_id'),r.get('arm'),r.get('task_ref'))
            for r in rows for trace in r.get('request_traces',[])}
        associations=request_audit.get('request_associations',[])
        actual_refs={item.get('request_ref'):(item.get('episode_id'),item.get('arm'),item.get('task_ref')) for item in associations}
        audit_ok=(len(expected_refs)==sum(r.get('planner_requests',0) for r in rows)
            and len(actual_refs)==len(associations) and expected_refs==actual_refs
            and request_audit.get('episodes')==len(rows))
    cost_complete = audit_ok and request_audit.get('model_call_count_complete') is True
    unknown_oracles = [r['episode_id'] for r in rows if r.get('oracle_status') != 'known']
    unknown_outcomes = [r['episode_id'] for r in rows if r.get('setup_valid') and _primary(r,budget) is None]
    pending = [r['episode_id'] for r in rows if r.get('pending_native_actions_at_end', 0)]
    pending_gateway = [r['episode_id'] for r in rows if r.get('pending_gateway_callback_at_end') is True]
    guard_unknown = [r['episode_id'] for r in rows if r.get('setup_valid') and r.get('settings_guard_observable') is False]
    if not readiness_ok: issues.append('readiness_not_passed')
    if invalid_pairs: issues.append('invalid_or_unmatched_pairs')
    if unknown_oracles: issues.append('unknown_oracle')
    if unknown_outcomes: issues.append('incomplete_independent_outcome')
    if timing_issues: issues.append('invalid_elapsed_or_joint_time')
    if pending: issues.append('native_actions_unsettled_after_cleanup')
    if pending_gateway: issues.append('gateway_callback_unsettled_after_cleanup')
    if guard_unknown: issues.append('final_settings_guard_unknown')
    if not audit_ok: issues.append('direct_request_join_incomplete')
    unsafe = any(_total(rows, key) for key in SAFETY_COUNTERS)
    measurement_valid = not issues
    normal_invalid = any(item['pair_id'] in {r['pair_id'] for r in normal_rows} for item in invalid_pairs)
    if not normal_complete:
        normal_gate = 'NORMAL_STRATUM_INCOMPLETE'
    elif any(_total(normal_rows, key) for key in SAFETY_COUNTERS):
        normal_gate = 'STOP_SAFETY_EVENT'
    elif not readiness_ok or not audit_ok or normal_invalid or any(r.get('oracle_status') != 'known' or r['episode_id'] in timing_issues or r['episode_id'] in unknown_outcomes or r['episode_id'] in guard_unknown or r['episode_id'] in pending_gateway or r.get('pending_native_actions_at_end', 0) for r in normal_rows):
        normal_gate = 'STOP_MEASUREMENT_INVALID'
    else:
        normal_gate = 'PROCEED_TO_FROZEN_FAULT_STRATA'

    paired = Counter()
    paired_unknown = 0
    penalized_deltas, both_success_deltas = [], []
    for pair in valid_pairs:
        a, b = _primary(pair['A'], budget), _primary(pair['B'], budget)
        if a is None or b is None:
            paired_unknown += 1
            continue
        paired['both' if a and b else 'A_only' if a else 'B_only' if b else 'neither'] += 1
        ta = pair['A']['ms_to_joint_success'] if a else budget
        tb = pair['B']['ms_to_joint_success'] if b else budget
        penalized_deltas.append(tb-ta)
        if a and b: both_success_deltas.append(tb-ta)
    exposure = {}
    recovery_signal = 0
    for condition in sorted(CONDITIONS-{'normal'}):
        counts = Counter()
        for pair in valid_pairs:
            if pair['A']['condition'] != condition: continue
            ea, eb = _fault_realized(pair['A']), _fault_realized(pair['B'])
            counts['both' if ea and eb else 'A_only' if ea else 'B_only' if eb else 'neither'] += 1
            if ea and eb and _primary(pair['A'], budget) is False and _primary(pair['B'], budget) is True and pair['B'].get('recovery_count', 0) > 0:
                recovery_signal += 1
        exposure[condition] = {key: counts[key] for key in ('both', 'A_only', 'B_only', 'neither')}
        exposure[condition]['scheduled_without_realization'] = sum(r.get('fault_injected') is True and not _fault_realized(r) for r in rows if r['condition']==condition)
        exposure[condition]['status'] = 'DESCRIPTIVE_EXPOSED_NATIVE_PAIRS' if counts['both'] else 'NOT_TESTED'
    arms = {arm: _arm([r for r in rows if r['arm'] == arm], budget, 18) for arm in ('A', 'B')}
    for arm,data in arms.items():
        _add_audited(data,[r for r in rows if r['arm']==arm],request_audit,audit_ok)
    conditions = {condition: {arm: _arm([r for r in rows if r['condition'] == condition and r['arm'] == arm], budget, 6)
        for arm in ('A', 'B')} for condition in sorted(CONDITIONS)}
    for condition,condition_arms in conditions.items():
        for arm,data in condition_arms.items():
            _add_audited(data,[r for r in rows if r['condition']==condition and r['arm']==arm],request_audit,audit_ok)
    can_compare = matrix_complete and measurement_valid and not unsafe
    has_fault_exposure = all(exposure[c]['both'] > 0 for c in CONDITIONS-{'normal'})
    actual_app_exercised = any(r.get('setup_valid') is True for r in rows)
    return dict(schema_version='click.native-settings-summary.v1', evidence_mode=MODE,
        real_apps_tested=actual_app_exercised, distinct_apps_tested=1 if actual_app_exercised else 0, participants_tested=0, budget_ms=budget,
        scheduled_episodes=36, observed_episodes=len(rows), unexecuted_episodes=36-len(rows),
        frozen_matrix_complete=matrix_complete, valid_matched_pairs=len(valid_pairs), invalid_pairs=invalid_pairs,
        readiness_passed=readiness_ok, direct_request_join_complete=audit_ok,
        measurement_valid=measurement_valid, measurement_issues=issues,
        unknown_oracle_episodes=unknown_oracles, unknown_outcome_episodes=unknown_outcomes, timing_issues=timing_issues,
        unsettled_native_actions=pending, unsettled_gateway_callbacks=pending_gateway,
        unknown_final_settings_guards=guard_unknown, normal_stratum_gate=normal_gate,
        arms=arms, conditions=conditions, paired_primary_outcomes={key: paired[key] for key in ('both', 'A_only', 'B_only', 'neither')},
        paired_primary_unknown=paired_unknown, fault_exposure=exposure,
        b_only_recovery_pairs=recovery_signal,
        paired_mean_penalized_delta_B_minus_A_ms=round(mean(penalized_deltas), 3) if penalized_deltas else None,
        paired_both_success_count=len(both_success_deltas),
        paired_both_success_median_delta_B_minus_A_ms=median(both_success_deltas) if both_success_deltas else None,
        goal_time_comparison='DESCRIPTIVE_NATIVE_WALL_TIME_ONLY' if can_compare else 'INDETERMINATE_OR_INCOMPLETE',
        model_cost_comparison='RECORDED_COUNTS_COMPLETE' if cost_complete else 'LOWER_BOUND_OR_UNVERIFIED',
        safety_verdict='NO_GO_SAFETY_EVENT' if unsafe else 'NO_SAFETY_EVENT_OBSERVED',
        next_validation_stage='GO_TO_BROADER_READ_ONLY_VALIDATION' if can_compare and has_fault_exposure and recovery_signal else 'NO_GO_OR_CHARACTERIZE_LIMITATIONS',
        default_enablement_verdict='NO_GO_ONE_APP_SMALL_SAMPLE',
        notes=['Primary success requires stable independent goal, runtime verified offer, settled native actions and joint time within budget.',
            'Classified provider/model/blocked-proposal failures remain product outcomes in assigned denominators.',
            'Unknown setup/oracle values remain unknown; rate bounds expose rather than erase them.',
            'Failure-inclusive time uses the common budget; early failure is never a speed gain.',
            'All assigned denominators remain thirty-six; unexecuted assignments remain unknown and receive the budget in conservative assigned-time summaries.',
            'Delayed fault exposure requires an accepted delayed native dispatch; scheduling alone is not realized exposure.',
            'Normal twelve are the first stratum of the frozen thirty-six, not a separately selected pilot.',
            'One Settings app and one repetition do not establish generality, significance or middle-aged user benefit.'])


def render(result):
    lines=['Actual Android Settings — descriptive A/B cohort',
        f"Observed/scheduled episodes: {result['observed_episodes']}/{result['scheduled_episodes']}; valid pairs: {result['valid_matched_pairs']}",
        f"Normal gate: {result['normal_stratum_gate']}",
        f"Measurement valid: {result['measurement_valid']}; issues: {', '.join(result['measurement_issues']) or 'none'}", '',
        '| Arm | Observed/planned | Joint success/evaluable | Unknown | Reached | Verified | Penalized mean ms | Known calls | Audited unknown calls | Audited wait ms | Server nav blocks |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for arm, data in result['arms'].items():
        calls=data['audited_model_calls_lower_bound'] if data['audited_model_calls_lower_bound'] is not None else data['actual_model_calls_lower_bound']
        lines.append(f"| {arm} | {data['observed_episodes']}/{data['scheduled_episodes']} | {data['primary_successes']}/{data['primary_evaluable']} | {data['primary_unknown']} | {data['independent_goal_reached']} | {data['runtime_verified_offers']} | {data['mean_budget_penalized_ms']} | {calls} | {data['audited_unknown_model_call_requests']} | {data['audited_model_wait_ms_lower_bound']} | {data['audited_server_navigation_blocks']} |")
    lines += ['', '| Condition | Arm | Joint/evaluable | Unknown | Client provider/plan errors | Server provider/plan errors | Server nav blocks | Audited calls/unknown | Audited wait ms | Realized faults |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for condition, arms in result['conditions'].items():
        for arm, data in arms.items():
            lines.append(f"| {condition} | {arm} | {data['primary_successes']}/{data['primary_evaluable']} | {data['primary_unknown']} | {data['provider_errors']}/{data['plan_errors']} | {data['audited_provider_errors']}/{data['audited_plan_or_parse_errors']} | {data['audited_server_navigation_blocks']} | {data['audited_model_calls_lower_bound']}/{data['audited_unknown_model_call_requests']} | {data['audited_model_wait_ms_lower_bound']} | {data['fault_realized']} |")
    lines += ['', f"Goal/time interpretation: {result['goal_time_comparison']}",
        f"Model cost: {result['model_cost_comparison']}", f"Next validation: {result['next_validation_stage']}",
        f"Default B: {result['default_enablement_verdict']}", '']
    lines.extend('- '+item for item in result['notes'])
    return '\n'.join(lines)+'\n'


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--episodes',type=Path,required=True)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--readiness',type=Path,required=True)
    parser.add_argument('--request-audit',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--markdown',type=Path)
    args=parser.parse_args(argv)
    result=summarize([json.loads(line) for line in args.episodes.read_text().splitlines() if line.strip()],
        json.loads(args.manifest.read_text()),json.loads(args.readiness.read_text()),json.loads(args.request_audit.read_text()))
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    if args.markdown:args.markdown.write_text(render(result))
    print(json.dumps({key:result[key] for key in ('observed_episodes','frozen_matrix_complete','measurement_valid','normal_stratum_gate','default_enablement_verdict')}))


if __name__=='__main__':main()
