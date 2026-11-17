"""Revise advisory candidates from observed failures, without inventing selectors."""
from collections import Counter
import re
from ai_agent.graph_proposal.proposals.browse_graph import policy_hash

VERSION = 'browse-repairs.v1'
ADVICE = {
    'unrecognized_screen': 'collect_public_screen_evidence',
    'no_candidates': 'inspect_public_candidate_coverage',
    'repeated_screen': 'reobserve_before_repeating',
    'abstained': 'request_additional_observation',
    'low_confidence': 'request_additional_observation',
    'unavailable': 'check_jev_connection',
    'provider_error': 'check_jev_connection',
    'invalid_response': 'check_jev_response_contract',
}


def propose_repairs(records, policy):
    if not records or len(records) > policy['max_turns'] + 1:
        raise ValueError('Invalid repair trace size')
    if ([r['sequence'] for r in records] != list(range(1, len(records) + 1))
            or len({r['event_id'] for r in records}) != len(records)):
        raise ValueError('Incomplete repair trace')
    events = [r['evidence'] for r in records]
    goal, code = events[0].get('goal_type'), events[0].get('code_hash')
    if (goal not in {'coupang.product.search', 'coupang.product.details'}
            or not isinstance(code, str) or not re.fullmatch('[0-9a-f]{64}', code)):
        raise ValueError('Unsupported repair trace')
    counts, unconfirmed = Counter(), Counter()
    attempted = set()
    resolved = {e.get('attempt_hash') for e in events if e.get('action_correlated')}
    for event in events:
        if (event.get('schema_version') != 'browse-event.v1' or event.get('goal_type') != goal
                or event.get('code_hash') != code or event.get('policy_version') != policy['version']
                or event.get('policy_hash') != policy_hash(policy)):
            raise ValueError('Incompatible repair trace')
        page, reason = event.get('page'), event.get('reason')
        if page not in {'search', 'results', 'detail', 'unknown'}:
            raise ValueError('Unsupported repair page')
        if reason in ADVICE:
            counts[(page, ADVICE[reason])] += 1
        # A tap without a changed screen is uncertain, never a proven failure.
        if event.get('action_attempted') and not event.get('action_correlated'):
            kind, resource = event.get('attempted_action'), event.get('attempted_resource')
            key = {'search': 'search_resources', 'product': 'product_resources', 'scroll': 'results_resources'}.get(kind)
            attempt = event.get('attempt_hash')
            if (key and resource in policy[key] and attempt not in attempted and attempt not in resolved
                    and isinstance(attempt, str) and re.fullmatch('[0-9a-f]{64}', attempt)):
                attempted.add(attempt)
                unconfirmed[(page, kind, resource)] += 1
    if events[-1].get('outcome') == 'verified_navigation':
        # A later verified recovery supersedes the old repair request.
        counts.clear()
        unconfirmed.clear()
    return {'schema_version': VERSION, 'goal_type': goal, 'policy_hash': policy_hash(policy),
        'advice': [{'page': page, 'advice': advice, 'observations': n}
            for (page, advice), n in sorted(counts.items())],
        'unconfirmed_routes': [{'page': page, 'action': kind, 'resource': resource,
            'advice': 'reobserve_before_repeating', 'unconfirmed_attempts': n}
            for (page, kind, resource), n in sorted(unconfirmed.items())],
        'automatic_candidate_revision': True, 'automatic_graph_release': False}
