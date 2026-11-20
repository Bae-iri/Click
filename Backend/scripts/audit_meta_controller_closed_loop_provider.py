"""Reconcile separate provider diagnostics with a sequential synthetic live run.

The frozen first runner did not persist the controller task-id hash in episode
rows. Association is therefore ordinal, backed by contiguous provider episode
groups, exact request counts, unique requests and matching model-call totals.
This cannot prove a direct request-id join and must not conceal that limitation.
"""
import argparse
import json
import math
from pathlib import Path


def audit(episodes, provider):
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
