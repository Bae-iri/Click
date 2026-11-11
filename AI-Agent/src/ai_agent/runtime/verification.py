"""Verify one correlated attempt against its original target and baseline."""
from hashlib import sha256
from math import isfinite
from ai_agent.runtime.wifi import adapter_state
from ai_agent.task_graph.settings import CHOICES
from ai_agent.runtime.settings_controls import CHECKED_PREDICATES
from ai_agent.runtime.setting_choices import PREDICATES as CHOICE_PREDICATES, expected_value as choice_value, system_value, allowed_values

from ai_agent.runtime.targeting import eligible_nodes, selector_evidence, target_key
from ai_agent.runtime.types import VerificationResult, VerificationStatus as Status, SafetyLevel


def correlate_action(session, request, transition):
    attempt = dict(session.pending_attempt) if session and session.pending_attempt else None
    processed = session.processed_event_keys if session else ()
    event = request.user_action_event
    if not attempt or not event or not transition or attempt.get('event_key'):
        return attempt, processed
    if ((event.session_id is not None and event.session_id != request.session_id)
            or (attempt.get('episode_id') is not None and event.episode_id != attempt['episode_id'])):
        return attempt, processed
    expected = {'tap': {'tap_completed'}, 'drag': {'drag_completed', 'tap_completed'},
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
    if (event.occurred_at_ms is None or baseline_time is None
            or event.occurred_at_ms <= baseline_time):
        return attempt, processed
    # A delayed event may describe an earlier action, but cannot claim a change
    # already observed before that action. Retire this guide rather than letting
    # the same event become valid after another observation overwrites the evidence.
    previous = session.last_snapshot
    if (transition.verification_predicate in {'target_value_increased', 'target_value_decreased', *CHECKED_PREDICATES, *CHOICE_PREDICATES}
            and previous and previous.captured_at_ms is not None
            and baseline_time < previous.captured_at_ms <= event.occurred_at_ms):
        observed_value = previous.slider_values.get(attempt['target_node_id'])
        setting = attempt.get('setting', 'font_size')
        observed_scale = applied_value(previous.applied_settings, setting)
        baseline_setting = attempt.get('baseline_setting_value', attempt.get('baseline_font_scale'))
        observed_checked = previous.checked_values.get(attempt['target_node_id'])
        checked_changed = (transition.verification_predicate in CHECKED_PREDICATES
            and type(observed_checked) is bool and observed_checked != attempt.get('baseline_checked'))
        if (checked_changed or (observed_value is not None and attempt.get('baseline_value') is not None
                and observed_value != attempt['baseline_value'])
                or (observed_scale is not None and baseline_setting is not None
                    and observed_scale != baseline_setting)):
            return None, processed
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
    if transition.verification_predicate not in {'state_reached', 'target_value_increased', 'target_value_decreased', *CHECKED_PREDICATES, *CHOICE_PREDICATES}:
        return result(Status.UNKNOWN, 'unsupported_verification_predicate')
    correlated = bool(attempt and attempt.get('event_key')
                      and attempt.get('transition_id') == pending_transition_id)
    observation_time = current_snapshot.captured_at_ms if current_snapshot else None
    action_time = attempt.get('action_at_ms') if attempt else None
    baseline_id = attempt.get('baseline_observation_id') if attempt else None
    stale_observation = bool(current_snapshot and previous_snapshot and (
        current_snapshot.observation_id == previous_snapshot.observation_id
        or (observation_time is not None and previous_snapshot.captured_at_ms is not None
            and observation_time <= previous_snapshot.captured_at_ms)))
    if stale_observation or (correlated and (
            (current_snapshot and current_snapshot.observation_id == baseline_id)
            or (observation_time is not None and action_time is not None and observation_time <= action_time))):
        return result(Status.WAITING_FOR_OBSERVATION, 'observation_precedes_action')
    gap_started = attempt.get('observation_gap_started_at_ms') if attempt else None
    if (not nodes and gap_started is not None and observation_time is not None
            and 0 <= observation_time - gap_started < graph.policy.observation_timeout_ms):
        return result(Status.WAITING_FOR_OBSERVATION, 'transient_screen_unavailable')

    # A manually reached nonterminal screen is progress, not evidence of executing a guide.
    if (transition.verification_predicate == 'state_reached'
            and current_state_id == transition.to_state
            and not graph.states[transition.to_state].verification_only
            and transition.to_state != graph.definition.done_state
            and (not transition.completes_task or correlated)):
        return result(Status.SUCCESS, 'state_reached' if correlated else 'observed_progress',
                      count=correlated, completed=transition.completes_task)
    if (transition.verification_predicate == 'state_reached' and not transition.completes_task
            and navigation_reachable(graph, transition.to_state, current_state_id)):
        # Combined OEM screens / several manual taps can skip navigation screens.
        # This is observed progress, not proof that every skipped guide was executed.
        return VerificationResult(Status.SUCCESS, current_state_id, current_state_id,
                                  evidence='observed_progress', count_attempt=False)
    if not correlated:
        return result(Status.WAITING_FOR_ACTION, 'no_correlated_action')

    if transition.verification_predicate in CHOICE_PREDICATES:
        if (not current_snapshot or not current_snapshot.observation_id or not baseline_id
                or observation_time is None or action_time is None):
            return result(Status.UNKNOWN, 'missing_observation_timing')
        candidates = [n for n in eligible_nodes(transition, nodes, graph)
            if target_key(n) == attempt.get('target_key') and n.node_id == attempt.get('target_node_id')
            and n.window_id == attempt.get('target_window_id') and n.resource_id == attempt.get('target_resource_id')
            and n.bounds == tuple(attempt.get('target_bounds', ()))
            and selector_evidence(n, transition, nodes, graph)[0] >= .7]
        if len(candidates) != 1:
            return result(Status.UNKNOWN, 'mode_choice_unobservable')
        before = attempt.get('baseline_setting_value')
        after = system_value(current_snapshot.applied_settings, graph)
        if type(before) is not int or before not in allowed_values(graph.definition.required_slots.get('setting')) or after is None:
            return result(Status.UNKNOWN, 'system_mode_unobservable')
        if before != choice_value(graph) and after == choice_value(graph):
            return result(Status.SUCCESS, transition.verification_predicate, count=True,
                completed=transition.completes_task, message='The correlated native choice changed the system mode.')

    if transition.verification_predicate in CHECKED_PREDICATES:
        if (not current_snapshot or not current_snapshot.observation_id or not baseline_id
                or observation_time is None or action_time is None):
            return result(Status.UNKNOWN, 'missing_observation_timing')
        candidates = [n for n in eligible_nodes(transition, nodes, graph)
            if target_key(n) == attempt.get('target_key') and n.window_id == attempt.get('target_window_id')
            and selector_evidence(n, transition, nodes, graph)[0] >= 0.7
            and (attempt.get('target_selection_method') != 'jev_choice' or (
                n.node_id == attempt.get('target_node_id') and n.resource_id == attempt.get('target_resource_id')
                and n.bounds == tuple(attempt.get('target_bounds', ()))))]
        before = attempt.get('baseline_checked')
        if len(candidates) != 1 or type(before) is not bool:
            return result(Status.UNKNOWN, 'native_switch_unobservable')
        if graph.definition.required_slots.get('setting') != 'wifi':
            expected = transition.verification_predicate == 'target_checked_on'
            target = candidates[0]
            if (target.node_id != attempt.get('target_node_id')
                    or target.resource_id != attempt.get('target_resource_id')):
                return result(Status.UNKNOWN, 'setting_switch_identity_changed')
            if before is not expected and target.checked is expected:
                return result(Status.SUCCESS, transition.verification_predicate, count=True,
                    completed=transition.completes_task, message='The native setting switch reached the requested checked state.')
        state = adapter_state(current_snapshot.applied_settings) if graph.definition.required_slots.get('setting') == 'wifi' else None
        expected = transition.verification_predicate == 'target_checked_on'
        if state in {0, 2, 4}:
            return result(Status.WAITING_FOR_OBSERVATION, 'wifi_state_transition')
        if graph.definition.required_slots.get('setting') == 'wifi' and (state is None or attempt.get('baseline_setting_value') not in {1, 3}):
            return result(Status.UNKNOWN, 'wifi_state_unobservable')
        if before is not expected and candidates[0].checked is expected and state == (3 if expected else 1):
            return result(Status.SUCCESS, transition.verification_predicate, count=True,
                completed=transition.completes_task, message='Wi-Fi adapter and master switch agree with the requested state.')

    opposite_direction = False
    if transition.verification_predicate in {'target_value_increased', 'target_value_decreased'}:
        if (not current_snapshot or not current_snapshot.observation_id or not baseline_id
                or observation_time is None or action_time is None):
            return result(Status.UNKNOWN, 'missing_observation_timing')
        candidates = [node for node in eligible_nodes(transition, nodes, graph)
                      if target_key(node) == attempt.get('target_key')
                      # Removing unknown text must not let a different anonymous
                      # slider inherit a pending Jev action via page context alone.
                      and (attempt.get('target_selection_method') != 'jev_choice'
                           or (node.node_id == attempt.get('target_node_id')
                               and node.resource_id == attempt.get('target_resource_id')
                               and node.bounds == tuple(attempt.get('target_bounds', ())))
                           or recreated_font_control(node, attempt, transition, graph,
                               current_state_id, current_snapshot, observation_time, action_time))
                      and (node.window_id == attempt.get('target_window_id')
                           or recreated_font_control(node, attempt, transition, graph,
                               current_state_id, current_snapshot, observation_time, action_time))
                      and not node.node_id.startswith('vision:')
                      and selector_evidence(node, transition, nodes, graph)[0] >= 0.7]
        baseline = attempt.get('baseline_value')
        if len(candidates) != 1 or baseline is None or candidates[0].value is None:
            return result(Status.UNKNOWN, 'target_value_unobservable')
        target = candidates[0]
        baseline_min = attempt.get('baseline_range_min')
        baseline_max = attempt.get('baseline_range_max')
        if (not valid_slider_value(baseline, baseline_min, baseline_max)
                or not valid_slider_value(target.value, target.range_min, target.range_max)):
            return result(Status.UNKNOWN, 'target_value_out_of_range')
        if ((baseline_min is not None and target.range_min != baseline_min)
                or (baseline_max is not None and target.range_max != baseline_max)):
            return result(Status.UNKNOWN, 'target_range_changed')
        value = target.value
        changed = value > baseline if transition.verification_predicate.endswith('increased') else value < baseline
        setting = graph.definition.required_slots.get('setting', 'font_size')
        applied_before = attempt.get('baseline_setting_value', attempt.get('baseline_font_scale') if setting == 'font_size' else None)
        applied_after = applied_value(current_snapshot.applied_settings, setting)
        if applied_before is None or applied_after is None:
            return result(Status.UNKNOWN, 'applied_setting_unobservable')
        applied_changed = (applied_after > applied_before if transition.verification_predicate.endswith('increased')
                           else applied_after < applied_before)
        opposite_direction = (value != baseline and applied_after != applied_before
            and not changed and not applied_changed)
        if changed and applied_changed:
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
            return result(Status.NO_CHANGE, 'target_value_changed_opposite_direction' if opposite_direction
                else 'settled_postcondition_not_met', count=True)
        if current_state_id != 'S0_UNKNOWN':
            return result(Status.WRONG_STATE, 'settled_wrong_state', count=True)
    if (observation_time is not None and action_time is not None
            and observation_time - action_time >= graph.policy.observation_timeout_ms):
        return result(Status.UNKNOWN, 'observation_timeout')
    return result(Status.WAITING_FOR_OBSERVATION, 'awaiting_settled_observation')


def navigation_reachable(graph, start, destination):
    """Only traverse navigation; never bypass a setting change or consent boundary."""
    if destination not in graph.states or destination in {'S0_UNKNOWN', graph.definition.done_state}:
        return False
    if graph.states[destination].verification_only or start == destination:
        return False
    seen, pending = set(), [start]
    while pending:
        state = pending.pop()
        if state in seen:
            continue
        seen.add(state)
        for edge in graph.transitions_from(state):
            if (edge.completes_task or edge.verification_predicate != 'state_reached'
                    or edge.safety_level != SafetyLevel.LOW_RISK
                    or edge.action_type.value not in {'tap', 'scroll'}
                    or graph.states[edge.to_state].verification_only
                    or edge.to_state == graph.definition.done_state):
                continue
            if edge.to_state == destination:
                return True
            pending.append(edge.to_state)
    return False


def recreated_font_control(node, attempt, transition, graph, state, snapshot, observed_at, action_at):
    """Font scale or display density can recreate Settings while preserving the native control.

    The caller still requires one matching stable target key, a correlated action,
    valid unchanged range, and movement in the requested direction. A new window
    alone, a text-only match, or preview-only movement never qualifies.
    """
    if (graph.definition.goal_type not in {'settings.font_size.increase', 'settings.font_size.decrease',
            'settings.display_size.increase', 'settings.display_size.decrease'}
            or state != transition.from_state
            or node.window_id is None or attempt.get('target_window_id') is None
            or not node.resource_id or node.resource_id != attempt.get('target_resource_id')
            or observed_at is None or action_at is None
            or not 0 < observed_at - action_at <= graph.policy.observation_timeout_ms):
        return False
    setting = graph.definition.required_slots['setting']
    before = attempt.get('baseline_setting_value') if setting == 'display_size' else attempt.get('baseline_font_scale')
    after = applied_value(snapshot.applied_settings, setting)
    if before is None or after is None:
        return False
    return after > before if transition.verification_predicate.endswith('increased') else after < before


def valid_slider_value(value, minimum=None, maximum=None):
    """Only finite values inside the reported control range are usable evidence."""
    if type(value) not in (int, float) or not isfinite(value):
        return False
    for bound in (minimum, maximum):
        if bound is not None and (type(bound) not in (int, float) or not isfinite(bound)):
            return False
    return ((minimum is None or value >= minimum)
            and (maximum is None or value <= maximum)
            and (minimum is None or maximum is None or minimum <= maximum))


def font_scale(settings):
    """Read persisted Android font preference, never infer it from an OCR/slider label."""
    value = (settings or {}).get('font_scale')
    if ((settings or {}).get('source') != 'android.provider.Settings.System.FONT_SCALE'
            or type(value) not in (int, float) or not isfinite(value) or not 0 < value <= 10):
        return None
    return float(value)


def applied_value(settings, setting):
    """Read only the device value and provenance for the requested setting."""
    if setting == 'font_size':
        return font_scale(settings)
    if setting == 'wifi':
        return adapter_state(settings)
    if setting in CHOICES:
        return system_value(settings, setting=setting)
    fields = {
        'display_size': ('display_density_dpi', 'display_density_source', 'android.content.res.Configuration.densityDpi', 2000),
        'brightness': ('screen_brightness', 'brightness_source', 'android.provider.Settings.System.SCREEN_BRIGHTNESS', 65535),
        'volume': ('media_volume', 'media_volume_source', 'android.media.AudioManager.STREAM_MUSIC', 1000),
    }.get(setting)
    if fields is None:
        return None
    key, source_key, source, maximum = fields
    value = (settings or {}).get(key)
    if (settings or {}).get(source_key) != source or type(value) is not int or not (72 if setting == 'display_size' else 0) <= value <= maximum:
        return None
    return value
