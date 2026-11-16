"""Primary screen decisions and revalidation of an unexecuted native guide.

Rule matches are evidence, not permission to replace an abstaining provider.
Only an already accepted selection can be retained on an unchanged observation.
"""
from dataclasses import dataclass, replace
from hashlib import sha256
import json

from ai_agent.runtime.failures import EXTERNAL_CHANGES, SelectionFailure, selection_failure
from ai_agent.runtime.targeting import eligible_nodes, target_key, selector_evidence
from ai_agent.runtime.types import MatchResult, SafetyLevel, VerificationStatus as Status


@dataclass(frozen=True)
class PrimaryDecision:
    match: MatchResult | None
    diagnostics: dict
    recovery_reason: str | None = None
    failure: SelectionFailure | None = None


def retry_session(request, session):
    """Retire the pending operation on explicit retry, preserving spent budgets."""
    event = request.user_action_event
    if (not session or session.task_completed or not event
            or event.action_type.value not in {"manual_override", "back_navigated", "home_navigated"}
            or event.session_id not in {None, request.session_id}
            or (event.episode_id is not None and event.episode_id != request.episode_id)):
        return session
    baseline = session.last_snapshot.captured_at_ms if session.last_snapshot else None
    if (baseline is not None and (event.occurred_at_ms is None
            or event.occurred_at_ms <= baseline)):
        return session
    failure = session.recovery_state.get("failure", {})
    if (event.action_type.value == "manual_override" and failure
            and failure.get("required_change") not in EXTERNAL_CHANGES):
        # A retry button is not new target evidence or an answer to ambiguity.
        return session
    return replace(session, pending_attempt=None, pending_selection=None, pending_transition_id=None,
                   pending_confirmation_transition_id=None, recovery_state={})


def recovery_scope(snapshot):
    # Layout/window churn is not progress. Bound selection retries for the
    # current graph step, and require explicit retry after exhaustion.
    return "selection:" + snapshot.state_id


def selection_fingerprint(snapshot, raw_nodes, screen_bounds, exclusions):
    """Exclude timestamps/observation IDs; include changes to candidate eligibility."""
    evidence = [(n.node_id, n.parent_id, n.visible_to_user, n.enabled, n.clickable,
                 n.scrollable, n.resource_id, n.window_id, n.bounds_in_screen) for n in raw_nodes]
    return sha256(json.dumps([snapshot.fingerprint, sorted(evidence, key=lambda n: n[0]),
        screen_bounds, exclusions], sort_keys=True).encode()).hexdigest()


def selection_binding(match, nodes, diagnostics):
    """Persist only native target identity and bounded diagnostics, never screen text."""
    node = next(n for n in nodes if n.node_id == match.target_node_id)
    return {"transition_id": match.transition_id,
        "target_node_id": node.node_id, "target_key": match.target_key,
        "target_window_id": node.window_id, "target_resource_id": node.resource_id,
        "target_selection_method": match.match_method, "target_bounds": node.bounds,
        "baseline_value": node.value, "selection_diagnostics": diagnostics}


def _retained_match(session, attempt, snapshot, nodes, graph, exclusions):
    if not session or (attempt and attempt.get("event_key")):
        return None
    selection = attempt or session.pending_selection
    if (not selection or not session.last_snapshot
            or session.last_snapshot.fingerprint != snapshot.fingerprint
            or session.last_snapshot.applied_settings != snapshot.applied_settings
            or selection.get("target_selection_method") != "jev_choice"
            or not isinstance(selection.get("selection_diagnostics", {}).get("confidence"), (int, float))):
        return None
    transition = next((t for t in graph.transitions
                       if t.transition_id == selection.get("transition_id")), None)
    if (transition is None or transition.safety_level == SafetyLevel.BLOCKED
            or graph.states[transition.from_state].verification_only
            or selection.get("target_key") in exclusions.get(transition.transition_id, ())):
        return None
    candidates = [n for n in eligible_nodes(transition, nodes, graph)
                  if n.node_id == selection.get("target_node_id")
                  and n.window_id == selection.get("target_window_id")
                  and n.resource_id == selection.get("target_resource_id")
                  and n.bounds == tuple(selection.get("target_bounds", ()))
                  and target_key(n) == selection.get("target_key")
                  and n.value == selection.get("baseline_value")
                  and (transition.target_role != 'slider'
                       or selector_evidence(n, transition, nodes, graph)[0] >= 0.7)
                  and n.source_confidence is None]
    if len(candidates) != 1:
        return None
    node = candidates[0]
    return MatchResult(transition.from_state, transition.to_state, transition.action_type,
        node.node_id, node.label, node.bounds, "jev_choice",
        selection["selection_diagnostics"]["confidence"],
        transition_id=transition.transition_id, target_key=target_key(node))


def decide_primary(*, resolver, request, session, snapshot, nodes, raw_nodes, graph,
                   goal, verification, attempt, exclusions, screen_bounds):
    info = {"provider": "jev", "mode": "primary", "status": "skipped", "called": False}
    if ((session and session.task_completed) or (verification and verification.task_completed)
            or request.user_confirmation == "rejected"
            or (session and session.total_failed_attempts >= graph.policy.max_failed_attempts)):
        return PrimaryDecision(None, info)

    recovery = session.recovery_state if session else {}
    fingerprint = selection_fingerprint(snapshot, raw_nodes, screen_bounds, exclusions)
    previous_failure = recovery.get("failure")
    changed = recovery.get("selection_fingerprint") != fingerprint
    if recovery.get("waiting_for_user") and (not previous_failure
            or previous_failure.get("required_change") in EXTERNAL_CHANGES or not changed):
        info.update(recovery.get("primary_diagnostics", {}), called=False, paused=True)
        failure = SelectionFailure(**previous_failure) if previous_failure else None
        return PrimaryDecision(None, info, recovery["reason_code"], failure)

    same_screen = bool(session and session.last_snapshot
                       and session.last_snapshot.fingerprint == snapshot.fingerprint)
    action_at = attempt.get("action_at_ms") if attempt else None
    settling = bool(action_at is not None and request.captured_at_ms is not None
                    and request.captured_at_ms - action_at < graph.policy.settle_ms)
    pending_result = bool(attempt and attempt.get("event_key") and verification
        and verification.status in {Status.WAITING_FOR_OBSERVATION, Status.UNKNOWN,
                                    Status.NO_CHANGE, Status.WRONG_STATE})
    if settling or (same_screen and pending_result) or recovery.get("jev_wait_fingerprint") == snapshot.fingerprint:
        info["status"] = "waiting"
        failure = selection_failure(info, cause="settling" if settling else "result_unobservable",
            outcome_uncertain=bool(attempt and attempt.get("event_key") and verification
                and verification.status in {Status.WAITING_FOR_OBSERVATION, Status.UNKNOWN}))
        info["failure"] = failure.to_dict()
        return PrimaryDecision(None, info, failure.reason_code, failure)

    event = request.user_action_event
    retained = (_retained_match(session, attempt, snapshot, nodes, graph, exclusions)
                if not event or event.action_type.value == "observe_only" else None)
    if retained:
        selection = attempt or session.pending_selection
        info.update(selection["selection_diagnostics"], called=False, retained=True)
        return PrimaryDecision(retained, info)

    match = resolver.resolve(nodes=nodes, graph=graph, goal=goal, raw_nodes=raw_nodes,
        session=session, verification=verification, excluded_target_keys=exclusions,
        screen_bounds=screen_bounds, attempt=attempt)
    info.update(resolver.last_diagnostics)
    if match:
        return PrimaryDecision(match, info)
    uncertain = bool(attempt and attempt.get("event_key") and verification
        and verification.status in {Status.WAITING_FOR_OBSERVATION, Status.UNKNOWN})
    failure = selection_failure(info, outcome_uncertain=uncertain,
        cause="result_unobservable" if info.get("status") == "waiting" and pending_result else None)
    info["failure"] = failure.to_dict()
    return PrimaryDecision(None, info, failure.reason_code, failure)
