"""Privacy bounded execution evidence shared by online producers and offline consumers."""
from hashlib import sha256
import math

SCHEMA = "agent-execution-event.v1"
REASONS = {
    "target_value_increased", "target_value_decreased", "target_value_unchanged",
    "expected_state_observed", "no_correlated_action", "verified_attempt_failed",
    "failed_attempt_budget_exhausted", "observation_recovery_exhausted",
    "target_not_found", "ambiguous_target", "waiting_for_fresh_observation",
    "unknown_transition", "unsupported_verification_predicate", "observation_precedes_action",
    "state_reached", "observed_progress", "target_value_unobservable", "settled_postcondition_not_met",
    "target_value_out_of_range", "target_range_changed",
    "user_back_navigation", "user_home_navigation",
    "settled_wrong_state", "observation_timeout", "awaiting_settled_observation",
}


def digest(value):
    return sha256(str(value).encode()).hexdigest() if value is not None else None


def values(snapshot):
    return {digest(key): value for key, value in sorted((snapshot.slider_values if snapshot else {}).items())[:16]
            if type(value) in (int, float) and math.isfinite(value) and abs(value) <= 1_000_000}


def produce(result, before, after, target_role="other", navigation_action=None, *, configuration=None):
    verification = result.verification_result
    replan = result.debug.get("replan_decision") or {}
    evidence = verification.evidence if verification else None
    reason = evidence if evidence in REASONS else replan.get("reason")
    navigation = navigation_action in {"back_navigated", "home_navigated"}
    if navigation:
        reason = "user_home_navigation" if navigation_action == "home_navigated" else "user_back_navigation"
    match = result.match_result
    pending = after.pending_attempt if after else None
    bound = pending if pending and pending.get("guide_command_id") == result.guide_command.guide_command_id else None
    baseline_ms = (before.pending_attempt or {}).get("baseline_captured_at_ms") if before else None
    after_ms = after.last_snapshot.captured_at_ms if after and after.last_snapshot else None
    duration = after_ms - baseline_ms if type(baseline_ms) is int and type(after_ms) is int else None
    duration = duration if duration is not None and 0 <= duration <= 86_400_000 else None
    event = {"schema_version": SCHEMA, "state_id": result.current_state_id,
            "before_state_id": before.current_state_id if before else None,
            "attempt_hash": digest((before.pending_attempt or {}).get("attempt_id")) if before else None,
            "previous_guide_hash": digest(before.last_guide_command_id) if before else None,
            "attempt_target_hash": digest((before.pending_attempt or {}).get("target_key")) if before else None,
            "before_observation_hash": digest(before.last_snapshot.observation_id) if before and before.last_snapshot else None,
            "after_observation_hash": digest(after.last_snapshot.observation_id) if after and after.last_snapshot else None,
            "guide_hash": digest(result.guide_command.guide_command_id),
            "action_type": result.guide_command.action_type.value,
            "command_type": result.guide_command.command_type.value,
            "target": {"node_hash": digest(bound.get("target_key")) if bound else digest(match.target_key) if match else None,
                       "role": target_role if target_role in {"slider", "button", "menu_item", "other"} else "other",
                       "transition_id": bound.get("transition_id") if bound else match.transition_id if match else None},
            "verification_status": "WAITING_FOR_OBSERVATION" if navigation else verification.status.value if verification else None,
            "count_attempt": False if navigation else bool(verification and verification.count_attempt),
            "duration_ms": duration if verification and verification.count_attempt else None,
            "task_completed": False if navigation else bool(verification and verification.task_completed),
            "reason_code": reason if reason in REASONS else "other" if reason else None,
            "before_values": values(before.last_snapshot if before else None),
            "after_values": values(after.last_snapshot if after else None)}
    from ai_agent.telemetry.jev_decisions import decision_evidence
    decision = decision_evidence(result.debug, configuration)
    if decision is not None:
        event["jev_decision"] = decision
    return event
