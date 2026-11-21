"""Summarize the opt-in MC x memory device interaction audit, without pooling training."""
import argparse
from collections import defaultdict
import json
from pathlib import Path


def summarize(root):
    groups = defaultdict(list)
    runs = {}
    matrix = {}
    for name in ('live-current', 'live-meta'):
        path = root / name / 'episodes.jsonl'
        if not path.exists():
            continue
        rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        if len({r['trial_id'] for r in rows}) != len(rows):
            raise ValueError('duplicate trial id')
        runs[name] = len(rows)
        expected = {(task, session, arm) for task in ('settings_display', 'settings_notifications')
            for session in ('learn1', 'learn2', 'eval_normal', 'eval_no_effect', 'eval_delayed') for arm in ('A', 'B')}
        actual = {(r['task_id'], r['session'], r['arm']) for r in rows}
        matrix[name] = dict(complete=actual == expected and len(rows) == len(expected),
            missing=sorted(expected - actual), unexpected=sorted(actual - expected))
        for row in rows:
            phase = 'train' if row['session'].startswith('learn') else 'evaluation'
            # Setup failures remain in their assigned cell and denominator.
            meta = row.get('meta_arm', 'CURRENT' if name == 'live-current' else 'META')
            condition = row.get('condition', {'eval_no_effect': 'accepted_no_effect',
                'eval_delayed': 'delayed_transition'}.get(row['session'], 'normal'))
            groups[(meta, row['memory_enabled'], phase, condition)].append(row)
    cells = []
    for (meta, memory, phase, condition), rows in sorted(groups.items()):
        cells.append(dict(meta=meta, memory=memory, phase=phase, condition=condition,
            episodes=len(rows), goal_success=sum(r.get('oracle_success') is True for r in rows),
            unknown_oracle=sum(r.get('oracle_success') is None for r in rows),
            verified_offers=sum(r.get('runtime_verified_offer') is True for r in rows),
            false_verified=sum(r.get('false_verified_offer') is True for r in rows),
            unknown_false_verified=sum(r.get('false_verified_offer') is None for r in rows),
            setup_failures=sum(r.get('setup_status') != 'ready' for r in rows),
            memory_proposals=sum(sum(v for k, v in r.get('proposals_by_source', {}).items()
                if k.startswith('memory_')) for r in rows),
            fault_exposed=sum(r.get('fault_injected') is True for r in rows),
            delayed_dispatch_accepted=sum(r.get('delayed_dispatch_accepted') is True for r in rows),
            recorded_model_calls=sum(r.get('observed_model_calls', 0) for r in rows),
            recoveries=sum(r.get('recovery_count', 0) for r in rows)))
    provider = {}
    for name in runs:
        path = root / name / 'facade-logs' / 'provider-calls.jsonl'
        rows = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        provider[name] = dict(terminal_requests=len(rows),
            recorded_model_calls=sum(r.get('model_calls', 0) for r in rows),
            provider_errors=sum(r.get('provider_error') is True for r in rows))
    return dict(runs=runs, complete=set(matrix) == {'live-current', 'live-meta'} and all(v['complete'] for v in matrix.values()), matrix=matrix, cells=cells,
        provider=provider, limitations=[
            'Exploratory two-task device audit; one observation per evaluation cell and task.',
            'CURRENT then META and fixed evaluation order; no population efficacy inference.',
            'Memory action proposals must be observed before claiming a memory interaction effect.',
            'Device diagnostic model-call counts and facade counts are reported separately.',
            'Controlled synthetic results are separate from real-device results.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    report = summarize(args.root)
    (args.root / 'device-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
