"""Shared numeric outcome policy; no dependency on either agent or storage."""
from math import sqrt

POLICY = 'ai2-success-selection.v1'
MIN_ATTEMPTS = 3
MIN_SUCCESS_RATE = .70


def outcome_metrics(successes, failures, unknown=0):
    if any(type(n) is not int or n < 0 for n in (successes, failures, unknown)):
        raise ValueError('Outcome counts must be nonnegative integers')
    attempts = successes + failures + unknown
    rate = successes / attempts if attempts else 0.0
    # Unknown outcomes stay in the denominator; they are never claimed failures.
    # Wilson's lower bound avoids favoring tiny perfect samples over proven routes.
    z = 1.96
    score = ((rate + z*z/(2*attempts) - z*sqrt(rate*(1-rate)/attempts + z*z/(4*attempts*attempts)))
             / (1 + z*z/attempts)) if attempts else 0.0
    return {'attempts': attempts, 'successes': successes, 'failures': failures,
            'unknown': unknown, 'success_rate': rate, 'score': score}


def select_candidate(candidates, *, min_attempts=MIN_ATTEMPTS, min_success_rate=MIN_SUCCESS_RATE):
    """Return a unique evidence-backed winner, or None to retain the fallback."""
    if type(min_attempts) is not int or min_attempts < 1 or not 0 <= min_success_rate <= 1:
        raise ValueError('Invalid success selection thresholds')
    eligible = []
    for candidate in candidates:
        metrics = outcome_metrics(candidate['successes'], candidate['failures'], candidate.get('unknown', 0))
        if metrics['attempts'] >= min_attempts and metrics['success_rate'] >= min_success_rate:
            eligible.append({**candidate, **metrics, 'selection_policy': POLICY})
    eligible.sort(key=lambda c: (c['score'], c['success_rate'], c['attempts']), reverse=True)
    if not eligible:
        return None
    if len(eligible) > 1 and all(eligible[0][k] == eligible[1][k] for k in ('score', 'success_rate', 'attempts')):
        return None
    return eligible[0]


def priority_candidate(proposal, base):
    """Recover counted outcomes for the proposed first edge, checking its baseline."""
    if proposal.proposal_type != 'transition_priority':
        return None
    priorities = proposal.change.get('priorities', {})
    state = proposal.evidence.get('state_id')
    edges = [e for e in base['transitions'] if e['from_state'] == state]
    if not edges or set(priorities) != {e['transition_id'] for e in edges}:
        return None
    baseline = min(edges, key=lambda e: (e.get('priority', 0), e['transition_id']))['transition_id']
    chosen = min(edges, key=lambda e: (priorities[e['transition_id']], e['transition_id']))['transition_id']
    metrics = proposal.evidence.get('route_metrics', {})
    def counted(edge):
        row = metrics.get(edge, {})
        attempts, rate = row.get('attempts'), row.get('success_rate')
        if (type(attempts) is not int or attempts < MIN_ATTEMPTS
                or type(rate) not in (float, int) or not 0 <= rate <= 1):
            return None
        successes = round(attempts * rate)
        if abs(successes - attempts * rate) > 1e-6:
            return None
        return outcome_metrics(successes, attempts-successes)
    before, after = counted(baseline), counted(chosen)
    if before is None or after is None or after['success_rate'] <= before['success_rate']:
        return None
    return {'key': proposal.proposal_id, **after}
