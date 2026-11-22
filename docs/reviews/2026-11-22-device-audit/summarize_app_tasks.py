"""Combine resumed native app-task runs without counting interrupted rows twice."""
import argparse
import json
import statistics
from collections import Counter
from pathlib import Path


def complete(row):
    return bool(row.get('finished') or 'native_goal' in row or row.get('failure'))


def timing(rows, key):
    values = sorted(r[key] for r in rows if key in r)
    return {'n': len(values), 'median_ms': statistics.median(values) if values else None,
            'p95_ms': values[min(len(values)-1, round(.95*(len(values)-1)))] if values else None}


def summarize(rows):
    done = [r for r in rows if complete(r)]
    return {'finished': len(done), 'success': sum(bool(r.get('passed')) for r in done),
            'native_destination': sum(bool(r.get('native_goal') or r.get('goal_reached_ms')) for r in done),
            'native_destination_at_end': sum(bool(r.get('native_goal')) for r in done),
            'false_completion': sum(bool(r.get('false_completion')) for r in done),
            'first_action_proposal': timing(done, 'first_action_ms'),
            'verified_completion_successes': timing([r for r in done if r.get('passed')], 'verified_completion_ms'),
            'last_status_failures': dict(Counter(r['events'][-1]['status'] if r.get('events') else 'NO_EVENT'
                                                for r in done if not r.get('passed'))),
            'by_task': {task: {'trials': len(sample), 'passed': sum(bool(r.get('passed')) for r in sample),
                              'false_completion': sum(bool(r.get('false_completion')) for r in sample)}
                        for task in sorted({r['task'] for r in done})
                        for sample in [[r for r in done if r['task'] == task]]},
            'failures': dict(Counter(r.get('failure_code') or r.get('failure') or 'TIMEOUT'
                                    for r in done if not r.get('passed')))}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('parts', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    rows = {}
    interrupted = 0
    for part in args.parts:
        for row in json.loads(part.read_text())['cases']:
            if not complete(row):
                interrupted += 1
                continue
            rows[(row['repeat'], row['task'])] = row
    cases = list(rows.values())
    result = {'schema_version': 'click.app-task-audit-summary.v1',
              'sources': [p.name for p in args.parts],
              'condition': 'One Galaxy Note20, scripted approvals, independent native oracle; real production API',
              'speed_definition': 'First proposed action, not first rendered guide; failures without a proposal excluded from timing only',
              'interrupted_rows_excluded': interrupted,
              'all': summarize(cases),
              'settings_without_graph': summarize([r for r in cases if r['task'].startswith('settings_')]),
              'apps': summarize([r for r in cases if not r['task'].startswith('settings_')]),
              'cases': cases}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='cases'}, ensure_ascii=False, indent=2))
