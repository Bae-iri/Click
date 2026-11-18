"""Attribute one outcome to each linked browsing attempt, including unresolved ones."""
import re
from collections import defaultdict
from ai_agent.graph_proposal.proposals.browse_graph import policy_hash


def browse_outcomes(records, policy):
    if not records or len(records) > policy['max_turns'] + 1:
        raise ValueError('Invalid outcome trace size')
    if [r['sequence'] for r in records] != list(range(1, len(records) + 1)):
        raise ValueError('Incomplete outcome trace')
    if len({r['event_id'] for r in records}) != len(records):
        raise ValueError('Duplicate outcome evidence')
    events = [r['evidence'] for r in records]
    first = events[0]
    if (first.get('goal_type') not in {'coupang.product.search', 'coupang.product.details'}
            or not isinstance(first.get('code_hash'), str)
            or not re.fullmatch('[0-9a-f]{64}', first['code_hash'])):
        raise ValueError('Unsupported outcome trace')
    attempts = {}
    for event in events:
        if (event.get('schema_version') != 'browse-event.v1'
                or event.get('goal_type') != first.get('goal_type')
                or event.get('code_hash') != first.get('code_hash')
                or event.get('policy_version') != policy['version']
                or event.get('policy_hash') != policy_hash(policy)):
            raise ValueError('Incompatible outcome trace')
        if not event.get('action_attempted'):
            continue
        attempt = event.get('attempt_hash')
        action, resource = event.get('attempted_action'), event.get('attempted_resource')
        source = event.get('attempted_page', event.get('before_page'))
        resources = {'search': 'search_resources', 'product': 'product_resources', 'scroll': 'results_resources'}
        if (not isinstance(attempt, str) or not re.fullmatch('[0-9a-f]{64}', attempt)
                or action not in resources or resource not in policy[resources[action]]
                or source not in {'search', 'results'}):
            continue
        key = (source, action, resource)
        previous = attempts.get(attempt)
        if previous and previous[0] != key:
            raise ValueError('Attempt identity changed')
        label = 'unknown'
        if event.get('action_correlated') is True:
            destination = event.get('page')
            if action == 'search' and destination in {'search', 'results'}:
                label = 'successes'
            elif action == 'product' and event.get('reason') == 'verified_navigation':
                label = 'successes'
            elif action == 'scroll' and destination == 'results':
                label = 'successes'
            elif destination in {'search', 'results', 'detail'}:
                label = 'failures'
        # A later correlated observation resolves the same attempt only once.
        if not previous or previous[1] == 'unknown':
            attempts[attempt] = (key, label)
    counts = defaultdict(lambda: {'successes': 0, 'failures': 0, 'unknown': 0})
    for key, label in attempts.values():
        counts[key][label] += 1
    return {'schema_version': 'browse-outcomes.v1', 'goal_type': first.get('goal_type'),
            'policy_hash': policy_hash(policy), 'code_hash': first.get('code_hash'),
            'routes': [{'page': p, 'action': a, 'resource': r, **counts[(p, a, r)]}
                       for p, a, r in sorted(counts)]}
