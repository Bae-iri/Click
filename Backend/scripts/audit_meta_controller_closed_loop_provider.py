"""Reconcile separate provider diagnostics with a sequential synthetic live run.

The frozen first runner did not persist the controller task-id hash in episode
rows. Association is therefore ordinal, backed by contiguous provider episode
groups, exact request counts, unique requests and matching model-call totals.
This cannot prove a direct request-id join and must not conceal that limitation.
"""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re


def _audit_legacy(episodes, provider):
    if not episodes or any(r.get('evidence_mode') != 'live_model_synthetic_closed_loop' for r in episodes):
        raise ValueError('A live synthetic run is required')
    issues = []
    groups = []
    seen_episodes, seen_requests = set(), set()
    for record in provider:
        ref, request = record.get('episode_ref'), record.get('request_ref')
        if record.get('evidence_mode') != 'live_model_synthetic_closed_loop':
            issues.append('provider_evidence_mode_mismatch')
        if not ref or not request or request in seen_requests:
            issues.append('missing_or_duplicate_provider_request_ref')
        seen_requests.add(request)
        if not groups or groups[-1][0] != ref:
            if ref in seen_episodes:
                issues.append('noncontiguous_provider_episode')
            groups.append((ref, []))
            seen_episodes.add(ref)
        groups[-1][1].append(record)
    nonzero = [r for r in episodes if r.get('planner_requests', 0) > 0]
    if len(groups) != len(nonzero):
        issues.append('provider_episode_group_count_mismatch')
    joins = []
    for row, (ref, records) in zip(nonzero, groups):
        calls = sum(r.get('model_calls', 0) for r in records)
        waits = [r.get('model_wait_ms') for r in records]
        bounded = all(isinstance(v, (int, float)) and math.isfinite(v) and v >= 0 for v in waits)
        wait = sum(waits) if bounded else None
        errors = []
        if len(records) != row['planner_requests']:
            errors.append('planner_request_count_mismatch')
        if calls != row.get('actual_model_calls', 0):
            errors.append('actual_model_call_count_mismatch')
        if wait is None or abs(wait - row.get('actual_model_wait_ms', 0)) > max(.01, .001 * len(records)):
            errors.append('actual_model_wait_mismatch')
        if row.get('gateway_errors', 0):
            errors.append('gateway_error_prevents_unambiguous_ordinal_join')
        if errors:
            issues.extend(errors)
        joins.append(dict(pair_id=row['pair_id'], arm=row['arm'], provider_episode_ref=ref,
            planner_requests=row['planner_requests'], provider_records=len(records),
            model_calls=calls, model_wait_ms=round(wait, 3) if wait is not None else None,
            provider_errors=sum(r.get('provider_error') is True for r in records),
            plan_or_parse_errors=sum(r.get('plan_or_parse_error') is True for r in records),
            ordinal_checks_passed=not errors))
    total_requests = sum(r.get('planner_requests', 0) for r in episodes)
    total_calls = sum(r.get('actual_model_calls', 0) for r in episodes)
    provider_calls = sum(r.get('model_calls', 0) for r in provider)
    if total_requests != len(provider):
        issues.append('global_request_count_mismatch')
    if total_calls != provider_calls:
        issues.append('global_model_call_count_mismatch')
    return dict(schema_version='click.synthetic-provider-audit.v1',
        status='ORDINAL_RECONCILED_WITH_EXPLICIT_LIMITATION' if not issues else 'INDETERMINATE_OR_MISMATCHED',
        direct_request_id_join_available=False,
        method='Sequential episode order + contiguous provider episode_ref groups + exact planner request and model call/wait counts',
        limitation='Episode rows lack controller task/request hashes; ordinal association is not an independently keyed join.',
        episodes=len(episodes), provider_episode_groups=len(groups), planner_requests=total_requests,
        provider_records=len(provider), episode_model_calls=total_calls, provider_model_calls=provider_calls,
        provider_model_wait_ms=round(sum(r.get('model_wait_ms') or 0 for r in provider), 3),
        provider_errors=sum(r.get('provider_error') is True for r in provider),
        plan_or_parse_errors=sum(r.get('plan_or_parse_error') is True for r in provider),
        actual_models=sorted({r['model'] for r in provider if r.get('model')}),
        issues=sorted(set(issues)), ordinal_episode_associations=joins,
        efficiency_inference_permitted=False,
        note='Provider errors remain in assigned episode results. This audit alone never authorizes efficiency or real-app claims.')


def _valid_ref(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{24}', value)


def _bounded_wait(value):
    return type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 120000


def _audit_direct(episodes, provider):
    """Join complete terminal outcomes by request identity, including rejections."""
    if not episodes or any(r.get('evidence_mode') != 'live_model_synthetic_closed_loop' for r in episodes):
        raise ValueError('A live synthetic run is required')
    issues, expected, actual, associations = [], {}, {}, []
    for row in episodes:
        traces = row.get('request_traces')
        if not isinstance(traces, list):
            issues.append('missing_direct_request_traces')
            continue
        if len(traces) != row.get('planner_requests'):
            issues.append('planner_trace_count_mismatch')
        observed = 0
        for trace in traces:
            key = trace.get('request_ref')
            if not _valid_ref(key) or not _valid_ref(trace.get('task_ref')):
                issues.append('invalid_client_reference')
                continue
            if key in expected:
                issues.append('duplicate_client_request_ref')
            if trace['task_ref'] != row.get('task_ref'):
                issues.append('client_task_ref_mismatch')
            expected[key] = (row, trace)
            if trace.get('model_call_state') == 'observed':
                observed += 1
        if observed != row.get('actual_model_calls', 0):
            issues.append('client_observed_call_count_mismatch')
    server_refs = set()
    for record in provider:
        key = record.get('request_ref')
        if record.get('schema_version') != 'local-meta-controller-synthetic-facade.v2' or record.get('event') != 'request_terminal':
            issues.append('invalid_terminal_record_schema')
        if record.get('evidence_mode') != 'live_model_synthetic_closed_loop':
            issues.append('provider_evidence_mode_mismatch')
        server = record.get('server_ref')
        if not _valid_ref(server) or server in server_refs:
            issues.append('missing_or_duplicate_server_ref')
        server_refs.add(server)
        if not _valid_ref(key):
            issues.append('unidentified_terminal_record')
            continue
        if key in actual:
            issues.append('duplicate_provider_request_ref')
        actual[key] = record
    missing, orphan = sorted(set(expected) - set(actual)), sorted(set(actual) - set(expected))
    if missing:
        issues.append('missing_terminal_records')
    if orphan:
        issues.append('orphan_terminal_records')
    unknown = set(missing)
    known_calls, known_wait = 0, 0
    timing_unknown = False
    for key, record in actual.items():
        state, calls, wait = record.get('model_call_state'), record.get('model_calls'), record.get('model_wait_ms')
        if state == 'observed' and calls == 1:
            known_calls += 1
            if _bounded_wait(wait):
                known_wait += wait
            else:
                timing_unknown = True
        elif state == 'not_started' and calls == 0:
            pass
        elif state == 'unknown' and calls is None:
            unknown.add(key)
        else:
            issues.append('invalid_model_call_measurement')
            unknown.add(key)
        if key not in expected:
            continue
        row, trace = expected[key]
        problems = []
        if record.get('task_ref') != trace.get('task_ref'):
            problems.append('request_task_ref_mismatch')
        if trace.get('server_ref') and trace['server_ref'] != record.get('server_ref'):
            problems.append('server_ref_mismatch')
        if trace.get('http_status') is not None and trace.get('http_status') != record.get('http_status'):
            problems.append('http_status_mismatch')
        if trace.get('model_call_state') == 'observed':
            if state != 'observed' or trace.get('model_calls') != 1:
                problems.append('model_call_state_mismatch')
            if not _bounded_wait(trace.get('model_wait_ms')) or not _bounded_wait(wait) or abs(trace['model_wait_ms'] - wait) > .01:
                problems.append('model_wait_mismatch')
        elif trace.get('model_call_state') == 'not_started' and state != 'not_started':
            problems.append('model_call_state_mismatch')
        issues.extend(problems)
        associations.append(dict(pair_id=row.get('pair_id'), arm=row.get('arm'), request_ref=key,
            task_ref=trace['task_ref'], server_ref=record.get('server_ref'), http_status=record.get('http_status'),
            error_code=record.get('error_code'), model_call_state=state, model_calls=calls,
            service_failure_kind=record.get('service_failure_kind'),
            service_failure_stage=record.get('service_failure_stage'), service_reason_code=record.get('service_reason_code'),
            response_delivery=record.get('response_delivery'), direct_checks_passed=not problems))
    joined = not issues
    complete_calls = joined and not unknown
    status = ('INDETERMINATE_OR_MISMATCHED' if not joined else
        'DIRECT_JOIN_COMPLETE_MODEL_COUNTS_UNKNOWN' if unknown else
        'DIRECT_JOIN_COMPLETE_TIMING_UNKNOWN' if timing_unknown else 'DIRECT_JOIN_RECONCILED')
    return dict(schema_version='click.synthetic-provider-audit.v2', status=status,
        direct_request_id_join_available=True, direct_join_complete=joined,
        method='Exact SHA256-prefix request_ref and task_ref joins; one terminal record per received attempt',
        episodes=len(episodes), planner_requests=sum(r.get('planner_requests', 0) for r in episodes),
        client_request_traces=len(expected), provider_records=len(provider), matched_request_records=len(associations),
        episode_model_calls=sum(r.get('actual_model_calls', 0) for r in episodes), provider_model_calls=known_calls,
        provider_model_wait_ms=round(known_wait, 3), model_call_count_complete=complete_calls,
        model_wait_measurement_complete=complete_calls and not timing_unknown,
        unknown_model_call_requests=len(unknown), missing_request_refs=missing, orphan_request_refs=orphan,
        provider_errors=sum(r.get('provider_error') is True for r in provider),
        plan_or_parse_errors=sum(r.get('plan_or_parse_error') is True for r in provider),
        rejected_requests=sum(r.get('outcome') == 'rejected' for r in provider),
        service_exceptions=sum(r.get('service_state') == 'raised' for r in provider),
        delivery_failures=sum(r.get('response_delivery') != 'sent' for r in provider),
        http_status_counts=dict(Counter(str(r.get('http_status')) for r in provider)),
        rejection_reason_counts=dict(Counter(r['error_code'] for r in provider if r.get('error_code'))),
        actual_models=sorted({r['model'] for r in provider if r.get('model')}),
        issues=sorted(set(issues)), request_associations=associations, efficiency_inference_permitted=False,
        note='Known calls are a lower bound when any attempt is unknown. Complete joining does not imply healthy model results or real-app efficacy.')


def audit(episodes, provider):
    if any('request_traces' in row for row in episodes) or any(r.get('schema_version') == 'local-meta-controller-synthetic-facade.v2' for r in provider):
        return _audit_direct(episodes, provider)
    return _audit_legacy(episodes, provider)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--episodes', type=Path, required=True)
    parser.add_argument('--provider', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    episodes = [json.loads(s) for s in args.episodes.read_text().splitlines() if s.strip()]
    provider = [json.loads(s) for s in args.provider.read_text().splitlines() if s.strip()]
    result = audit(episodes, provider)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ('status', 'episodes', 'provider_records', 'provider_model_calls', 'provider_errors', 'issues')}))


if __name__ == '__main__':
    main()
