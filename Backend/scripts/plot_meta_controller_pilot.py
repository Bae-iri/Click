"""Plot a complete, validated v4 MetaController pilot summary.

The input must be pilot-summary.json written by summarize_meta_controller_pilot.py
for the frozen 216-episode real-device cohort. This export is descriptive: it
does not infer significance, replace paired comparisons, or impute missing data.
Matplotlib is imported only after argument parsing and summary validation.
"""
import argparse
from hashlib import sha256
import json
from math import isclose, isfinite
from pathlib import Path
import re
import textwrap


ARMS = ('A', 'B', 'C')
CONDITIONS = ('normal', 'no_effect', 'dispatch_delay')
CONDITION_LABELS = ('Normal', 'No effect', 'Dispatch delay')
COLORS = {'A': '#0072B2', 'B': '#D55E00', 'C': '#009E73'}
ARM_LABELS = {'A': 'A: current', 'B': 'B: MC-v0', 'C': 'C: no event extension'}


def _count(value, name, maximum=None):
    if type(value) is not int or value < 0 or maximum is not None and value > maximum:
        raise ValueError(f'Invalid count: {name}')
    return value


def _number(value, name):
    if type(value) not in (int, float) or not isfinite(value):
        raise ValueError(f'Invalid measured number: {name}')
    return value


def _metrics(item, expected_n, name):
    if not isinstance(item, dict) or item.get('trials') != expected_n:
        raise ValueError(f'{name} must contain {expected_n} assigned episodes')
    successes = _count(item.get('verified_successes'), f'{name}.verified_successes', expected_n)
    rate = _number(item.get('success_rate'), f'{name}.success_rate')
    if not isclose(rate, successes / expected_n, abs_tol=1e-10):
        raise ValueError(f'{name} success rate disagrees with its count/denominator')
    elapsed = _number(item.get('mean_penalized_time_s'), f'{name}.mean_penalized_time_s')
    if not 0 <= elapsed <= 15:
        raise ValueError(f'{name} failure-inclusive time is outside the 15-second budget')
    milliseconds = _number(item.get('mean_penalized_time_ms'), f'{name}.mean_penalized_time_ms')
    if not isclose(elapsed * 1000, milliseconds, abs_tol=1e-7):
        raise ValueError(f'{name} seconds and milliseconds disagree')
    offers = _count(item.get('runtime_verified_offers'), f'{name}.runtime_verified_offers', expected_n)
    adjudicated = _count(item.get('adjudicated_verified_offers'), f'{name}.adjudicated_verified_offers', offers)
    _count(item.get('false_verified_offers'), f'{name}.false_verified_offers', adjudicated)
    unknown_offers = _count(item.get('unknown_verified_offers'), f'{name}.unknown_verified_offers', offers)
    if unknown_offers != offers - adjudicated:
        raise ValueError(f'{name} unknown and adjudicated offer counts disagree')
    _count(item.get('unknown_oracle_trials'), f'{name}.unknown_oracle_trials', expected_n)
    setup = item.get('setup_statuses')
    if not isinstance(setup, dict) or not setup or not set(setup) <= {'ready', 'failed', 'error'}:
        raise ValueError(f'{name} requires measured ready/failed/error setup statuses')
    if sum(_count(value, f'{name}.setup_statuses.{status}', expected_n)
           for status, value in setup.items()) != expected_n:
        raise ValueError(f'{name} setup-status counts disagree with its scheduled denominator')


def validate_summary(summary):
    """Reject incomplete/mixed exports; trust the summarizer's raw-grid validation."""
    if not isinstance(summary, dict) or summary.get('schema_version') != 'meta-controller-pilot-summary.v1':
        raise ValueError('Expected a validated meta-controller-pilot-summary.v1 export')
    if summary.get('instrumentation_version') != 'native-navigation.v4':
        raise ValueError('Only the final native-navigation.v4 cohort may be plotted')
    completeness = summary.get('completeness', {})
    expected = dict(expected_episodes=216, actual_episodes=216, complete=True,
                    tasks=12, apps=4, assigned_conditions=3, repetitions=2)
    if any(completeness.get(key) != value for key, value in expected.items()):
        raise ValueError('The complete 216-episode / 12-task / four-app cohort is required')
    if completeness.get('arms') != list(ARMS) or summary.get('budget_s') != 15:
        raise ValueError('Expected A/B/C and the frozen 15-second episode budget')
    if not isinstance(summary.get('run_id'), str) or not summary['run_id'].strip():
        raise ValueError('Missing measured run identity')
    for field in ('manifest', 'normalized_episodes'):
        digest = summary.get('input_sha256', {}).get(field)
        if not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest):
            raise ValueError('Missing validated input-file SHA-256 provenance')
    scope = summary.get('frozen_scope', {})
    for field in ('experiment_patch_sha', 'base_code_revision', 'device_scope', 'configured_model'):
        if not isinstance(scope.get(field), str) or not scope[field].strip():
            raise ValueError(f'Missing frozen scope: {field}')
    overall = summary.get('overall', {})
    if not isinstance(overall, dict) or set(overall) != set(ARMS):
        raise ValueError('Overall summary must contain exactly A/B/C')
    for arm in ARMS:
        _metrics(overall[arm], 72, f'overall.{arm}')
    groups = summary.get('assigned_conditions')
    if not isinstance(groups, list) or len(groups) != 3:
        raise ValueError('Expected three all-assigned condition summaries')
    by_condition = {}
    for group in groups:
        condition = group.get('condition')
        if condition not in CONDITIONS or condition in by_condition:
            raise ValueError('Missing, duplicate or unknown assigned condition')
        metrics = group.get('by_arm', {})
        if not isinstance(metrics, dict) or set(metrics) != set(ARMS):
            raise ValueError(f'{condition} must contain exactly A/B/C')
        for arm in ARMS:
            _metrics(metrics[arm], 24, f'{condition}.{arm}')
        by_condition[condition] = metrics
    for arm in ARMS:
        for field in ('verified_successes', 'runtime_verified_offers', 'adjudicated_verified_offers',
                      'false_verified_offers', 'unknown_verified_offers', 'unknown_oracle_trials'):
            if sum(by_condition[c][arm][field] for c in CONDITIONS) != overall[arm][field]:
                raise ValueError(f'Condition and overall counts disagree: {arm}.{field}')
        for status in ('ready', 'failed', 'error'):
            if sum(by_condition[c][arm]['setup_statuses'].get(status, 0) for c in CONDITIONS) != overall[arm]['setup_statuses'].get(status, 0):
                raise ValueError(f'Condition and overall setup counts disagree: {arm}.{status}')
        condition_mean = sum(by_condition[c][arm]['mean_penalized_time_s'] for c in CONDITIONS) / 3
        if not isclose(condition_mean, overall[arm]['mean_penalized_time_s'], abs_tol=1e-8):
            raise ValueError(f'Condition and overall failure-inclusive time disagree: {arm}')
    return by_condition


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--summary', type=Path, required=True, help='Validated final v4 pilot-summary.json')
    parser.add_argument('--output', type=Path, required=True, help='Output PNG path')
    args = parser.parse_args(argv)
    if args.output.suffix.lower() != '.png':
        parser.error('--output must have a .png suffix')
    try:
        raw = args.summary.read_bytes()
        summary = json.loads(raw)
        groups = validate_summary(summary)
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.error(str(error))

    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        from matplotlib.patches import Patch
        from matplotlib.ticker import PercentFormatter
    except ImportError as error:
        parser.error(f'Matplotlib is required to export the figure: {error}')

    style = {'font.family': 'DejaVu Sans', 'font.size': 10,
             'axes.spines.top': False, 'axes.spines.right': False,
             'axes.edgecolor': '#889096', 'axes.labelcolor': '#263238',
             'text.color': '#263238', 'xtick.color': '#263238', 'ytick.color': '#263238',
             'axes.titleweight': 'bold', 'figure.facecolor': 'white',
             'savefig.facecolor': 'white', 'axes.axisbelow': True}
    with plt.rc_context(style):
        figure, axes = plt.subplots(1, 3, figsize=(16, 8.5), gridspec_kw={'width_ratios': (1.15, 1.15, 1)})
        figure.subplots_adjust(left=.055, right=.985, top=.79, bottom=.36, wspace=.32)
        figure.suptitle('MetaController pilot: 216 scheduled device attempts', y=.97, fontsize=16, fontweight='bold')
        figure.text(.5, .925, 'Frozen v4 | 12 tasks | 4 apps | 2 repetitions | 15 s episode limit',
                    ha='center', fontsize=11)
        figure.legend(handles=[Patch(facecolor=COLORS[a], label=ARM_LABELS[a]) for a in ARMS],
                      loc='upper center', bbox_to_anchor=(.5, .885), ncol=3, frameon=False)
        width = .23
        for arm_index, arm in enumerate(ARMS):
            positions = [index + (arm_index - 1) * width for index in range(3)]
            rates = [groups[c][arm]['success_rate'] * 100 for c in CONDITIONS]
            times = [groups[c][arm]['mean_penalized_time_s'] for c in CONDITIONS]
            axes[0].bar(positions, rates, width, color=COLORS[arm])
            axes[1].bar(positions, times, width, color=COLORS[arm])
            for index, condition in enumerate(CONDITIONS):
                metric = groups[condition][arm]
                axes[0].text(positions[index], rates[index] + 2.5,
                             f'{metric["verified_successes"]}/{metric["trials"]}',
                             ha='center', va='bottom', fontsize=8.5)
                axes[1].text(positions[index], times[index] + .35, f'{times[index]:.1f}',
                             ha='center', va='bottom', fontsize=8.5)
        axes[0].set(title='Independent goals / scheduled attempts', ylabel='Goals / all scheduled attempts')
        axes[0].set_ylim(0, 118)
        axes[0].set_yticks((0, 25, 50, 75, 100))
        axes[0].yaxis.set_major_formatter(PercentFormatter(100))
        axes[1].set(title='Budget-penalized mean time', ylabel='Seconds (24 scheduled attempts per arm / condition)')
        axes[1].set_ylim(0, 18)
        axes[1].set_yticks((0, 5, 10, 15))
        for axis in axes[:2]:
            axis.set_xticks(range(3), CONDITION_LABELS)
            axis.grid(axis='y', color='#E1E6E8', linewidth=.7)

        for position, arm in enumerate(ARMS):
            metric = summary['overall'][arm]
            false, adjudicated = metric['false_verified_offers'], metric['adjudicated_verified_offers']
            if adjudicated:
                rate = false / adjudicated * 100
                axes[2].bar(position, rate, width=.55, color=COLORS[arm])
                axes[2].text(position, rate + 2.5, f'{false}/{adjudicated} offers',
                             ha='center', va='bottom', fontsize=10)
            else:
                axes[2].text(position, 5, 'Not estimable\n0 adjudicated offers',
                             ha='center', va='bottom', fontsize=9, color='#65727A')
        axes[2].set(title='Verified-offer proxy alerts', ylabel='Proxy contradictions / evaluable offers')
        axes[2].set_xticks(range(3), list(ARMS))
        axes[2].set_xlabel('72 scheduled attempts per arm')
        axes[2].set_xlim(-.6, 2.6)
        axes[2].set_ylim(0, 118)
        axes[2].set_yticks((0, 25, 50, 75, 100))
        axes[2].yaxis.set_major_formatter(PercentFormatter(100))
        axes[2].grid(axis='y', color='#E1E6E8', linewidth=.7)

        unknown = '; '.join(f'{a} {summary["overall"][a]["unknown_oracle_trials"]}/72' for a in ARMS)
        unadjudicated = '; '.join(f'{a} {summary["overall"][a]["unknown_verified_offers"]}' for a in ARMS)
        setup_failed = '; '.join(f'{a} {summary["overall"][a]["setup_statuses"].get("failed", 0)}/72' for a in ARMS)
        setup_errors = '; '.join(f'{a} {summary["overall"][a]["setup_statuses"].get("error", 0)}' for a in ARMS)
        notes = [
            'Scheduled attempts include setup failures; these are not agent execution failures. Prepared and strictly matched analyses are in the report.',
            f'Setup failures (status=failed): {setup_failed}. Setup/harness errors (status=error): {setup_errors}.',
            'Unsuccessful/unverifiable attempts receive 15 s as a budget penalty, not their observed execution duration. Dispatch delay postpones native execution by 5.5 s.',
            f'Unknown/ambiguous goal outcomes: {unknown}. Unadjudicated verified offers (excluded from the right-panel denominator): {unadjudicated}.',
            'Descriptive proxy summaries: goal predicates require the postrun validity audit. No equivalence or usability claim; paired task-cluster intervals are in the report.',
        ]
        y = .28
        for note in notes:
            wrapped = textwrap.fill(note, width=165)
            figure.text(.055, y, wrapped, ha='left', va='top', fontsize=8.7, linespacing=1.35)
            y -= .024 * (wrapped.count('\n') + 1) + .012
        figure.text(.055, .025, f'Run: {summary["run_id"]} | Summary SHA-256: {sha256(raw).hexdigest()[:16]}',
                    fontsize=8, color='#65727A')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        figure.savefig(args.output, dpi=300, bbox_inches='tight',
                       metadata={'Software': 'plot_meta_controller_pilot.py',
                                 'Description': 'Descriptive frozen-v4 all-assigned pilot results; source summary SHA-256 ' + sha256(raw).hexdigest()})
        plt.close(figure)
    print(json.dumps({'figure': str(args.output), 'episodes': 216,
                      'instrumentation_version': summary['instrumentation_version'],
                      'summary_sha256': sha256(raw).hexdigest()}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
