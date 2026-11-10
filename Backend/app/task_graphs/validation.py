"""Decode versioned JSON graphs into AI1 contracts and validate their topology."""
from dataclasses import fields

from pydantic import TypeAdapter

from ai_agent.runtime.types import (
    TaskGraph, TaskDefinition, TaskState, Transition, TargetSelector, RuntimePolicy,
    ActionType, SafetyLevel,
)

ADAPTER = TypeAdapter(TaskGraph)
from ai_agent.runtime.settings_controls import CONTROLS
from ai_agent.task_graph.settings import BASE_PREDICATES, supported_predicates, CHOICE_PREDICATES, choice_spec, choice_predicate

PREDICATES = BASE_PREDICATES


def _fields(body, contract):
    if not isinstance(body, dict) or body.keys() - {field.name for field in fields(contract)}:
        raise ValueError("Unexpected graph fields")


def load_graph(body, *, executable=True):
    _fields(body, TaskGraph)
    _fields(body.get("definition"), TaskDefinition)
    if not isinstance(body.get("states"), dict) or not isinstance(body.get("transitions"), list):
        raise ValueError("Invalid graph collections")
    for state in body["states"].values():
        _fields(state, TaskState)
    for transition in body["transitions"]:
        _fields(transition, Transition)
        _fields(transition.get("selector", {}), TargetSelector)
    _fields(body.get("policy", {}), RuntimePolicy)
    graph = ADAPTER.validate_python(body)
    definition = graph.definition
    if not definition.task_id or not definition.goal_type:
        raise ValueError("Missing graph identity")
    if definition.done_state not in graph.states or not definition.start_states:
        raise ValueError("Missing terminal or start state")
    if any(key != state.state_id for key, state in graph.states.items()):
        raise ValueError("State ID mismatch")
    if any(not 0 <= state.confidence_threshold <= 1 for state in graph.states.values()):
        raise ValueError("Invalid state confidence threshold")
    if any(state not in graph.states for state in definition.start_states):
        raise ValueError("Unknown start state")
    ids = [transition.transition_id for transition in graph.transitions]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate transition ID")
    for transition in graph.transitions:
        if not transition.transition_id:
            raise ValueError("Missing transition ID")
        if transition.from_state not in graph.states or transition.to_state not in graph.states:
            raise ValueError("Dangling transition")
        if transition.from_state == definition.done_state:
            raise ValueError("Terminal state has outgoing transitions")
        supported = supported_predicates(definition.required_slots, definition.task_type)
        if executable and transition.verification_predicate not in supported:
            raise ValueError("Unsupported completion predicate")
        if transition.verification_predicate in CHOICE_PREDICATES:
            choice = choice_spec(definition.required_slots)
            if (not choice
                    or transition.action_type != ActionType.TAP or transition.target_role != 'menu_item'
                    or transition.safety_level != SafetyLevel.CONFIRM_REQUIRED or not transition.completes_task
                    or transition.verification_predicate != choice_predicate(definition.required_slots)
                    or set(transition.target_keywords) != set(choice['labels'])):
                raise ValueError('Mode choice requires an authored target, confirmation and system-value verification')
        if executable and transition.verification_predicate in {'target_checked_on', 'target_checked_off'} and (
                transition.action_type != ActionType.TAP or transition.target_role != 'toggle'
                or transition.safety_level != SafetyLevel.CONFIRM_REQUIRED or not transition.completes_task
                or transition.verification_predicate != 'target_checked_' + definition.required_slots.get('state', '')):
            raise ValueError('Switch requires a confirmed native action and exact checked-state verification')
        if transition.verification_predicate == 'manual_result_required' and (
                definition.task_type != 'settings_assisted' or transition.action_type != ActionType.OBSERVE_ONLY
                or transition.requires_user_action or transition.target_role is not None
                or transition.safety_level != SafetyLevel.LOW_RISK or not transition.completes_task
                or not graph.states[transition.from_state].required_keyword_groups):
            raise ValueError('Manual handoff cannot mutate a setting or prove completion')
        if definition.task_type == 'settings_assisted' and (
                transition.action_type not in {ActionType.TAP, ActionType.OBSERVE_ONLY}
                or transition.target_role not in {None, 'menu_item', 'app_icon'}
                or (transition.completes_task and transition.verification_predicate != 'manual_result_required')):
            raise ValueError('Assisted routes stop before setting changes')
        if transition.verification_predicate == 'settings_page_visible'  and (
                definition.task_type != 'settings_navigation'
                or definition.required_slots.get('action') != 'open'
                or transition.action_type != ActionType.OBSERVE_ONLY or transition.requires_user_action
                or transition.safety_level != SafetyLevel.LOW_RISK or not transition.completes_task
                or not graph.states[transition.from_state].required_keyword_groups):
            raise ValueError('Page visibility is restricted to read-only navigation')
        if definition.task_type == 'settings_navigation' and transition.action_type not in {ActionType.TAP, ActionType.OBSERVE_ONLY}:
            raise ValueError('Navigation cannot modify controls')
        if definition.task_type == 'settings_navigation' and transition.target_role not in {None, 'menu_item', 'app_icon'}:
            raise ValueError('Navigation targets menus, never setting controls')
        if transition.safety_level == SafetyLevel.CONFIRM_REQUIRED and not transition.confirmation_message:
            raise ValueError("Missing confirmation message")
        if transition.completes_task and transition.action_type != ActionType.OBSERVE_ONLY:
            if transition.safety_level != SafetyLevel.CONFIRM_REQUIRED:
                raise ValueError("Setting mutation requires confirmation")
        if transition.requires_user_action and not transition.target_keywords:
            raise ValueError("Action has no target keywords")
        if executable and transition.target_role == 'slider':
            direction = definition.required_slots.get('direction')
            expected = {'increase': 'target_value_increased', 'decrease': 'target_value_decreased'}.get(direction)
            if expected is not None and (transition.action_type != ActionType.DRAG or transition.verification_predicate != expected
                    or transition.safety_level != SafetyLevel.CONFIRM_REQUIRED):
                raise ValueError('Slider requires directional device verification and confirmation')
            if definition.required_slots.get('setting') == 'volume':
                if transition.selector.allow_unlabelled_in_context:
                    raise ValueError('Media volume requires an identified channel control')
    reachable = set(definition.start_states)
    for _ in graph.states:
        reachable.update(t.to_state for t in graph.transitions if t.from_state in reachable)
    if definition.done_state not in reachable:
        raise ValueError("Unreachable terminal state")
    if executable and not any(t.completes_task for t in graph.transitions):
        raise ValueError("Graph has no verified completion")
    return graph
