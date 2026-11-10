"""Graph-owned selectors shared by matching and result verification."""
from hashlib import sha256
import re

from ai_agent.runtime.keywords import any_keyword_matches, any_phrase_matches, normalize_text
from ai_agent.runtime.types import ActionType, NormalizedNode, TaskGraph, Transition


def target_key(node: NormalizedNode) -> str:
    # Stable across tree-index and rectangle changes; never persist arbitrary UI text.
    identity = (node.package_name or '', node.resource_id or '', node.role, control_label(node))
    return sha256('\x1f'.join(identity).encode()).hexdigest()


def control_label(node: NormalizedNode) -> str:
    """Samsung SeekBars expose their changing step number as contentDescription."""
    label = normalize_text(node.label_parts[0] if node.role in {'menu_item', 'button', 'app_icon'}
                           and node.label_parts else node.label)
    if node.role == 'slider' and re.fullmatch(r'[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)\s*%?', label):
        return ''
    if node.role == 'toggle':
        from ai_agent.runtime.wifi import WIFI_NAMES, STATUS_NAMES, MASTER_IDS
        if any(normalize_text(w) in WIFI_NAMES for w in node.label_parts or (node.label,)):
            return 'wifi'
        if node.resource_id in MASTER_IDS and label in STATUS_NAMES:
            return ''
    return label


def selector_keywords(transition: Transition, graph: TaskGraph) -> tuple[str, ...]:
    keys = transition.selector.synonym_keys
    # Compatibility for older graphs without explicit references.
    if not keys:
        keys = tuple(key for key, words in graph.synonyms.items()
                     if set(map(normalize_text, words)) & set(map(normalize_text, transition.target_keywords)))
    return tuple(dict.fromkeys((*transition.target_keywords,
        *(word for key in keys for word in graph.synonyms.get(key, ())))))


def eligible_nodes(transition: Transition, nodes: tuple[NormalizedNode, ...], graph: TaskGraph):
    state = graph.states[transition.from_state]
    roles = {'menu_item': {'menu_item', 'button'}, 'slider': {'slider'},
             'app_icon': {'app_icon'}, 'toggle': {'toggle'}, 'radio': {'radio'}}
    allowed_roles = roles.get(transition.target_role, {transition.target_role})
    from ai_agent.runtime.setting_choices import is_choice
    if is_choice(transition, graph):
        allowed_roles = allowed_roles | {'radio'}
    packages = transition.selector.package_candidates or state.package_candidates
    eligible = []
    for node in nodes:
        if not node.enabled or node.role not in allowed_roles:
            continue
        if packages and node.package_name not in packages:
            continue
        if transition.action_type in {ActionType.TAP, ActionType.DRAG} and not node.actionable:
            continue
        if transition.action_type == ActionType.DRAG and node.role != 'slider':
            continue
        if not node.bounds or node.bounds[2] <= node.bounds[0] or node.bounds[3] <= node.bounds[1]:
            continue
        if any_keyword_matches(node.label, transition.selector.excluded_keywords):
            continue
        from ai_agent.runtime.wifi import is_wifi_transition, target_allowed
        if is_wifi_transition(transition, graph) and not target_allowed(node, nodes):
            continue
        from ai_agent.runtime.settings_controls import CONTROLS, CHECKED_PREDICATES, target_allowed as setting_target
        setting = graph.definition.required_slots.get('setting')
        if (setting in CONTROLS and transition.verification_predicate in CHECKED_PREDICATES
                and not setting_target(node, nodes, setting)):
            continue
        from ai_agent.runtime.setting_choices import is_choice, target_allowed as choice_target
        if is_choice(transition, graph) and not choice_target(node, nodes, graph):
            continue
        eligible.append(node)
    return tuple(eligible)


def selector_evidence(node, transition, nodes, graph) -> tuple[float, str]:
    """Return an ordinal evidence score, never a calibrated probability."""
    selector = transition.selector
    if node.resource_id and node.resource_id in selector.resource_ids:
        return 1.0, 'resource_id'
    keywords = selector_keywords(transition, graph)
    labels = node.label_parts or (node.label,)
    names = {normalize_text(part) for part in labels}
    if names.intersection(normalize_text(word) for word in keywords):
        return 0.9, 'exact_label'
    # Short/general aliases identify a whole title, not e.g. 홈 화면 or Always on display.
    specific = tuple(word for word in keywords if len(normalize_text(word)) > 2
                     and normalize_text(word) not in {"screen", "display"})
    if any(any_phrase_matches(label, specific) for label in labels):
        return 0.8, 'keyword'
    if not control_label(node) and selector.allow_unlabelled_in_context:
        # A page title may name a single unlabelled control, never a named other setting.
        context = ' '.join(n.label for n in nodes if n.package_name == node.package_name
                           and n.window_id == node.window_id and n.role == 'text'
                           and not n.actionable
                           and n.structure_role not in {'control_label', 'description'}
                           and not any(_contains_control_label(other, n) for other in nodes))
        if any_keyword_matches(context, selector.context_keywords):
            if len(eligible_nodes(transition, nodes, graph)) == 1:
                return 0.8, 'unique_control_in_context'
    return 0.0, 'none'


def _contains_control_label(control, label):
    """Legacy flat trees still expose bounds: a menu label isn't page context."""
    return (control.node_id != label.node_id and control.package_name == label.package_name
            and control.window_id == label.window_id and (control.actionable or control.scrollable)
            and control.bounds and label.bounds
            and control.bounds[0] <= label.bounds[0] and control.bounds[1] <= label.bounds[1]
            and control.bounds[2] >= label.bounds[2] and control.bounds[3] >= label.bounds[3])
