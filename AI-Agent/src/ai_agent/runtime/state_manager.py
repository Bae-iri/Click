"""Bounded attempt accounting; observation changes alone never count as failures."""
from hashlib import sha256
import json
from ai_agent.runtime.types import ScreenSnapshot, VerificationStatus

RETRY_LIMIT_BEFORE_EXCLUSION = 2


def build_screen_snapshot(state_id, nodes, *, observation_id=None, captured_at_ms=None, applied_settings=None):
    parts = sorted('|'.join((node.node_id, node.label, node.role, str(node.bounds),
                            str(node.selected), str(node.value), node.package_name or '',
                            node.resource_id or '', str(node.window_id), str(node.enabled),
                            str(node.actionable), str(node.range_min), str(node.range_max), str(node.checked))) for node in nodes)
    # Selection identity excludes unrelated persisted device values. Retain
    # the full fingerprint below for action consent and result verification.
    native_fingerprint = sha256(json.dumps([node.to_dict() for node in sorted(nodes, key=lambda n: n.node_id)],
        sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    from ai_agent.runtime.classifier import _screen_titles
    from ai_agent.runtime.keywords import normalize_text
    titles = [n for n in _screen_titles(nodes) if n.source_confidence is None and not n.node_id.startswith('vision:')]
    page = [(n.package_name, normalize_text(n.label)) for n in titles]
    page_fingerprint = sha256(json.dumps(sorted(page), ensure_ascii=False).encode()).hexdigest() if len(page) == 1 else None
    navigation = []
    for node in sorted(nodes, key=lambda n: n.node_id):
        payload = node.to_dict()
        for key in ('value', 'checked', 'selected'):
            payload.pop(key, None)
        navigation.append(payload)
    navigation_fingerprint = sha256(json.dumps(navigation, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    parts.append(json.dumps(applied_settings or {}, sort_keys=True, separators=(',', ':')))
    return ScreenSnapshot(state_id, sha256('\n'.join(parts).encode()).hexdigest(),
                          {n.node_id: n.value for n in nodes if n.role == 'slider' and n.value is not None},
                          observation_id=observation_id, captured_at_ms=captured_at_ms,
                          applied_settings=applied_settings or {},
                          checked_values={n.node_id: n.checked for n in nodes if type(n.checked) is bool},
                          native_fingerprint=native_fingerprint, page_fingerprint=page_fingerprint,
                          navigation_fingerprint=navigation_fingerprint)


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
