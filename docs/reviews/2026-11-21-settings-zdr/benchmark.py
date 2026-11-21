"""Live synthetic Settings planner comparison. Run from any directory with backend dependencies."""
import argparse
import json
import os
import sys
from pathlib import Path
from unittest.mock import patch

import httpx
from dotenv import dotenv_values

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]
from app.services import settings_planner as settings
from app.task_graphs import approved_graphs
from scripts.run_settings_comparison import Replay, ReplayStopped

# Qwen always uses its normal shared route. Historical Qwen ZDR arms are data only.
ROUTES = {
    'flash': ('qwen/qwen3.8-flash', None),
    'nano': ('openai/gpt-5.4-nano', 'azure'),
}
ZDR_API = 'https://openrouter.ai/api/v1/endpoints/zdr'
INPUT_CAP, OUTPUT_CAP = 0.20, 2.00  # USD per million tokens


def check_endpoints(arms):
    if not any(ROUTES[arm][1] for arm in arms):
        return
    response = httpx.get(ZDR_API, timeout=15, follow_redirects=False, trust_env=False)
    response.raise_for_status()
    endpoints = response.json()['data']
    for arm in arms:
        model, tag = ROUTES[arm]
        if not tag:
            continue
        matches = [e for e in endpoints if e['model_id'] == model and e['tag'] == tag
                   and e['status'] == 0 and e.get('safety_retention') is False
                   and 'structured_outputs' in e['supported_parameters']]
        if len(matches) != 1:
            raise RuntimeError(f'Exact ZDR endpoint unavailable: {model}/{tag}')


class MeasuredPlanner(settings.SettingsPlanner):
    def __init__(self, arm):
        super().__init__(provider='openrouter')
        self.model, self.endpoint = ROUTES[arm]
        self.calls = []

    def plan(self, **context):
        # Only override the pinned model in this experiment process. Keep the app parser.
        with patch.object(settings, 'settings_planner_model', return_value=self.model):
            return super().plan(**context)

    def request(self, payload, token, diagnostic):
        payload['reasoning'] = {'effort': 'none'}
        prefs = {'max_price': {'prompt': INPUT_CAP, 'completion': OUTPUT_CAP}}
        if self.endpoint:
            prefs.update(only=[self.endpoint], zdr=True, allow_fallbacks=False, require_parameters=True)
        payload['provider'] = prefs
        if len(json.dumps(payload, ensure_ascii=False).encode()) > 3500 or payload['max_output_tokens'] > 192:
            raise RuntimeError('Case exceeded the experiment size limit')
        response = super().request(payload, token, diagnostic)
        usage = response.json().get('usage', {}) if response is not None else {}
        self.calls.append({k: usage.get(k) for k in ('input_tokens', 'output_tokens', 'cost')})
        return response


def cases():
    graphs = approved_graphs()
    result = []
    for goal in sorted(settings.GOALS):
        graph = graphs[goal]
        targets, _ = settings.catalogue(graph)
        # Gold comes from the reviewed native transitions. The model only sees the
        # unordered capabilities, never these edges or the expected route.
        state = 'S1_SETTINGS_HOME'
        states, route = [state], []
        while True:
            edge, = [e for e in graph.transitions if e.from_state == state]
            if edge.completes_task:
                route.append('adjust_setting')
                break
            state = edge.to_state
            states.append(state)
            route.append(settings.navigation_key(state))
        for kind in ('home', 'replan' if goal.endswith('increase') else 'control'):
            pos = {'home': 0, 'replan': 1, 'control': len(states) - 1}[kind]
            page = states[pos]
            context = dict(goal=goal, targets=targets, current_page=graph.states[page].name,
                           visible_targets=[route[pos]], completed=route[:pos], failures=[])
            if kind == 'replan':
                context['failures'] = [{'subgoal': route[pos], 'status': 'NO_CHANGE'}] * 2
            result.append((goal, kind, context, route[pos:], settings.navigation_key(page)))
    return result


def append(path, row):
    with path.open('a', encoding='utf-8') as file:
        file.write(json.dumps(row, ensure_ascii=False) + '\n')
    print(row['arm'], row['goal'], row['case'], row['success'], row.get('latency_ms'), flush=True)


def benchmark(output, arms, reverse):
    fixtures = cases()
    if reverse:
        fixtures.reverse()
    for index, (goal, kind, context, expected, current) in enumerate(fixtures):
        for arm in arms[index % len(arms):] + arms[:index % len(arms)]:
            planner = MeasuredPlanner(arm)
            plan, diag = planner.plan(**context)
            steps = plan['subgoals'] if plan else []
            # The native runtime skips navigation to the screen it already occupies.
            effective = steps[1:] if steps[:1] == [current] else steps
            append(output, dict(arm=arm, model=planner.model, endpoint=planner.endpoint,
                                goal=goal, case=kind, expected=expected, returned=steps,
                                success=effective == expected, reason=diag.get('reason_code'),
                                http=diag.get('attempts', []), latency_ms=diag['latency_ms'],
                                usage=planner.calls))


def metered_jev(resolver, usage):
    def send(payload, **kwargs):
        response = resolver._post(payload, **kwargs)
        if response.status_code == 200:
            body = response.json()
            counters = body.get('usage') or body
            usage.append({k: counters[k] for k in ('input_tokens', 'prompt_tokens', 'total_tokens', 'tokens')
                          if type(counters.get(k)) is int})
        return response
    return send


def integrated(output, arms, goals):
    for index, goal in enumerate(goals):
        for arm in arms[index % len(arms):] + arms[:index % len(arms)]:
            replay = Replay(goal, 'llm', 'live')
            planner = MeasuredPlanner(arm)
            replay.resolver.planner = planner
            jev_usage = []
            replay.resolver.inner._sender = metered_jev(replay.resolver.inner, jev_usage)
            stop = None
            try:
                replay.run()
            except ReplayStopped as error:
                stop = str(error)
            last = replay.records[-1]['verification']['final']
            jev = [r['jev'] for r in replay.records if r['jev'].get('called')]
            append(output, dict(arm=arm, model=planner.model, endpoint=planner.endpoint,
                                goal=goal, case='live_jev_synthetic_screen',
                                success=last['status'] == 'SUCCESS', verification=last, stop=stop,
                                planner=[r['planner'] for r in replay.records if r['planner'].get('called')],
                                usage=planner.calls, jev_calls=jev, jev_usage=jev_usage))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='New JSONL file (refuses to overwrite)')
    parser.add_argument('--arms', nargs='+', choices=list(ROUTES), default=['flash', 'nano'])
    parser.add_argument('--reverse', action='store_true', help='Reverse fixture order for the second pass')
    parser.add_argument('--integrated', action='store_true', help='Run real Jev on synthetic Android screens')
    parser.add_argument('--goals', nargs='+', choices=sorted(settings.GOALS), default=sorted(settings.GOALS))
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Choose a new output path')
    if len(set(args.arms)) != len(args.arms) or len(set(args.goals)) != len(args.goals):
        parser.error('Duplicate arms or goals')
    env = dotenv_values(ROOT / 'Backend/.env')
    for key in ('OPENROUTER_API_KEY', 'TYPESAFE_API_KEY'):
        if env.get(key) and not os.environ.get(key):
            os.environ[key] = env[key]
    os.environ.update(CLICK_SETTINGS_PLANNER_PROVIDER='openrouter', CLICK_SETTINGS_ROUTE_REUSE_ENABLED='0',
                      JEV_MODEL='jev-1.14.0', AI1_UI_SELECTOR_PROVIDER='jev')
    if not os.getenv('OPENROUTER_API_KEY') or (args.integrated and not os.getenv('TYPESAFE_API_KEY')):
        parser.error('Required provider credential is missing')
    check_endpoints(args.arms)
    if args.integrated:
        integrated(args.output, args.arms, args.goals)
    else:
        benchmark(args.output, args.arms, args.reverse)


if __name__ == '__main__':
    main()
