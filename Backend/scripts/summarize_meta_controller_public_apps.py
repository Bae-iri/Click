"""Descriptive, failure-inclusive analysis of the frozen native public-apps cohort.

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


VERSION = 'click.native-public-apps.v1'
VERSION2 = 'click.native-public-apps.v1'
REVISION2 = 'equal_independent_observation_window_public_apps_v1'
MODE = 'live_native_public_apps'
CONDITIONS = frozenset(('normal', 'accepted_no_effect', 'delayed_transition'))
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


def _episode_version(rows,manifest):
    versions={row.get('schema_version') for row in rows}
    if len(versions)>1:raise ValueError('Mixed native public-apps episode versions')
    version=next(iter(versions),manifest.get('schema_version',VERSION))
    if version not in {VERSION,VERSION2}:raise ValueError('Native public apps evidence only')
    if VERSION2 in {version,manifest.get('schema_version')} and manifest.get('schema_version')!=version:
        raise ValueError('Episode/manifest version mismatch')
    return version


def _v2_row_issues(row,budget):
    if row.get('setup_valid') is not True:return []
    issues=[];elapsed=row.get('elapsed_ms');joint=row.get('ms_to_joint_success')
    samples,known,unknown=(row.get(key) for key in ('oracle_samples','oracle_known_samples','oracle_unknown_samples'))
    counts_valid=all(type(value) is int and value>=0 for value in (samples,known,unknown))
    if not counts_valid or not samples or known+unknown!=samples:issues.append('invalid_oracle_sample_counts')
    elif not known:issues.append('no_known_measurement_oracle')
    last=row.get('last_oracle_sample_ms')
    if not _number(last) or last>budget or not _number(elapsed) or last>elapsed:issues.append('invalid_last_oracle_sample_time')
    times={}
    for flag,key in (('ever_independent_goal_stable','first_independent_stable_goal_ms'),
            ('ever_runtime_verified_offer','first_runtime_verified_ms')):
        seen,value=row.get(flag),row.get(key)
        if type(seen) is not bool or seen != (value is not None):issues.append('inconsistent_'+key)
        if value is not None and (not _number(value) or value>budget or not _number(elapsed) or value>elapsed):
            issues.append('invalid_'+key)
        times[key]=value
    stable=times['first_independent_stable_goal_ms']
    if _number(stable) and stable<700:issues.append('stable_goal_before_required_window')
    if _number(stable) and _number(last) and stable>last:issues.append('stable_goal_after_last_sample')
    cleanup_status,cleanup_goal=row.get('cleanup_oracle_status'),row.get('goal_present_after_cleanup')
    if not (cleanup_status=='known' and type(cleanup_goal) is bool or cleanup_status=='unknown' and cleanup_goal is None):
        issues.append('inconsistent_cleanup_oracle')
    claimed=row.get('joint_success')
    if type(claimed) is not bool:issues.append('missing_joint_success_flag')
    if claimed is False:
        if joint is not None:issues.append('joint_time_without_success')
        if not _number(elapsed) or elapsed<budget:issues.append('nonjoint_observation_ended_early')
    elif claimed is True:
        first_verified=times['first_runtime_verified_ms']
        if not (_number(joint) and joint<=budget and _number(elapsed) and joint<=elapsed
                and _number(stable) and stable<=joint and _number(first_verified) and first_verified<=joint
                and row.get('oracle_status')=='known' and row.get('independent_goal_reached') is True
                and row.get('independent_goal_stable') is True and row.get('runtime_verified_offer') is True
                and not row.get('pending_native_actions_at_end',0) and not any(row.get(key,0) for key in SAFETY_COUNTERS)):
            issues.append('inconsistent_joint_success')
    return issues


def _primary(row, budget):
    """Unknown/setup failure is not silently converted to a known negative."""
    if not row.get('setup_valid') or row.get('oracle_status') != 'known':
        return None
    if row.get('schema_version')==VERSION2 and _v2_row_issues(row,budget):return None
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


def _add_audited(data, rows, request_audit, audit_ok,version=VERSION):
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
    if version==VERSION2:
        observed=[item for item in associated if item.get('model_call_state')=='observed' and type(item.get('model_calls')) is int and item['model_calls']==1]
        required=('recovery_context_used','capabilities_applied','allowed_actions_context_used','allowed_action_count','recovery_blocked_action_count')
        missing=sum(any(key not in item for key in required) or item.get('recovery_context_used') is True and 'recovery_attempt' not in item for item in observed)
        reasons=Counter(item['recovery_reason'] for item in observed if item.get('recovery_context_used') is True
            and item.get('recovery_policy') in {'mc_v0','mc_v1'} and item.get('recovery_reason') in {'accepted_action_unconfirmed','observed_transition_unconfirmed'})
        data['audited_token_usage']={key:dict(
            observed_sum=sum(item[key] for item in observed if type(item.get(key)) is int),
            observed_attempts_with_usage=sum(type(item.get(key)) is int for item in observed),
            observed_attempts_usage_unknown=sum(type(item.get(key)) is not int for item in observed))
            for key in ('input_tokens','output_tokens','thinking_tokens','total_tokens')} if audit_ok else None
        data['audited_payload_context_evidence']=dict(
            observed_calls_by_recovery_reason=dict(reasons),
            recovery_reason_missing_observed_attempts=sum(item.get('recovery_context_used') is True and 'recovery_reason' not in item for item in observed),
            observed_model_attempts=len(observed),
            recovery_context_forwarded_attempts=sum(item.get('recovery_context_used') is True for item in observed),
            trusted_capabilities_applied_attempts=sum(item.get('capabilities_applied') is True for item in observed),
            allowed_actions_context_forwarded_attempts=sum(item.get('allowed_actions_context_used') is True for item in observed),
            metadata_missing_or_invalid_observed_attempts=missing,
            unknown_model_state_requests=sum(item.get('model_call_state')=='unknown' for item in associated),
            allowed_action_count_sum=sum(item['allowed_action_count'] for item in observed if type(item.get('allowed_action_count')) is int),
            removed_blocked_pair_count_sum=sum(item['recovery_blocked_action_count'] for item in observed if type(item.get('recovery_blocked_action_count')) is int),
            recovery_attempt_counts=dict(Counter(str(item['recovery_attempt']) for item in observed if item.get('recovery_context_used') is True and type(item.get('recovery_attempt')) is int)),
            scope='Joined observed calls and typed payload metadata establish forwarding, not model attention or benefit.') if audit_ok else None


def _add_v2_timelines(data,rows,budget):
    valid=[row for row in rows if row.get('setup_valid') is True and not _v2_row_issues(row,budget)]
    for key in ('first_independent_stable_goal_ms','first_runtime_verified_ms'):
        times=[row[key] for row in valid if _number(row.get(key))]
        data[key+'_observed_episodes']=len(times)
        data[key+'_median']=median(times) if times else None
    data['ever_independent_goal_stable']=sum(row.get('ever_independent_goal_stable') is True for row in rows)
    for key in ('oracle_samples','oracle_known_samples','oracle_unknown_samples'):
        data[key]=sum(row[key] for row in rows if type(row.get(key)) is int and row[key]>=0)
    data['oracle_unknown_sample_fraction']=data['oracle_unknown_samples']/data['oracle_samples'] if data['oracle_samples'] else None
    data['goal_present_after_cleanup']=sum(row.get('goal_present_after_cleanup') is True for row in rows)
    data['coordination_decisions']={key:sum(row.get(key,0) for row in rows) for key in
        ('recovery_count','progress_rescue_count','failure_replan_reservations','privacy_guard_rejections')}
    data['coordination_decision_metadata_missing_episodes']=sum(any(type(row.get(key)) is not int for key in
        ('progress_rescue_count','failure_replan_reservations')) for row in rows)
    data['unknown_cleanup_oracle_episodes']=sum(row.get('cleanup_oracle_status')!='known' for row in rows)
    tails=[budget-row['last_oracle_sample_ms'] for row in valid if row.get('joint_success') is False]
    data['max_oracle_tail_gap_ms']=max(tails) if tails else None


def summarize(rows, manifest=None, readiness=None, request_audit=None):
    manifest = manifest or {}
    version=_episode_version(rows,manifest)
    catalogue=manifest.get('catalogue',{});definitions=catalogue.get('tasks',[])
    if not isinstance(definitions,list) or len(definitions)!=6:raise ValueError('Six frozen public-app tasks required')
    task_packages={item.get('task_id'):item.get('package') for item in definitions if isinstance(item,dict)}
    if (len(task_packages)!=6 or any(not isinstance(task,str) or not task or not isinstance(package,str) or not package for task,package in task_packages.items())
            or len(set(task_packages.values()))!=3 or any(list(task_packages.values()).count(package)!=2 for package in set(task_packages.values()))):
        raise ValueError('Exactly two tasks per each of three frozen apps required')
    tasks=frozenset(task_packages);expected_pairs=frozenset((task,condition) for task in tasks for condition in CONDITIONS)
    scheduled=manifest.get('scheduled_pairs',[])
    if not isinstance(scheduled,list) or len(scheduled)!=18:raise ValueError('Frozen full eighteen-pair schedule required')
    scheduled_by_id={pair.get('pair_id'):pair for pair in scheduled if isinstance(pair,dict)}
    if (len(scheduled_by_id)!=18 or any(not isinstance(key,str) or not key for key in scheduled_by_id)
            or {(pair.get('task'),pair.get('condition')) for pair in scheduled}!=expected_pairs
            or any(pair.get('package')!=task_packages[pair['task']] or pair.get('arms') not in (['A','B'],['B','A']) for pair in scheduled)):
        raise ValueError('Schedule differs from the frozen task/app matrix')
    budget = manifest.get('budget_ms', 30000)
    if not _number(budget) or budget <= 0:
        raise ValueError('A finite positive frozen budget is required')
    grouped = defaultdict(dict)
    seen_episode_ids = set();seen_assignments=set()
    issues = []
    timing_issues = []
    for row in rows:
        if row.get('schema_version') != version or row.get('evidence_mode') != MODE:
            raise ValueError('Native public apps evidence only')
        if row.get('task') not in tasks or row.get('condition') not in CONDITIONS or row.get('arm') not in {'A', 'B'}:
            raise ValueError('Unknown frozen task, condition or arm')
        if row.get('package')!=task_packages[row['task']]:raise ValueError('Episode package differs from frozen task')
        declared=scheduled_by_id.get(row.get('pair_id'))
        if (declared is None or row['task']!=declared['task'] or row['condition']!=declared['condition']
                or row.get('arm_order')!=declared['arms'].index(row['arm'])
                or row.get('episode_id')!=row['pair_id']+':'+row['arm']):raise ValueError('Episode differs from frozen schedule')
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
    matrix_complete = len(rows) == 36 and len(grouped) == 18 and len(set(all_pair_keys)) == 18 and set(all_pair_keys) == expected_pairs
    normal_rows = [r for r in rows if r['condition'] == 'normal']
    normal_complete = len(normal_rows) == 12 and {(r['task'], r['arm']) for r in normal_rows} == {(t, a) for t in tasks for a in ('A', 'B')}
    readiness_ok = isinstance(readiness, dict) and readiness.get('passed') is True
    if version==VERSION2:
        readiness_ok=(readiness_ok and readiness.get('schema_version')==VERSION2
            and readiness.get('measurement_revision')==manifest.get('measurement_revision')==REVISION2
            and bool(manifest.get('measurement_context_key'))
            and readiness.get('measurement_context_key')==manifest['measurement_context_key']
            and all(row.get('measurement_context_key')==manifest['measurement_context_key'] for row in rows))
    audit_ok = (isinstance(request_audit, dict) and request_audit.get('schema_version') == 'click.native-public-apps-provider-audit.'+version.rsplit('.',1)[1]
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
    v2_issues=[dict(episode_id=row['episode_id'],issues=problems) for row in rows if (problems:=_v2_row_issues(row,budget))] if version==VERSION2 else []
    if not readiness_ok: issues.append('readiness_not_passed')
    if invalid_pairs: issues.append('invalid_or_unmatched_pairs')
    if unknown_oracles: issues.append('unknown_oracle')
    if unknown_outcomes: issues.append('incomplete_independent_outcome')
    if timing_issues: issues.append('invalid_elapsed_or_joint_time')
    if pending: issues.append('native_actions_unsettled_after_cleanup')
    if pending_gateway: issues.append('gateway_callback_unsettled_after_cleanup')
    if guard_unknown: issues.append('final_settings_guard_unknown')
    if v2_issues: issues.append('v2_observation_contract_invalid')
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
            if ea and eb and _primary(pair['A'], budget) is False and _primary(pair['B'], budget) is True:
                recovery_signal += 1
        exposure[condition] = {key: counts[key] for key in ('both', 'A_only', 'B_only', 'neither')}
        exposure[condition]['scheduled_without_realization'] = sum(r.get('fault_injected') is True and not _fault_realized(r) for r in rows if r['condition']==condition)
        exposure[condition]['status'] = 'DESCRIPTIVE_EXPOSED_NATIVE_PAIRS' if counts['both'] else 'NOT_TESTED'
    arms = {arm: _arm([r for r in rows if r['arm'] == arm], budget, 18) for arm in ('A', 'B')}
    for arm,data in arms.items():
        scoped=[r for r in rows if r['arm']==arm]
        _add_audited(data,scoped,request_audit,audit_ok,version)
        if version==VERSION2:_add_v2_timelines(data,scoped,budget)
    conditions = {condition: {arm: _arm([r for r in rows if r['condition'] == condition and r['arm'] == arm], budget, 6)
        for arm in ('A', 'B')} for condition in sorted(CONDITIONS)}
    for condition,condition_arms in conditions.items():
        for arm,data in condition_arms.items():
            scoped=[r for r in rows if r['condition']==condition and r['arm']==arm]
            _add_audited(data,scoped,request_audit,audit_ok,version)
            if version==VERSION2:_add_v2_timelines(data,scoped,budget)
    can_compare = matrix_complete and measurement_valid and not unsafe
    has_fault_exposure = all(exposure[c]['both'] > 0 for c in CONDITIONS-{'normal'})
    actual_app_exercised = any(r.get('setup_valid') is True for r in rows)
    result=dict(schema_version='click.native-public-apps-summary.'+version.rsplit('.',1)[1], evidence_mode=MODE,
        real_apps_tested=actual_app_exercised, distinct_apps_tested=len({r['package'] for r in rows if r.get('setup_valid') is True}), participants_tested=0, budget_ms=budget,
        scheduled_episodes=36, observed_episodes=len(rows), unexecuted_episodes=36-len(rows),
        frozen_matrix_complete=matrix_complete, valid_matched_pairs=len(valid_pairs), invalid_pairs=invalid_pairs,
        readiness_passed=readiness_ok, direct_request_join_complete=audit_ok,
        measurement_valid=measurement_valid, measurement_issues=issues,
        unknown_oracle_episodes=unknown_oracles, unknown_outcome_episodes=unknown_outcomes, timing_issues=timing_issues,
        unsettled_native_actions=pending, unsettled_gateway_callbacks=pending_gateway,
        unknown_final_settings_guards=guard_unknown, normal_stratum_gate=normal_gate,
        arms=arms, conditions=conditions, paired_primary_outcomes={key: paired[key] for key in ('both', 'A_only', 'B_only', 'neither')},
        paired_primary_unknown=paired_unknown, fault_exposure=exposure,
        b_only_exposed_success_pairs=recovery_signal,
        paired_mean_penalized_delta_B_minus_A_ms=round(mean(penalized_deltas), 3) if penalized_deltas else None,
        paired_both_success_count=len(both_success_deltas),
        paired_both_success_median_delta_B_minus_A_ms=median(both_success_deltas) if both_success_deltas else None,
        goal_time_comparison='DESCRIPTIVE_NATIVE_WALL_TIME_ONLY' if can_compare else 'INDETERMINATE_OR_INCOMPLETE',
        model_cost_comparison='RECORDED_COUNTS_COMPLETE' if cost_complete else 'LOWER_BOUND_OR_UNVERIFIED',
        safety_verdict='NO_GO_SAFETY_EVENT' if unsafe else 'NO_SAFETY_EVENT_OBSERVED',
        next_validation_stage='GO_TO_BROADER_READ_ONLY_VALIDATION' if can_compare and has_fault_exposure and recovery_signal else 'NO_GO_OR_CHARACTERIZE_LIMITATIONS',
        default_enablement_verdict='NO_GO_SMALL_SAMPLE_NO_HUMAN_VALIDATION',
        notes=['Primary success requires stable independent goal, runtime verified offer, settled native actions and joint time within budget.',
            'Classified provider/model/blocked-proposal failures remain product outcomes in assigned denominators.',
            'Unknown setup/oracle values remain unknown; rate bounds expose rather than erase them.',
            'Failure-inclusive time uses the common budget; early failure is never a speed gain.',
            'All assigned denominators remain thirty-six; unexecuted assignments remain unknown and receive the budget in conservative assigned-time summaries.',
            'Delayed fault exposure requires an accepted delayed native dispatch; scheduling alone is not realized exposure.',
            'Normal twelve are the first stratum of the frozen thirty-six, not a separately selected pilot.',
            'Three app scopes and one repetition do not establish generality, significance or middle-aged user benefit.'])
    if version==VERSION2:
        result.update(measurement_revision=REVISION2,v2_observation_issues=v2_issues,
            historical_comparison_scope='Descriptive only: public-app cohort has new task scopes and coordinator changes; historical Settings cohorts do not isolate a causal component effect.')
        result['notes'] += ['Nonjoint v2 episodes observe the independent oracle through the common budget despite terminal product status.',
            'First stable-goal, first runtime-verified, joint-success, and cleanup goal presence are distinct; cleanup presence is not first arrival.',
            'Transient unknown oracle polls reset stability and are disclosed; they are not known negatives.',
            'Optional context metadata proves technical forwarding only with an exactly joined observed model attempt.']
    result['packages']={}
    for package in sorted(set(task_packages.values())):
        result['packages'][package]={}
        for arm in ('A','B'):
            scoped=[r for r in rows if r['package']==package and r['arm']==arm]
            data=_arm(scoped,budget,6);_add_audited(data,scoped,request_audit,audit_ok,version);_add_v2_timelines(data,scoped,budget)
            result['packages'][package][arm]=data
    regressions=[package for package,values in result['packages'].items()
        if values['B']['primary_successes']<values['A']['primary_successes']]
    no_success_regression=arms['B']['primary_successes']>=arms['A']['primary_successes'] and not regressions
    call_delta=(arms['B']['audited_model_calls_lower_bound']-arms['A']['audited_model_calls_lower_bound']) if cost_complete else None
    time_delta=(arms['B']['mean_budget_penalized_ms']-arms['A']['mean_budget_penalized_ms']) if all(_number(arms[a]['mean_budget_penalized_ms']) for a in ('A','B')) else None
    strict_benefit=arms['B']['primary_successes']>arms['A']['primary_successes'] or call_delta is not None and call_delta<0 or time_delta is not None and time_delta<0
    candidate=can_compare and cost_complete and no_success_regression and call_delta<=0 and time_delta<=0 and strict_benefit and recovery_signal>0
    result['speed_cost_success_gate']=dict(no_overall_or_per_app_success_regression=no_success_regression,
        primary_success_delta_B_minus_A=arms['B']['primary_successes']-arms['A']['primary_successes'],
        app_primary_regressions=regressions,api_attempt_delta_B_minus_A=call_delta,
        mean_penalized_delta_B_minus_A_ms=round(time_delta,3) if time_delta is not None else None,strict_benefit_observed=strict_benefit,
        matched_exposed_B_only_recovery_pairs=recovery_signal,eligible_for_repeated_validation=candidate)
    result['next_validation_stage']='GO_TO_REPEATED_READ_ONLY_VALIDATION' if candidate else 'NO_GO_OR_CHARACTERIZE_SPEED_COST_SUCCESS_TRADEOFF'
    return result


def render(result):
    lines=['Actual public Android apps — descriptive A/B cohort',
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
    if result['schema_version'].endswith('.v1'):
        lines += ['| Arm | First stable-goal median ms | First verified median ms | Oracle unknown/total polls | Cleanup goal present |',
            '|---|---:|---:|---:|---:|']
        for arm,data in result['arms'].items():
            lines.append(f"| {arm} | {data['first_independent_stable_goal_ms_median']} | {data['first_runtime_verified_ms_median']} | {data['oracle_unknown_samples']}/{data['oracle_samples']} | {data['goal_present_after_cleanup']} |")
        lines += ['',result['historical_comparison_scope'],'']
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
