"""Summarize the real-device memory-reuse experiment (no simulation, no re-scoring).

Inputs: device episodes.jsonl and the facade log directory. Outputs summary.json
and summary.md. Failure-inclusive time assigns the full budget to every episode
without an independent oracle success.
"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import random

PHASE = {'learn1': 'learn', 'learn2': 'learn', 'learn3': 'learn', 'retain': 'retain', 'mirror1': 'mirror', 'mirror2': 'mirror'}
SESSIONS = list(PHASE)
# MetaController post-run positive controls confirmed these oracles (frozen before run 2).
VALIDATED_ORACLE_TASKS = ('settings_display', 'settings_notifications', 'clock_timer', 'chrome_menu',
                          'chrome_new_tab', 'youtube_subscriptions', 'youtube_search')
MEMORY_SOURCES = ('memory_server_ai2', 'memory_device_ai2_recall', 'memory_device_route_cache')


def read_jsonl(path):
    path = Path(path)
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def memory_proposals(e):
    return sum((e.get('proposals_by_source') or {}).get(s, 0) for s in MEMORY_SOURCES)


def penalized(e):
    return e['elapsed_ms'] if e.get('oracle_success') is True else e['budget_ms']


def rate(k, n):
    return None if not n else round(k / n, 4)


def group_metrics(rows):
    n = len(rows)
    ready = [e for e in rows if e.get('setup_status') == 'ready']
    success = sum(e.get('oracle_success') is True for e in rows)
    calls = [e.get('observed_model_calls', 0) for e in ready]
    mem = [memory_proposals(e) for e in ready]
    return dict(scheduled=n, ready=len(ready), oracle_success=success, success_rate=rate(success, n),
        success_rate_ready=rate(success, len(ready)),
        mean_penalized_time_ms=round(sum(penalized(e) for e in rows) / n, 1) if n else None,
        mean_success_time_ms=round(sum(e['elapsed_ms'] for e in rows if e.get('oracle_success') is True) / success, 1) if success else None,
        mean_model_calls=round(sum(calls) / len(calls), 3) if calls else None, total_model_calls=sum(calls),
        episodes_with_memory_proposal=sum(m > 0 for m in mem), memory_proposals=sum(mem),
        memory_proposals_by_source=dict(sum((Counter({s: (e.get('proposals_by_source') or {}).get(s, 0) for s in MEMORY_SOURCES}) for e in ready), Counter())),
        success_when_memory_proposed=f"{sum(e.get('oracle_success') is True for e in ready if memory_proposals(e))}/{sum(memory_proposals(e) > 0 for e in ready)}",
        runtime_verified_offers=sum(bool(e.get('runtime_verified_offer')) for e in rows),
        false_verified_offers=sum(e.get('false_verified_offer') is True for e in rows),
        user_confirmed=sum(e.get('user_confirmation') == 'confirmed' for e in rows),
        outcomes=dict(Counter(e.get('outcome') for e in rows)))


def bootstrap(pairs, key, iterations, seed):
    """Task-cluster bootstrap of mean paired difference."""
    clusters = defaultdict(list)
    for task, diff in pairs:
        clusters[task].append(diff)
    tasks = sorted(clusters)
    if not tasks:
        return None
    rng = random.Random(seed)
    means = []
    for _ in range(iterations):
        sample = [d for t in (rng.choice(tasks) for _ in tasks) for d in clusters[t]]
        means.append(sum(sample) / len(sample))
    means.sort()
    return [round(means[int(.025 * iterations)], 4), round(means[int(.975 * iterations) - 1], 4)]


def paired(episodes, left, right, phases, iterations, seed):
    index = {(e['task_id'], e['session'], e['arm']): e for e in episodes if e.get('setup_status') == 'ready'}
    rows = []
    for (task, session, arm), e in index.items():
        if arm != left or PHASE[session] not in phases:
            continue
        other = index.get((task, session, right))
        if other:
            rows.append((task, e, other))
    if not rows:
        return dict(pairs=0)
    out = dict(pairs=len(rows), task_clusters=len({t for t, *_ in rows}))
    for name, fn in (('success', lambda e: float(e.get('oracle_success') is True)),
                     ('penalized_time_ms', penalized), ('model_calls', lambda e: e.get('observed_model_calls', 0))):
        diffs = [(t, fn(r) - fn(l)) for t, l, r in rows]
        out[name] = dict(mean_diff_right_minus_left=round(sum(d for _, d in diffs) / len(diffs), 4),
                         cluster_bootstrap_95ci=bootstrap(diffs, name, iterations, seed))
    return out


def recall_diagnostics(recalls, registrations):
    trial = {r['runtime_ref']: r for r in registrations if r.get('trial_id')}
    out = defaultdict(lambda: defaultdict(Counter))
    for r in recalls:
        reg = trial.get(r['runtime_ref'])
        if not reg:
            continue
        session = reg['trial_id'].split(':')[2]
        key = f"{r['arm']}/{PHASE[session]}"
        c = out[key]['all']
        c['recalls'] += 1
        c['gate_' + str(r.get('gate'))] += 1
        c['completion_known'] += bool(r.get('completion_known'))
        for variant, v in r.get('variants', {}).items():
            vc = out[key][variant]
            vc['remembered'] += v['status'] == 'remembered'
            vc['remembered_with_completion'] += v['status'] == 'remembered' and bool(r.get('completion_known'))
            vc['has_same_goal_memory'] += v['same_goal_memories'] > 0
            vc['has_two_confirmed_routes'] += v['confirmed_routes'] >= 2
            vc['has_same_goal_screen_step'] += v['same_goal_screen_steps'] > 0
            vc['blocked_by_adverse'] += any(ev.get('rejections', 0) + ev.get('unconfirmed', 0) > 0 for ev in v.get('evidence', []))
    return {k: {vk: dict(vv) for vk, vv in v.items()} for k, v in sorted(out.items())}


def per_task(episodes):
    out = {}
    for arm in sorted({e['arm'] for e in episodes}):
        by_task = defaultdict(dict)
        for e in episodes:
            if e['arm'] == arm:
                by_task[e['task_id']][e['session']] = e.get('oracle_success') is True
        learn = [[s.get(x, False) for x in ('learn1', 'learn2', 'learn3')] for s in by_task.values()]
        recov = [l for l in learn if not l[0]]
        out[arm] = dict(tasks=len(by_task), pass_at_1=sum(l[0] for l in learn), pass_at_3=sum(any(l) for l in learn),
            learn_successes_ge_2=sum(sum(l) >= 2 for l in learn),
            failure_recovery=f"{sum(any(l[1:]) for l in recov)}/{len(recov)}",
            per_task={t: ''.join('O' if s.get(x) else '.' for x in SESSIONS) for t, s in sorted(by_task.items())})
    return out


def analyses_summary(rows):
    out = {}
    for arm in sorted({r['arm'] for r in rows}):
        mine = [r for r in rows if r['arm'] == arm]
        goals = Counter((r['task_id'], r['goal_ref']) for r in mine if r.get('route_steps'))
        out[arm] = dict(user_confirmed_tasks=len(mine), route_formed=sum(bool(r.get('route_steps')) for r in mine),
            promoted_by_user_confirmed_result=sum('user_confirmed_result' in (r.get('effect_evidence') or []) for r in mine),
            goals_with_two_or_more_routes=sorted(f'{t}:{g[:6]}' for (t, g), n in goals.items() if n >= 2))
    return out


def render(summary):
    L = ['# 기억 재사용 실기기 실험 집계', '', f"에피소드 {summary['episodes']}회, 회기 예산 {summary['budget_ms']}ms.", '',
         '## 비교군 × 단계', '', '| 비교군 | 단계 | 예약 | 준비 | 독립 성공 | 성공률 | 실패 포함 평균 시간(ms) | 평균 모델 호출 | 기억 제안 회기 | 기억 제안 회기의 성공 | 잘못된 VERIFIED |',
         '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for key, m in summary['by_arm_phase'].items():
        arm, phase = key.split('/')
        L.append(f"| {arm} | {phase} | {m['scheduled']} | {m['ready']} | {m['oracle_success']} | {m['success_rate']} | {m['mean_penalized_time_ms']} | {m['mean_model_calls']} | {m['episodes_with_memory_proposal']} | {m['success_when_memory_proposed']} | {m['false_verified_offers']} |")
    L += ['', '## 회기별 성공 (O=독립 성공)', '', '| 비교군 | pass@1 | pass@3 | 학습 2회 이상 성공 | 실패 회복 | 과제별 learn1·2·3·retain·mirror1·2 |', '|---|---:|---:|---:|---:|---|']
    for arm, m in summary['per_task'].items():
        L.append(f"| {arm} | {m['pass_at_1']}/{m['tasks']} | {m['pass_at_3']}/{m['tasks']} | {m['learn_successes_ge_2']} | {m['failure_recovery']} | " +
                 ', '.join(f"{t} {p}" for t, p in m['per_task'].items()) + ' |')
    L += ['', '## 대응 비교 (같은 과제·회기, 준비 완료 쌍)', '']
    for name, p in summary['paired'].items():
        L.append(f"- **{name}**: `{json.dumps(p, ensure_ascii=False)}`")
    L += ['', '## 정답 양성 대조 확인 7과제', '', '| 비교군 | 독립 성공/예약 | 잘못된 VERIFIED | 기억 제안 회기 |', '|---|---:|---:|---:|']
    for arm, m in summary.get('by_arm_validated_oracle', {}).items():
        L.append(f"| {arm} | {m['oracle_success']}/{m['scheduled']} | {m['false_verified_offers']} | {m['episodes_with_memory_proposal']} |")
    L += ['', '## 완료 조건 결속과 AI2 경로 형성', '', '```json', json.dumps(dict(completion_binding=summary.get('completion_binding'),
          ai2_analyses=summary.get('ai2_analyses')), ensure_ascii=False, indent=1), '```']
    L += ['', '## 앱별 성공률', '', '| 비교군 | 앱 | 성공/예약 |', '|---|---|---:|']
    for key, m in summary['by_arm_app'].items():
        arm, app = key.split('/', 1)
        L.append(f"| {arm} | {app} | {m['oracle_success']}/{m['scheduled']} |")
    L += ['', '## 회수 진단과 그림자 변형', '', '```json', json.dumps(summary['recall_diagnostics'], ensure_ascii=False, indent=1), '```',
          '', '## 모델 제공자 오류', '', '```json', json.dumps(summary['provider'], ensure_ascii=False, indent=1), '```']
    return '\n'.join(L) + '\n'


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--episodes', type=Path, required=True)
    p.add_argument('--logs', type=Path, required=True)
    p.add_argument('--output-dir', type=Path, required=True)
    p.add_argument('--bootstrap', type=int, default=2000)
    p.add_argument('--seed', type=int, default=20261120)
    a = p.parse_args(argv)
    episodes = read_jsonl(a.episodes)
    calls = read_jsonl(a.logs / 'provider-calls.jsonl')
    summary = dict(schema_version='memory-reuse-summary.v1', episodes=len(episodes),
        budget_ms=episodes[0]['budget_ms'] if episodes else None,
        by_arm_phase={f'{arm}/{ph}': group_metrics([e for e in episodes if e['arm'] == arm and PHASE[e['session']] == ph])
                      for arm in ('A', 'B', 'C') for ph in ('learn', 'retain', 'mirror')
                      if any(e['arm'] == arm and PHASE[e['session']] == ph for e in episodes)},
        by_arm={arm: group_metrics([e for e in episodes if e['arm'] == arm]) for arm in ('A', 'B', 'C') if any(e['arm'] == arm for e in episodes)},
        by_arm_app={f'{arm}/{app}': group_metrics([e for e in episodes if e['arm'] == arm and e['app_id'] == app])
                    for arm in ('A', 'B', 'C') for app in sorted({e['app_id'] for e in episodes})},
        per_task=per_task(episodes),
        paired={f'{r}-{l} {"+".join(ph)}': paired(episodes, l, r, ph, a.bootstrap, a.seed)
                for l, r in (('A', 'B'), ('B', 'C'), ('A', 'C')) for ph in (('learn',), ('retain',), ('mirror',), ('retain', 'mirror'))},
        by_arm_validated_oracle={arm: group_metrics([e for e in episodes if e['arm'] == arm and e['task_id'] in VALIDATED_ORACLE_TASKS])
                                 for arm in ('A', 'B', 'C') if any(e['arm'] == arm for e in episodes)},
        completion_binding={arm: dict(first_planner_responses=sum(1 for c in calls if c['arm'] == arm and 'completion_bound' in c and c.get('status') != 'unavailable'),
            bound=sum(bool(c.get('completion_bound')) for c in calls if c['arm'] == arm),
            rejected_satisfied_at_binding=sum(c.get('completion_rejected') == 'satisfied_at_binding' for c in calls if c['arm'] == arm))
            for arm in ('A', 'B', 'C')},
        ai2_analyses=analyses_summary(read_jsonl(a.logs / 'analyses.jsonl')),
        recall_diagnostics=recall_diagnostics(read_jsonl(a.logs / 'recalls.jsonl'), read_jsonl(a.logs / 'registrations.jsonl')),
        provider=dict(by_arm={arm: dict(calls=sum(c['model_calls'] for c in calls if c['arm'] == arm),
            errors=dict(Counter(c.get('error_type') for c in calls if c['arm'] == arm and c.get('error_type'))),
            ai2_server_proposals=sum(c.get('reason') == 'ai2_remembered' for c in calls if c['arm'] == arm))
            for arm in ('A', 'B', 'C')}))
    a.output_dir.mkdir(parents=True, exist_ok=True)
    (a.output_dir / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (a.output_dir / 'summary.md').write_text(render(summary), encoding='utf-8')
    print(json.dumps({k: summary['by_arm'][k]['success_rate'] for k in summary['by_arm']}))


if __name__ == '__main__':
    raise SystemExit(main())
