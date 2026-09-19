"""Verify one correlated attempt against its original target and baseline."""
from hashlib import sha256

from ai_agent.runtime.targeting import eligible_nodes, selector_evidence, target_key
from ai_agent.runtime.types import VerificationResult, VerificationStatus as Status


def correlate_action(session, request, transition):
    attempt = dict(session.pending_attempt) if session and session.pending_attempt else None
    processed = session.processed_event_keys if session else ()
    event = request.user_action_event
    if not attempt or not event or not transition or attempt.get('event_key'):
        return attempt, processed
    expected = {'tap': {'tap_completed'}, 'drag': {'drag_completed'},
                'scroll': {'scroll_completed', 'scroll_failed'}}.get(transition.action_type.value, set())
    if (event.action_type.value not in expected
            or event.guide_command_id != attempt['guide_command_id']
            or event.target_node_id != attempt['target_node_id']
            or (event.target_bounds is not None and tuple(event.target_bounds) != tuple(attempt['target_bounds']))):
        return attempt, processed
    # Older clients can omit event_id; one guide still denotes exactly one attempt.
    event_key = sha256((event.event_id or attempt['guide_command_id']).encode()).hexdigest()
    if event_key in processed:
        return attempt, processed
    baseline_time = attempt.get('baseline_captured_at_ms')
    if event.occurred_at_ms is not None and baseline_time is not None and event.occurred_at_ms < baseline_time:
        return attempt, processed
    attempt.update(event_key=event_key, action_at_ms=event.occurred_at_ms,
                   action_type=event.action_type.value)
    return attempt, (*processed, event_key)[-128:]


def verify_transition(*, graph, previous_state_id, pending_transition_id, current_state_id,
                      previous_snapshot=None, current_snapshot=None, attempt=None, nodes=()):
    if not previous_state_id or not pending_transition_id:
        return None
    transition = next((t for t in graph.transitions if t.transition_id == pending_transition_id), None)
    if transition is None:
        return VerificationResult(Status.UNKNOWN, current_state_id, evidence='unknown_transition')
    def result(status, evidence, *, count=False, completed=False, message=None):
        return VerificationResult(status, current_state_id, transition.to_state, message,
                                  attempt_id=attempt.get('attempt_id') if attempt else None,
                                  count_attempt=count, task_completed=completed, evidence=evidence)
    if transition.verification_predicate not in {'state_reached', 'target_value_increased', 'target_value_decreased'}:
        return result(Status.UNKNOWN, 'unsupported_verification_predicate')
    correlated = bool(attempt and attempt.get('event_key'))
    observation_time = current_snapshot.captured_at_ms if current_snapshot else None
    action_time = attempt.get('action_at_ms') if attempt else None
    baseline_id = attempt.get('baseline_observation_id') if attempt else None
    stale_observation = bool(current_snapshot and previous_snapshot and (
        current_snapshot.observation_id == previous_snapshot.observation_id
        or (observation_time is not None and previous_snapshot.captured_at_ms is not None
            and observation_time <= previous_snapshot.captured_at_ms)))
    if correlated and (stale_observation
            or (current_snapshot and current_snapshot.observation_id == baseline_id)
            or (observation_time is not None and action_time is not None and observation_time < action_time)):
        return result(Status.WAITING_FOR_OBSERVATION, 'observation_precedes_action')

    # A manually reached nonterminal screen is progress, not evidence of executing a guide.
    if (transition.verification_predicate == 'state_reached'
            and current_state_id == transition.to_state
            and not graph.states[transition.to_state].verification_only
            and transition.to_state != graph.definition.done_state
            and (not transition.completes_task or correlated)):
        return result(Status.SUCCESS, 'state_reached' if correlated else 'observed_progress',
                      count=correlated, completed=transition.completes_task)
    if not correlated:
        return result(Status.WAITING_FOR_ACTION, 'no_correlated_action')

    if transition.verification_predicate in {'target_value_increased', 'target_value_decreased'}:
        candidates = [node for node in eligible_nodes(transition, nodes, graph)
                      if target_key(node) == attempt.get('target_key')
                      and node.window_id == attempt.get('target_window_id')
                      and selector_evidence(node, transition, nodes, graph)[0] >= 0.7]
        baseline = attempt.get('baseline_value')
        if len(candidates) != 1 or baseline is None or candidates[0].value is None:
            return result(Status.UNKNOWN, 'target_value_unobservable')
        value = candidates[0].value
        changed = value > baseline if transition.verification_predicate.endswith('increased') else value < baseline
        if changed:
            return result(Status.SUCCESS, transition.verification_predicate, count=True,
                          completed=transition.completes_task,
                          message='Requested target value changed in the requested direction.')

    # Failure needs two distinct post-action observations separated by a settling interval.
    # A missing timestamp is insufficient to declare failure, even if an event was supplied.
    settled = bool(current_snapshot and previous_snapshot
        and observation_time is not None and action_time is not None
        and previous_snapshot.captured_at_ms is not None
        and previous_snapshot.captured_at_ms >= action_time
        and observation_time - previous_snapshot.captured_at_ms >= graph.policy.settle_ms
        and current_snapshot.observation_id != previous_snapshot.observation_id
        and current_snapshot.fingerprint == previous_snapshot.fingerprint)
    if settled:
        if current_state_id == transition.from_state:
            return result(Status.NO_CHANGE, 'settled_postcondition_not_met', count=True)
        if current_state_id != 'S0_UNKNOWN':
            return result(Status.WRONG_STATE, 'settled_wrong_state', count=True)
    if (observation_time is not None and action_time is not None
            and observation_time - action_time >= graph.policy.observation_timeout_ms):
        return result(Status.UNKNOWN, 'observation_timeout')
    return result(Status.WAITING_FOR_OBSERVATION, 'awaiting_settled_observation')
