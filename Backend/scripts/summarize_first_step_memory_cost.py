"""Summarize the frozen A-policy first-action memory audit, including token coverage."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import mean


def summarize(root):
    rows = [json.loads(line) for line in (root / 'episodes.jsonl').read_text().splitlines()]
    expected = {(t, s, a) for t in ('settings_display', 'settings_notifications')
        for s in ('learn1', 'learn2', 'learn3', 'learn4', 'eval1', 'eval2', 'eval3') for a in 'ABC'}
    actual = {(r['task_id'], r['session'], r['arm']) for r in rows}
    ids = {r['trial_id']: r for r in rows}
    if len(ids) != len(rows):
        raise ValueError('Duplicate trial ID')
    requests = [json.loads(line) for line in (root / 'facade-logs/provider-calls.jsonl').read_text().splitlines()]
    by_trial = defaultdict(list)
    for r in requests:
        by_trial[r['trial_id']].append(r)
    groups = []
    for phase in ('learn', 'eval'):
        for arm in 'ABC':
            group = [r for r in rows if r['arm'] == arm and r['session'].startswith(phase)]
            provider = [p for r in group for p in by_trial[r['trial_id']]]
            if not group:
                continue
            usage = {}
            for field in ('input_tokens', 'output_tokens', 'thinking_tokens', 'total_tokens'):
                known = [r[field] for r in provider if type(r.get(field)) is int]
                usage[field] = dict(reported_sum=sum(known), requests_with_usage=len(known), total_requests=len(provider))
            priced = [r for r in provider if r.get('model') == 'gemini-3.8-flash'
                and type(r.get('input_tokens')) is int and type(r.get('total_tokens')) is int
                and r['total_tokens'] >= r['input_tokens']]
            estimated_usd = sum((r['input_tokens'] * .75 + (r['total_tokens'] - r['input_tokens']) * 3.75) / 1_000_000 for r in priced)
            groups.append(dict(phase=phase, arm=arm, episodes=len(group),
                estimated_standard_usd=round(estimated_usd, 8), priced_requests=len(priced), total_requests=len(provider),
                goal_success=sum(r.get('oracle_success') is True for r in group),
                unknown_oracle=sum(r.get('oracle_success') is None for r in group),
                setup_failures=sum(r.get('setup_status') != 'ready' for r in group),
                confirmed=sum(r.get('user_confirmation') == 'confirmed' for r in group),
                verified_offers=sum(r.get('runtime_verified_offer') is True for r in group),
                false_verified=sum(r.get('false_verified_offer') is True for r in group),
                memory_proposals=sum(sum(v for k, v in r.get('proposals_by_source', {}).items() if k.startswith('memory_')) for r in group),
                memory_approvals=sum(sum(v for k, v in r.get('approvals_by_source', {}).items() if k.startswith('memory_')) for r in group),
                memory_used_episodes=sum(any(v for k, v in r.get('approvals_by_source', {}).items() if k.startswith('memory_')) for r in group),
                recorded_model_calls=sum(r.get('model_calls', 0) for r in provider),
                unknown_call_requests=sum(r.get('model_call_state') == 'unknown' for r in provider),
                provider_errors=sum(r.get('provider_error') is True for r in provider),
                token_usage=usage, mean_failure_inclusive_elapsed_ms=mean(r.get('elapsed_ms', 0) for r in group)))
    return dict(complete=actual == expected and len(rows) == len(expected), episodes=len(rows),
        missing=sorted(expected - actual), unexpected=sorted(actual - expected), groups=groups,
        unmatched_provider_requests=sum(len(v) for k, v in by_trial.items() if k not in ids),
        limitations=['One device, two tasks, three evaluations each; exploratory.',
            'Missing usage is unknown, never zero. Token sums only cover reported metadata.',
            'Learning and evaluation have separate costs; no billed currency is inferred.',
            'Latency is goal-observation elapsed time; confirmation and evidence wait are separate.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    report = summarize(args.root)
    (args.root / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(complete=report['complete'], episodes=report['episodes'], groups=report['groups']), ensure_ascii=False))


if __name__ == '__main__':
    main()
