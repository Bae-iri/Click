"""Write the NOT_RUN protocol, or analyze measured MetaController episode JSONL.

No model, device or network is simulated by this command. Measurements must be
exported by a device driver/product runner and carry their original provenance.
"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.services.meta_controller_experiment import evaluate, protocol


def render_report(report):
    lines = ['# MetaController episode evaluation', '',
        f'Measured episodes: {report["episode_count"]}', '',
        f'Deployment: **{report["go_no_go"]["deployment"]}** — {report["go_no_go"]["reason"]}',
        f'Next stage: **{report["go_no_go"]["experiment_continuation"]}**', '']
    for group in report['evidence_groups']:
        lines += [f'## {group["source"]} / {group["planner_arm"]} / {group["driver_mode"]}', '',
            '| Arm | Episodes | Oracle-verified successes | Failure-inclusive mean time (ms) | Verified offers (measured episodes) | False verified offers (adjudicated offers) | Unknown offers | False DONE | Model calls (measured episodes) |',
            '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
        for arm, m in group['by_arm'].items():
            if not m['trials']:
                continue
            calls = 'unmeasured' if m['model_calls_total'] is None else str(m['model_calls_total'])
            lines.append(f'| {arm} | {m["trials"]} | {m["verified_successes"]} | '
                         f'{m["mean_penalized_time_ms"]:.3f} | {m["runtime_verified_offers"]} ({m["runtime_verified_offer_measured_trials"]}) | '
                         f'{m["false_verified_offers"]} ({m["adjudicated_verified_offers"]}) | {m["unknown_verified_offers"]} | {m["false_done"]} | '
                         f'{calls} ({m["model_calls_measured_trials"]}) |')
        lines.append('')
        for comparison in group['paired']:
            if not comparison['paired_episodes']:
                continue
            lines += [f'{comparison["right"]} − {comparison["left"]}: '
                f'{comparison["paired_episodes"]} matched episodes, '
                f'{comparison["task_clusters"]} task clusters.',
                '```json', json.dumps(comparison['difference_right_minus_left'], indent=2), '```',
                f'Cluster bootstrap 95% CI: `{json.dumps(comparison["cluster_bootstrap_95ci"])}`', '']
        lines += ['Condition-level results are retained in `comparison.json`; evidence sources are never pooled.', '']
    lines += ['## Interpretation limits', ''] + ['- ' + item for item in report['limitations']]
    return '\n'.join(lines) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--plan', action='store_true')
    mode.add_argument('--input', type=Path, help='Measured episode JSONL, never the NOT_RUN plan')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--app-tasks', type=Path, help='JSON list of 12 preselected {task_id, app_id} templates')
    parser.add_argument('--include-ablation', action='store_true')
    parser.add_argument('--seed', type=int, default=20261120)
    parser.add_argument('--bootstrap', type=int, default=2000)
    args = parser.parse_args(argv)
    if not args.plan and (args.app_tasks or args.include_ablation):
        parser.error('--app-tasks and --include-ablation require --plan')
    try:
        if args.plan:
            tasks = json.loads(args.app_tasks.read_text()) if args.app_tasks else None
            report = protocol(seed=args.seed, app_tasks=tasks, include_ablation=args.include_ablation)
            name = 'protocol.json'
        else:
            records = [json.loads(line) for line in args.input.read_text().splitlines() if line.strip()]
            report = evaluate(records, bootstrap=args.bootstrap, seed=args.seed)
            name = 'comparison.json'
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / name).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if not args.plan:
        (args.output_dir / 'comparison.md').write_text(render_report(report), encoding='utf-8')
    print(json.dumps(dict(report=str(args.output_dir / name),
        status=report.get('decision') or report['go_no_go']), ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
