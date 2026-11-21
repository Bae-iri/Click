"""Summarize the model-free closed loop for A's planner-avoiding speed paths."""
import argparse
import json
from pathlib import Path

ARMS = ['base', 'reoffer_prediction', 'memory_first', 'memory_first+remembered_result', 'rebind', 'planned_route', 'all']


def summarize(root):
    rows = [json.loads(line) for line in (root / 'episodes.jsonl').read_text().splitlines()]
    keys = [(r['depth'], r['condition'], r['lookahead'], r['memory_available'], r['arm']) for r in rows]
    if len(set(keys)) != len(keys):
        raise ValueError('Duplicate episode key')
    groups = []
    for repeat in (False, True):
        for condition in ('normal', 'churn', 'prediction_miss'):
            for arm in ARMS:
                g = sorted((r for r in rows if r['arm'] == arm and r['memory_available'] == repeat
                            and r['condition'] == condition and r['lookahead'] == 'accurate'), key=lambda r: r['depth'])
                ok = [r for r in g if r['goal_reached'] and r['verified_offer']]
                groups.append(dict(memory_available=repeat, condition=condition, arm=arm, episodes=len(g),
                    planner_requests_by_depth=[r['planner_requests'] for r in g],
                    planner_requests=sum(r['planner_requests'] for r in g), verified_goal=len(ok),
                    ms_to_verified_by_depth=[r['ms_to_verified_offer'] for r in g],
                    false_verified=sum(r['false_verified'] for r in g), wrong_taps=sum(r['wrong_taps'] for r in g)))
    robustness = [dict(lookahead=r['lookahead'], arm=r['arm'], planner_requests=r['planner_requests'], wrong_taps=r['wrong_taps'],
                       planned_route_proposals=r['proposals_by_source'].get('planned_route', 0),
                       verified_goal=r['goal_reached'] and r['verified_offer']) for r in rows if r['lookahead'] != 'accurate']
    return dict(episodes=len(rows), goal_reached=sum(r['goal_reached'] for r in rows),
        verified_offer=sum(r['verified_offer'] for r in rows), false_verified=sum(r['false_verified'] for r in rows),
        guard_rejections=sum(r['guard_rejections'] for r in rows), listener_errors=sum(r['listener_errors'] for r in rows),
        wrong_taps=sum(r['wrong_taps'] for r in rows), groups=groups, robustness=robustness,
        limitations=['Synthetic app and controlled planner/recall doubles; no device, no Gemini.',
                     'Latency is modeled (planner 4.6 s, recall 0.15 s); counts are exact, times are illustrative.',
                     'Deterministic: one episode per cell; differences are mechanism checks, not statistics.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    root = parser.parse_args().root
    report = summarize(root)
    (root / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    for g in report['groups']:
        print(('repeat' if g['memory_available'] else 'first '), f"{g['condition']:16s}", f"{g['arm']:32s}",
              'calls', g['planner_requests_by_depth'], '=', g['planner_requests'], 'ok', f"{g['verified_goal']}/{g['episodes']}",
              's', [round(m / 1000, 1) for m in g['ms_to_verified_by_depth']])
    print({k: report[k] for k in ('episodes', 'goal_reached', 'verified_offer', 'false_verified', 'guard_rejections', 'listener_errors', 'wrong_taps')})


if __name__ == '__main__':
    main()
