"""Bounded attempt accounting; observation changes alone never count as failures."""
from hashlib import sha256
import json
from ai_agent.runtime.types import ScreenSnapshot, VerificationStatus

RETRY_LIMIT_BEFORE_EXCLUSION = 2


def build_screen_snapshot(state_id, nodes, *, observation_id=None, captured_at_ms=None, applied_settings=None):
    parts = sorted('|'.join((node.node_id, node.label, node.role, str(node.bounds),
                            str(node.selected), str(node.value), node.package_name or '',
                            node.resource_id or '', str(node.window_id), str(node.enabled),
                            str(node.actionable), str(node.range_min), str(node.range_max))) for node in nodes)
    parts.append(json.dumps(applied_settings or {}, sort_keys=True, separators=(',', ':')))
    return ScreenSnapshot(state_id, sha256('\n'.join(parts).encode()).hexdigest(),
                          {n.node_id: n.value for n in nodes if n.role == 'slider' and n.value is not None},
                          observation_id=observation_id, captured_at_ms=captured_at_ms,
                          applied_settings=applied_settings or {})


def is_failed_attempt(verification_result, attempt, transition_id):
    """A failure flag alone cannot spend the user's action budget."""
    return bool(verification_result and verification_result.count_attempt
        and verification_result.status in {VerificationStatus.NO_CHANGE, VerificationStatus.WRONG_STATE}
        and attempt and attempt.get('event_key') and attempt.get('target_key')
        and attempt.get('attempt_id')
        and verification_result.attempt_id == attempt['attempt_id']
        and attempt.get('transition_id') == transition_id
        and attempt.get('action_at_ms') is not None
        and attempt.get('baseline_captured_at_ms') is not None
        and attempt['action_at_ms'] > attempt['baseline_captured_at_ms'])


def register_attempt_outcome(session, *, pending_transition_id, verification_result, retry_limit=2,
                             attempt=None):
    if session is None:
        return {}, ()
    attempts = dict(session.transition_attempts)
    failed_nodes = set(session.failed_node_ids)
    if not pending_transition_id or not verification_result:
        return attempts, tuple(sorted(failed_nodes))
    attempt = attempt if attempt is not None else session.pending_attempt
    if is_failed_attempt(verification_result, attempt, pending_transition_id):
        # The counter is scoped to a transition AND semantic target, not just an edge.
        key = pending_transition_id + ':' + attempt.get('target_key', '')
        attempts[key] = attempts.get(key, 0) + 1
        if attempts[key] >= retry_limit and session.last_target_node_id:
            failed_nodes.add(session.last_target_node_id)
    return attempts, tuple(sorted(failed_nodes))
