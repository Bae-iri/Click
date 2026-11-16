"""Cause-specific recovery policy. Observation retries never count as failed actions."""
from dataclasses import dataclass, field
from hashlib import sha256

from ai_agent.runtime.replanner import decide_replan
from ai_agent.runtime.failures import EXTERNAL_CHANGES, SelectionFailure, provider_attempt_count
from ai_agent.runtime.targeting import selector_evidence
from ai_agent.runtime.types import ReplanDecision, VerificationStatus as Status


@dataclass(frozen=True)
class RecoveryPlan:
    replan: ReplanDecision
    decision: str = "continue"
    reason_code: str = "NONE"
    next_action: str = "none"
    waiting_for: str = "none"
    state: dict = field(default_factory=dict)
    target_label: str | None = None
    failure: SelectionFailure | None = None

    def to_dict(self):
        result = {"schema_version": "ai1-recovery.v1", "decision": self.decision,
                "reason_code": self.reason_code, "next_action": self.next_action,
                "waiting_for": self.waiting_for, "recoverable": self.decision != "continue",
                "attempts": self.state.get("attempts", 0),
                "retry_after_ms": self.state.get("retry_after_ms", 0),
                "options": ["retry", "manual_navigation", "cancel"] if self.waiting_for == "user" else []}
        if self.failure:
            result["failure"] = self.failure.to_dict()
            if self.waiting_for == "user":
                result["options"] = (["retry_after_change", "manual_navigation", "cancel"]
                    if self.failure.required_change in EXTERNAL_CHANGES else
                    ["manual_navigation", "cancel"])
        else:
            from ai_agent.runtime.environment import recovery_rule
            rule = recovery_rule(self.reason_code)
            result["failure"] = {"reason": self.reason_code, **rule} if rule else {"reason": self.reason_code}
        return result


def manual_navigation_target(graph, match, nodes):
    if match.fallback_reason != "target_not_found" or match.state_id == "S0_UNKNOWN":
        return None
    from ai_agent.runtime.capabilities import navigation_state
    if not navigation_state(graph, match.state_id):return None
    edge = next((t for t in graph.transitions if t.transition_id == match.transition_id), None)
    if not edge or edge.completes_task or edge.action_type.value != "tap":
        return None
    packages = edge.selector.package_candidates or graph.states[match.state_id].package_candidates
    relevant = tuple(n for n in nodes if not packages or n.package_name in packages)
    if not any(n.scrollable and n.enabled for n in relevant):
        return None
    # A visible but inaccessible/ambiguous item needs better evidence, not scrolling.
    if any(n.label and selector_evidence(n, edge, relevant, graph)[0] > 0 for n in relevant):
        return None
    return edge.target_keywords[0] if edge.target_keywords else None


def decide_recovery(*, graph, session, snapshot, match, nodes, verification,
                    failed_node_ids, total_failed_attempts, state_evidence,
                    awaiting_vision_consent=False, vision_failed=False,
                    within_settling_interval=False, user_retry=False, completed=False,
                    selection_failure=None, primary_failure=None,
                    selection_fingerprint=None, selection_paused=False, selection_called=False):
    base = decide_replan(session=session, verification_result=verification,
        failed_node_ids=failed_node_ids, total_failed_attempts=total_failed_attempts,
        max_failed_attempts=graph.policy.max_failed_attempts, match_result=match)
    if completed:
        return RecoveryPlan(ReplanDecision(False))

    def plan(decision, reason, action, waiting, state=None, label=None, strategy=None):
        state = dict(state or {})
        if primary_failure:
            state.update(failure=primary_failure.to_dict(), selection_fingerprint=selection_fingerprint)
        if waiting == "user":
            state.update(waiting_for_user=True, decision=decision,
                         reason_code=reason, next_action=action)
        return RecoveryPlan(ReplanDecision(True, reason, failed_node_ids,
            strategy=strategy or ("backend_reselection" if decision == "handoff" else
                                  "wait_for_action" if waiting == "user" else "reobserve")),
            decision, reason, action, waiting, state, label, primary_failure)

    if total_failed_attempts >= graph.policy.max_failed_attempts:
        return plan("handoff", "ACTION_BUDGET_EXHAUSTED", "reselect_graph", "user")
    if state_evidence.get("settings_subpage") and graph.definition.target_app == "android_settings":
        return plan("wait", "SETTINGS_OFF_PATH", "go_back", "user")
    if awaiting_vision_consent:
        previous = dict(session.recovery_state) if session else {}
        return plan("recover", "OBSERVATION_INSUFFICIENT", "vision_analysis", "consent", previous)

    graph_packages = {package for state in graph.states.values() for package in state.package_candidates}
    if (match.state_id == "S0_UNKNOWN" and match.transition_id == "S0_TO_S1_OPEN_SETTINGS"
            and (match.fallback_reason or selection_failure) and nodes
            and not any(n.package_name in graph_packages for n in nodes)):
        return plan("wait", "APP_NOT_OPEN", "open_settings", "user")

    waiting_result = bool(verification and verification.status in {Status.UNKNOWN, Status.WAITING_FOR_OBSERVATION})
    label = manual_navigation_target(graph, match, nodes)
    if primary_failure and primary_failure.next_action != "reobserve":
        from ai_agent.runtime.primary import recovery_scope
        scope = recovery_scope(snapshot)
        old = dict(session.recovery_state) if session else {}
        # Actual native page navigation already retires recovery in the engine.
        # A classifier/scope change or a legacy checkpoint must not refund it.
        if user_retry:
            old = {}
        state = {**old, "scope": scope, "failure": primary_failure.to_dict(),
                 "selection_fingerprint": selection_fingerprint,
                 "attempts": old.get("attempts", 0) + int(not selection_paused),
                 "provider_attempts": provider_attempt_count(old) + int(selection_called),
                 "retry_after_ms": 0}
        reason, action = primary_failure.reason_code, primary_failure.next_action
        # Keep native list-search evidence without discarding the original cause.
        if primary_failure.cause in {"no_candidates", "abstained"}:
            if label and not waiting_result:
                reason, action = "TARGET_NOT_VISIBLE", "manual_navigation"
            elif match.fallback_reason == "ambiguous_target":
                reason, action = "TARGET_AMBIGUOUS", "ask_user"
        return plan("wait", reason, action, "user", state, label=label)
    if label and not selection_failure and not waiting_result and not (session and session.failed_target_keys):
        return plan("wait", "TARGET_NOT_VISIBLE", "manual_navigation", "user", label=label)

    pending = session.pending_attempt if session else None
    target = next((n for n in nodes if n.node_id == match.target_node_id), None)
    if (pending and not pending.get("event_key") and target
            and match.target_key == pending.get("target_key") and target.value is not None
            and pending.get("baseline_value") is not None and target.value != pending["baseline_value"]):
        return plan("wait", "ACTION_EVENT_MISSING", "user_assistance", "user")

    if not selection_failure and not waiting_result and not match.fallback_reason:
        return RecoveryPlan(base)

    reason = selection_failure or ("ACTION_RESULT_UNOBSERVABLE" if waiting_result else
              "VISION_UNAVAILABLE" if vision_failed else
              "STATE_AMBIGUOUS" if state_evidence.get("ambiguous") else
              "OBSERVATION_INSUFFICIENT" if not nodes else
              "STATE_UNKNOWN" if match.state_id == "S0_UNKNOWN" else
              "TARGET_AMBIGUOUS" if match.fallback_reason == "ambiguous_target" else
              "GRAPH_PATH_UNAVAILABLE" if match.fallback_reason == "no_transition" else "TARGET_NOT_FOUND")
    if selection_failure:
        from ai_agent.runtime.primary import recovery_scope
        scope = recovery_scope(snapshot)
    else:
        scope = f"{match.state_id}:{match.transition_id}:{'verification' if waiting_result else 'navigation'}"
    old = dict(session.recovery_state) if session else {}
    if selection_failure and old.get('waiting_for_user') and not user_retry:
        return plan(old.get("decision", "wait"), old.get("reason_code", reason),
                    old.get("next_action", "user_assistance"), "user", old)
    if user_retry or (not selection_failure and old.get("scope") != scope):
        old = {}
    if old.get("waiting_for_user"):
        return plan(old.get("decision", "wait"), old.get("reason_code", reason),
                    old.get("next_action", "user_assistance"), "user", old)
    now = snapshot.captured_at_ms
    observation_key = sha256((snapshot.observation_id or "").encode()).hexdigest()
    started = old.get("started_at_ms", now)
    last_at = old.get("last_attempt_at_ms")
    spaced = now is None or last_at is None or now - last_at >= graph.policy.settle_ms
    attempts = old.get("attempts", 0)
    if not within_settling_interval and observation_key != old.get("observation_key") and spaced:
        attempts += 1
        last_at = now
    state = {"scope": scope, "attempts": attempts, "started_at_ms": started,
             "last_attempt_at_ms": last_at, "observation_key": observation_key,
             "retry_after_ms": graph.policy.settle_ms,
             "strategy_attempts": {"reobserve": attempts}}
    elapsed = max(0, now - started) if now is not None and started is not None else 0
    exhausted = attempts >= graph.policy.recovery_limit or elapsed >= graph.policy.observation_timeout_ms
    if within_settling_interval:
        return plan("recover", "WAITING_FOR_SETTLE", "reobserve", "observation", state)
    if reason == "GRAPH_PATH_UNAVAILABLE" or (exhausted and session and session.failed_target_keys):
        return plan("handoff", reason, "reselect_graph", "user", state)
    if exhausted or vision_failed:
        return plan("wait", reason, "user_assistance", "user", state)
    return plan("recover", reason, "reobserve", "observation", state)


def stale_observation_recovery():
    return RecoveryPlan(ReplanDecision(True, strategy="reobserve"), "recover", "OBSERVATION_STALE",
                        "reobserve", "observation").to_dict()
