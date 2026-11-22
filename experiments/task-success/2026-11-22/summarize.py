"""Join per-trial native outcomes with the application server's attributable calls."""
import argparse
import collections
import json
import re
from pathlib import Path
import statistics

FIRST = {
    'display': {'디스플레이'}, 'wifi': {'연결', 'Wi-Fi', '와이파이'},
    'clock_stopwatch': {'스톱워치'}, 'clock_timer': {'타이머'},
    'chrome_menu': {'Chrome 맞춤설정 및 제어'}, 'chrome_settings': {'Chrome 맞춤설정 및 제어'},
    'youtube_shorts': {'Shorts'}, 'youtube_search': {'검색', 'YouTube 검색'}, 'coupang_category': {'카테고리'},
}


def describe(phase, raw, calls):
    rep, arm, task = phase.split('-', 2)
    group = 'forced' if task.startswith('settings_') else 'registered' if arm in ('graph', 'commerce') else 'fallback'
    if arm == 'commerce':
        group = 'harness_setup'
    if arm in ('terminal','auto','arrival','routecheck','entry'):
        group = arm
    key = task.removeprefix('settings_')
    resolved = [r for r in calls if r['command'] == 'resolve_goal']
    turns = [r for r in calls if r['path'] == 'task']
    planner = [r for r in turns if r['diagnostic'].get('model') == 'qwen/qwen3.8-flash']
    proposed = next((r for r in planner if r['diagnostic'].get('called') is True and r['action']), None)
    plan = next((r['subgoals'] for r in planner if r['subgoals'] and r['diagnostic'].get('called') is True), [])
    ui = [e for e in raw.get('events', []) if 'approved' in e]
    label = proposed['label'] if proposed else None
    scored = None
    if proposed and proposed['action'] == 'tap' and label and label != 'other':
        scored = label in FIRST[key]
    row = dict(trial=phase, group=group, task=task, repeat=int(rep),
               goal=raw.get('input') or raw.get('goal'),
               resolution=[dict(status=r['resolution'],goal_type=r['typed_goal'],ms=r['elapsed_ms']) for r in resolved],
               accepted_goal_type=raw.get('accepted_goal_type'), app_controller_ms=raw.get('app_controller_ms'),
               planner_http_confirmed_calls=sum(r['diagnostic'].get('called') is True for r in planner),
               planner_call_state_unknown=sum(r['diagnostic'].get('called') is None for r in planner),
               first_plan=plan, first_qwen_tool=proposed['action'] if proposed else None,
               first_qwen_label=label, first_qwen_tap_correct=scored,
               first_overlay_target_correct=raw.get('first_action_correct'),
               first_overlay_target_ms=raw.get('first_visible_guidance_ms'),
               target_ever_reached='goal_reached_ms' in raw, target_at_end=raw.get('native_goal'),
               first_arrival_observed_ms=raw.get('goal_reached_ms'), passed=raw['passed'],
               false_completion=raw.get('false_completion',False),
               completion_ms=raw.get('verified_completion_ms') or (raw.get('result_prompt_ms') if raw['passed'] else None),
               elapsed_ms=raw.get('elapsed_ms'), runtime_assessment=raw.get('assessment') or raw.get('technical_status'),
               failure=raw.get('failure_code') or raw.get('failure'), approved_actions=ui,
               queue_status=raw.get('queue_status'), confirmation_clicked=raw.get('confirmation_clicked'),
               browse_status=raw.get('browse_status'), browse_outcome=raw.get('browse_outcome'),
               provider_turns=[{k:r[k] for k in ['mode','status','elapsed_ms','action','label','subgoals','diagnostic']} for r in turns])
    # Legacy navigation rows overwrite goal_reached_ms on a later re-entry. Treat it as evidence of arrival.
    if group == 'forced':
        row['arrival_observed_ms'] = row.pop('first_arrival_observed_ms')
    return row


def run(inputs):
    calls = collections.defaultdict(list)
    files = {}
    for folder in inputs:
        log = folder/'calls.jsonl'
        if log.exists():
            for line in log.read_text().splitlines():
                r = json.loads(line)
                calls[r['phase']].append(r)
        for path in folder.glob('[12]-*.json'):
            if path.stem in files:
                raise ValueError('Duplicate trial '+path.stem)
            files[path.stem] = path
    rows = [describe(k,json.loads(p.read_text())['cases'][0],calls[k]) for k,p in sorted(files.items())]
    by_trial = {r['trial']:r for r in rows}
    for folder in inputs:
        client_log = folder/'app-stages.log'
        if not client_log.exists():
            continue
        statuses = []
        for line in client_log.read_text().splitlines():
            match = re.search(r'^\s*([\d.]+).*?app_task_state status=(\w+) tool=(\w+)',line)
            if match:
                statuses.append((float(match[1]),match[2],match[3]))
        previous_end = 0
        for path in sorted(folder.glob('[12]-*.json'),key=lambda p:p.stat().st_mtime):
            end = path.stat().st_mtime
            transitions = []
            for stamp, state, tool in statuses:
                if previous_end < stamp <= end and (not transitions or transitions[-1] != [state,tool]):
                    transitions.append([state,tool])
            by_trial[path.stem]['client_transitions'] = transitions
            previous_end = end
    grouped = {}
    for group in ('registered','forced','fallback','harness_setup','terminal','auto','arrival','routecheck','entry'):
        g = [r for r in rows if r['group']==group]
        times = [r['completion_ms'] for r in g if r['passed'] and r['completion_ms'] is not None]
        grouped[group] = dict(trials=len(g), reached=sum(r['target_ever_reached'] for r in g),
                              success=sum(r['passed'] for r in g), false_completion=sum(r['false_completion'] for r in g),
                              success_median_ms=statistics.median(times) if times else None)
    return {'groups':grouped,'trials':rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs',nargs='+',type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    args.output.write_text(json.dumps(run(args.inputs),indent=2,ensure_ascii=False)+'\n')
