"""Bounded attempt accounting; observation changes alone never count as failures."""
from hashlib import sha256
from ai_agent.runtime.types import ScreenSnapshot, VerificationStatus

RETRY_LIMIT_BEFORE_EXCLUSION = 2


def build_screen_snapshot(state_id, nodes, *, observation_id=None, captured_at_ms=None):
    parts = sorted('|'.join((node.node_id, node.label, node.role, str(node.bounds),
                            str(node.selected), str(node.value))) for node in nodes)
    return ScreenSnapshot(state_id, sha256('\n'.join(parts).encode()).hexdigest(),
                          {n.node_id: n.value for n in nodes if n.role == 'slider' and n.value is not None},
                          observation_id=observation_id, captured_at_ms=captured_at_ms)


def register_attempt_outcome(session, *, pending_transition_id, verification_result, retry_limit=2):
    if session is None:
        return {}, ()
    attempts = dict(session.transition_attempts)
    failed_nodes = set(session.failed_node_ids)
    if not pending_transition_id or not verification_result:
        return attempts, tuple(sorted(failed_nodes))
    if verification_result.count_attempt and verification_result.status in {
            VerificationStatus.NO_CHANGE, VerificationStatus.WRONG_STATE}:
        attempt = session.pending_attempt or {}
        # The counter is scoped to a transition AND semantic target, not just an edge.
        key = pending_transition_id + ':' + attempt.get('target_key', '')
        attempts[key] = attempts.get(key, 0) + 1
        if attempts[key] >= retry_limit and session.last_target_node_id:
            failed_nodes.add(session.last_target_node_id)
    return attempts, tuple(sorted(failed_nodes))
