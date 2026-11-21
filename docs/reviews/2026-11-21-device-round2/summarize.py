"""Summarize round-2 device experiments (E1 provider, E3 retry, E2 memory+planned)."""
import json, collections, statistics as st, sys
from pathlib import Path
ROOT = Path(__file__).parent

def load(name):
    ep = [json.loads(l) for l in open(ROOT / name / 'episodes.jsonl')]
    pc = collections.defaultdict(list)
    for l in open(ROOT / name / 'facade-logs/provider-calls.jsonl'):
        r = json.loads(l); pc[r['trial_id']].append(r)
    return ep, pc

def group(ep, pc, arms, phase=None):
    out = {}
    for arm in arms:
        g = [e for e in ep if e['arm'] == arm and (phase is None or e['session'].startswith(phase))]
        calls = [r for e in g for r in pc[e['trial_id']]]; model = [r for r in calls if r['model_calls']]
        goal = [e for e in g if e['oracle_success'] is True]
        vt = [e['goal_observed_ms'] + e['runtime_verified_after_goal_ms'] for e in g
              if e.get('runtime_verified_after_goal_ms') is not None and e.get('goal_observed_ms') is not None]
        usd = sum((r['input_tokens'] * (.75 if (r.get('model') or '').startswith('gemini') else 2.0)
                   + (r['total_tokens'] - r['input_tokens']) * (3.75 if (r.get('model') or '').startswith('gemini') else 10.0)) / 1e6
                  for r in model if isinstance(r.get('total_tokens'), int))
        props = collections.Counter(k for e in g for k, v in e.get('proposals_by_source', {}).items() for _ in range(v))
        out[arm] = dict(episodes=len(g), goal=len(goal), verified=sum(bool(e['runtime_verified_offer']) for e in g),
            false_verified=sum(e.get('false_verified_offer') is True for e in g), model_calls=len(model), requests=len(calls),
            provider_errors=sum(r['provider_error'] for r in calls), injected=sum('injected' in str(r.get('reason')) or r.get('reason_code') == 'provider_timeout' for r in calls),
            result_only_model=sum(r.get('planning_mode') == 'result_only' and r['model_calls'] == 1 for r in calls),
            triage=sum(r.get('reason_code') == 'completion_triage' for r in calls),
            goal_ms_median=st.median([e['goal_observed_ms'] for e in goal]) if goal else None,
            verified_ms=sorted(vt), mean_elapsed_ms=round(st.mean([e['elapsed_ms'] for e in g])) if g else None,
            model_wait_median=st.median([r['model_wait_ms'] for r in model]) if model else None,
            proposals=dict(props), est_usd=round(usd, 4),
            models=sorted({r.get('model') for r in model if r.get('model')}))
    return out

res = {}
ep, pc = load('E1-provider'); res['E1'] = group(ep, pc, 'AB'); res['E1_by_task'] = {t: group([e for e in ep if e['task_id'] == t], pc, 'AB') for t in sorted({e['task_id'] for e in ep})}
ep, pc = load('E3-retry'); res['E3'] = group(ep, pc, 'AB')
ep, pc = load('E2-memory'); res['E2_learn'] = group(ep, pc, 'BC', 'learn'); res['E2_eval'] = group(ep, pc, 'BC', 'eval')
(ROOT / 'summary.json').write_text(json.dumps(res, ensure_ascii=False, indent=1) + '\n')
for k, v in res.items():
    if k.endswith('by_task'):
        for t, g in v.items(): print(k, t, {a: (g[a]['goal'], g[a]['verified'], g[a]['false_verified'], g[a]['model_calls'], g[a]['mean_elapsed_ms']) for a in g})
        continue
    for a, g in v.items(): print(k, a, json.dumps(g, ensure_ascii=False))
