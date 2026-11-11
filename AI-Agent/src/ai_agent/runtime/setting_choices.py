"""Authored native mode choices, verified against independent finite system values."""
from itertools import combinations
from ai_agent.runtime.keywords import normalize_text
from ai_agent.task_graph.settings import CHOICES, CHOICE_PREDICATES, choice_spec, choice_predicate

PREDICATES = CHOICE_PREDICATES
SOURCE = 'android.app.UiModeManager.getNightMode'  # Legacy dark-mode provenance.


def allowed_values(setting):
    return {option['value'] for option in CHOICES.get(setting, {}).get('options', {}).values()}


def system_value(settings, graph=None, *, setting=None):
    setting = setting or (graph.definition.required_slots.get('setting') if graph else 'dark_mode')
    contract = CHOICES.get(setting)
    if not contract:
        return None
    source = contract['system']
    value = (settings or {}).get(source['key'])
    if ((settings or {}).get(source['key'] + '_source') != source['source']
            or type(value) is not int or value not in allowed_values(setting)):
        return None
    return value


def expected_value(graph):
    option = choice_spec(graph.definition.required_slots)
    return option['value'] if option else None


def is_choice(transition, graph):
    predicate = choice_predicate(graph.definition.required_slots)
    return predicate is not None and transition.verification_predicate == predicate


def target_allowed(node, nodes, graph):
    from ai_agent.runtime.classifier import _screen_titles
    contract = CHOICES.get(graph.definition.required_slots.get('setting'))
    if not contract or not choice_spec(graph.definition.required_slots):
        return False
    relevant = tuple(n for n in nodes if n.package_name == node.package_name and n.window_id == node.window_id
        and n.source_confidence is None and not n.node_id.startswith('vision:'))
    titles = {normalize_text(n.label) for n in _screen_titles(relevant)}
    if not titles.intersection(normalize_text(word) for word in contract['pages']):
        return False
    choices = {}
    for name, spec in contract['options'].items():
        names = {normalize_text(word) for word in spec['labels']}
        found = [n for n in relevant if n.actionable and n.enabled
            and n.role in {'menu_item', 'button', 'radio'} and n.resource_id in spec['resource_ids']
            and names.intersection(n.label_parts or (n.label,))]
        if len(found) != 1:
            return False
        choices[name] = found[0]
    if len({n.node_id for n in choices.values()}) != len(choices):
        return False
    for left, right in combinations((n.bounds for n in choices.values()), 2):
        if not (left[2] <= right[0] or right[2] <= left[0] or left[3] <= right[1] or right[3] <= left[1]):
            return False
    requested = contract['state_options'][graph.definition.required_slots['state']]
    return node in relevant and node == choices[requested]


def already_satisfied(graph, nodes, settings):
    from ai_agent.runtime.types import VerificationResult, VerificationStatus
    expected = expected_value(graph)
    if expected is None or system_value(settings, graph) != expected:
        return None
    for edge in graph.transitions:
        if is_choice(edge, graph) and sum(target_allowed(n, nodes, graph) for n in nodes) == 1:
            return VerificationResult(VerificationStatus.SUCCESS, edge.from_state, edge.to_state,
                evidence='system_mode_already_set', task_completed=True, count_attempt=False)
    return None
