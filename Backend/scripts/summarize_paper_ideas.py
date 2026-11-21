"""Summarize the paper-ideas B vs C device run (docs/reviews/2026-11-21-paper-ideas/protocol.md)."""
import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip()]


def summarize(episodes, calls, recalls):
    by_arm = defaultdict(list)
    for e in episodes:
        by_arm[e['arm']].append(e)
    calls_by_arm = defaultdict(list)
    for c in calls:
        calls_by_arm[c.get('arm')].append(c)
    out = {}
    for arm, eps in sorted(by_arm.items()):
        cs = calls_by_arm[arm]
        def part(pred):
            sub = [e for e in eps if pred(e)]
            return dict(
                episodes=len(sub),
                goal_observed=sum(e.get('oracle_success') is True for e in sub),
                runtime_verified=sum(e.get('runtime_verified_offer') is True for e in sub),
                false_verified=sum(e.get('false_verified_offer') is True for e in sub),
                setup_failures=sum(e.get('setup_status') != 'ready' for e in sub),
                model_calls=sum(e.get('observed_model_calls') or 0 for e in sub),
                mean_elapsed_ms=round(sum(e.get('elapsed_ms') or 0 for e in sub) / len(sub)) if sub else None,
                verified_after_goal_ms=sorted(e['runtime_verified_after_goal_ms'] for e in sub
                                              if isinstance(e.get('runtime_verified_after_goal_ms'), (int, float))),
                proposals_by_source=dict(sum((Counter(e.get('proposals_by_source') or {}) for e in sub), Counter())),
                changed_frame_discards=sum(e.get('changed_frame_discards') or 0 for e in sub),
                changed_frame_rebinds=sum(e.get('changed_frame_rebinds') or 0 for e in sub),
                memory_ingested=sum(e.get('experience_ingested') is True for e in sub),
            )
        out[arm] = dict(
            all=part(lambda e: True),
            learn=part(lambda e: e['session'].startswith('learn')),
            eval=part(lambda e: e['session'].startswith('eval')),
            by_task={t: part(lambda e, t=t: e['task_id'] == t) for t in sorted({e['task_id'] for e in eps})},
            server=dict(
                requests=len(cs),
                model_calls=sum(c.get('model_calls') or 0 for c in cs),
                provider_errors=sum(c.get('provider_error') is True for c in cs),
                completion_triage=sum(c.get('reason_code') == 'completion_triage' for c in cs),
                completion_bound=sum(c.get('completion_bound') is True for c in cs),
                input_tokens=sum(c.get('input_tokens') or 0 for c in cs),
                output_tokens=sum(c.get('output_tokens') or 0 for c in cs),
                calls_with_usage=sum(c.get('input_tokens') is not None for c in cs),
                planning_modes=dict(Counter(c.get('planning_mode') for c in cs)),
            ),
            recall=dict(Counter(v.get('status') for r in recalls if r.get('arm') == arm
                                for name, v in (r.get('variants') or {}).items() if name == 'exact')),
        )
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--episodes', type=Path, required=True)
    parser.add_argument('--logs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    calls = rows(args.logs / 'provider-calls.jsonl') if (args.logs / 'provider-calls.jsonl').exists() else []
    recalls = rows(args.logs / 'recalls.jsonl') if (args.logs / 'recalls.jsonl').exists() else []
    summary = summarize(rows(args.episodes), calls, recalls)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
