"""Graphless operational outcomes, without gold-label, training or release claims."""
from collections import Counter
import math
from statistics import median

OUTCOMES = {'in_progress','verified_navigation','user_reported','cancelled','blocked','unknown'}


def assess_sessions(sessions):
    outcomes, pages, actions = Counter(), Counter(), Counter()
    latencies, calls, steps = [], 0, 0
    for records in sessions:
        if not records:
            continue
        for event in records:
            if event.get('schema_version') != 'browse-event.v1' or event.get('outcome') not in OUTCOMES:
                raise ValueError('Invalid browse evidence')
            steps += 1
            pages[event['page']] += 1
            if event.get('action_correlated'):
                actions[event['action_kind']] += 1
            if event.get('called'):
                calls += 1
                value = event.get('provider_latency_ms')
                if type(value) not in (int,float) or value < 0 or not math.isfinite(value):
                    raise ValueError('Invalid browse timing')
                latencies.append(value)
        outcomes[records[-1]['outcome']] += 1
    ordered = sorted(latencies)
    return {'schema_version':'browse-assessment.v1','sessions':sum(outcomes.values()),'steps':steps,
        'outcomes':dict(outcomes),'pages':dict(pages),'correlated_actions':dict(actions),'jev_calls':calls,
        'provider_median_ms':median(ordered) if ordered else None,
        'provider_p95_ms':ordered[math.ceil(.95*len(ordered))-1] if ordered else None,
        'independent_target_accuracy':None,'automatic_training':False,'automatic_release':False}
