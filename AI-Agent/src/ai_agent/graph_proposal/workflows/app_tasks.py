"""Reconstruct app-independent experience for reusable, owner-scoped suggestions.

No LLM, weight update, new selectors or independent-gold claims are produced here.
Only linked device observations and explicit user results support route candidates.
"""
from collections import Counter
from hashlib import sha256

VERSION = 'ai2-app-task-analysis.v1'


def analyze_app_task(records, policy_hash):
    if not records or len(records) > 66:
        raise ValueError('Invalid experience budget')
    events = [r['evidence'] for r in records]
    arrivals = {r['event_id']:r.get('received_at_ms',r['evidence']['observed_at_ms']) for r in records}
    if (len({r['event_id'] for r in records}) != len(records)
            or [e['sequence'] for e in events] != list(range(1,len(events)+1))):
        return {'schema_version':VERSION,'policy_hash':policy_hash,'status':'NEEDS_DATA',
                'reason':'incomplete_sequence','steps':[],'route':[],'tactics':[]}
    first = events[0]
    if any(e['task_id'] != first['task_id'] or e['goal_key'] != first['goal_key']
           or e['protocol_version'] != 'app-task-experience.v1' for e in events):
        raise ValueError('Mixed task experience')
    terminals=[i for i,e in enumerate(events) if e['kind']=='result']
    if terminals and terminals != [len(events)-1]:
        return {'schema_version':VERSION,'policy_hash':policy_hash,'status':'NEEDS_DATA',
                'reason':'invalid_terminal_order','steps':[],'route':[],'tactics':[]}
    steps, route_steps, tactics, seen, prefix = [], [], Counter(), set(), []
    for record in records:
        e=record['evidence']
        if e['kind'] != 'attempt':
            continue
        a = e['action']
        if not a:
            raise ValueError('Missing action')
        outcome = e['outcome']
        if outcome == 'verified' and (not e['action_observed'] or not e['condition_met']):
            raise ValueError('Unsupported success')
        if outcome == 'verified' and a['expectation'] in {'screen_changed','target_visible'} and e['screen_key'] == e['after_key']:
            # Keyboard dismissal is intentionally not part of structural fingerprints.
            if a['tool'] != 'back':
                outcome = 'unconfirmed'
        step={'environment':e['environment'],'goal_key':e['goal_key'],
            'subgoal_key':e['subgoal_key'],'screen_key':e['screen_key'],'after_key':e['after_key'],
            'after_package':e['after_package'],'action':a,'outcome':outcome,
            'sequence':e['sequence'],'observed_at_ms':e['observed_at_ms'],'received_at_ms':arrivals[record['event_id']],
            'path_prefix':list(prefix),'has_initial_context':first.get('starts_task') is True}
        route_steps.append(step)
        identity = (e['screen_key'],a['tool'],a['target_key'],outcome,tuple(prefix))
        if outcome=='verified':
            prefix.append(sha256('\n'.join((e['screen_key'],a['tool'],a['target_key'],e['after_key'])).encode('utf-8')).hexdigest())
        if identity in seen:
            continue  # Repeated retries within one task are not independent support.
        seen.add(identity)
        steps.append(step)
        advice = ('prefer_observed_capability' if outcome == 'verified' else
                  'choose_another_native_target' if outcome == 'rejected' else 'reobserve_before_repeating')
        tactics[(a['tool'],a['role'],advice)] += 1
    attempts = [e for e in events if e['kind']=='attempt']
    linked = all(a['after_key']==b['screen_key'] and a['after_package']==b['environment']['package']
                 for a,b in zip(attempts,attempts[1:]))
    complete = (first.get('starts_task') is True and events[-1]['kind']=='result' and events[-1]['outcome']=='user_confirmed'
                and bool(attempts) and all(e['outcome']=='verified' for e in attempts) and linked
                and all(s['outcome']=='verified' for s in route_steps)
                and events[-1]['screen_key']==attempts[-1]['after_key']
                and events[-1]['environment']['package']==attempts[-1]['after_package'])
    return {'schema_version':VERSION,'policy_hash':policy_hash,'status':'ANALYZED',
        'goal_key':first['goal_key'],'steps':steps,'route':route_steps if complete else [],
        'tactics':[{'tool':t,'role':r,'advice':a,'observations':n} for (t,r,a),n in sorted(tactics.items())],
        'technical_source':'device_reported','user_confirmed':events[-1]['outcome']=='user_confirmed',
        'independent_device_validation':'NOT_ASSESSED','automatic_execution':False}
