"""Authored native mode choices, independently verified against a system value.

Selection decorations never prove that a system setting changed.
"""
from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.settings_controls import CONTROLS

PREDICATES = {'system_night_mode_on', 'system_night_mode_off'}
SOURCE = 'android.app.UiModeManager.getNightMode'


def system_value(settings):
    value = (settings or {}).get('night_mode')
    if (settings or {}).get('night_mode_source') != SOURCE or type(value) is not int:
        return None
    # Automatic/custom schedules do not denote an explicitly selected mode.
    return value if value in {1, 2} else None


def expected_value(graph):
    return 2 if graph.definition.required_slots.get('state') == 'on' else 1


def is_choice(transition, graph):
    return (graph.definition.required_slots.get('setting') == 'dark_mode'
        and transition.verification_predicate in PREDICATES)


def target_allowed(node, nodes, graph):
    from ai_agent.runtime.classifier import _screen_titles
    setting = graph.definition.required_slots.get('setting')
    contract = CONTROLS.get(setting, {}).get('choices')
    if not contract or setting != 'dark_mode':
        return False
    relevant = tuple(n for n in nodes if n.package_name == node.package_name and n.window_id == node.window_id
        and n.source_confidence is None and not n.node_id.startswith('vision:'))
    titles = {normalize_text(n.label) for n in _screen_titles(relevant)}
    if not titles.intersection(normalize_text(w) for w in CONTROLS[setting]['pages']):
        return False
    # A Samsung mode card is a native clickable container with a finite child
    # title; paired identities are required, never a free-form button label.
    choices = {}
    for state, spec in contract.items():
        names = {normalize_text(w) for w in spec['labels']}
        found = [n for n in relevant if n.actionable and n.enabled
            and n.role in {'menu_item', 'button', 'radio'} and n.resource_id in spec['resource_ids']
            and names.intersection(n.label_parts or (n.label,))]
        if len(found) != 1:
            return False
        choices[state] = found[0]
    if len({n.node_id for n in choices.values()}) != len(choices):
        return False
    left, right = choices['off'].bounds, choices['on'].bounds
    if not (left[2] <= right[0] or right[2] <= left[0] or left[3] <= right[1] or right[3] <= left[1]):
        return False
    return node in relevant and node == choices[graph.definition.required_slots['state']]


def already_satisfied(graph, nodes, settings):
    from ai_agent.runtime.types import VerificationResult, VerificationStatus
    if graph.definition.required_slots.get('setting') != 'dark_mode' or system_value(settings) != expected_value(graph):
        return None
    for edge in graph.transitions:
        if is_choice(edge, graph) and sum(target_allowed(n, nodes, graph) for n in nodes) == 1:
            return VerificationResult(VerificationStatus.SUCCESS, edge.from_state, edge.to_state,
                evidence='system_mode_already_set', task_completed=True, count_attempt=False)
    return None
