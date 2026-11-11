"""Authored switch identities. Native checked state is UI evidence, not connectivity.

No model, selected flag, OCR text, or guessed default can supply checked state.
Wi-Fi additionally retains its independent adapter-state verifier.
"""
from ai_agent.task_graph.settings import DOCUMENT, CONTROLS
from ai_agent.runtime.keywords import normalize_text

CHECKED_PREDICATES = {'target_checked_on', 'target_checked_off'}
STATUS_NAMES = {normalize_text(w) for w in ('사용 중', '사용 안 함', '사용', '켜짐', '꺼짐', 'On', 'Off')}
GENERIC_LABELS = STATUS_NAMES | {normalize_text(w) for w in ('지금 사용', 'Use now', '지금 켜기', 'Turn on now')}


def is_checked_transition(transition, graph):
    return (graph.definition.required_slots.get('setting') in {*CONTROLS, 'wifi'}
        and transition.verification_predicate in CHECKED_PREDICATES)


def target_allowed(node, nodes, setting):
    from ai_agent.runtime.classifier import _screen_titles
    from ai_agent.runtime.wifi import MASTER_IDS
    contract = CONTROLS.get(setting)
    if (not contract or node.role != 'toggle' or not node.checkable or type(node.checked) is not bool
            or node.source_confidence is not None or node.node_id.startswith('vision:')):
        return False
    titles = {normalize_text(n.label) for n in _screen_titles(nodes)
        if n.package_name == node.package_name and n.window_id == node.window_id
        and n.source_confidence is None and not n.node_id.startswith('vision:')}
    pages = {normalize_text(w) for w in contract['pages']}
    if not titles.intersection(pages):
        return False
    labels = {normalize_text(w) for w in node.label_parts or (node.label,) if w}
    names = {normalize_text(w) for w in contract['labels']}
    if labels.intersection(names - GENERIC_LABELS):
        return True
    # Anonymous or generic master switches require a dedicated page, not a
    # category such as Display containing several unrelated settings.
    dedicated = pages - {normalize_text(w) for w in (*contract['categories'], *contract['intermediate'])}
    specific = titles.intersection(dedicated)
    return bool(specific and node.resource_id in MASTER_IDS
        and labels.issubset(STATUS_NAMES | names.intersection(GENERIC_LABELS))
        and sum(n.role == 'toggle' and n.package_name == node.package_name
            and n.window_id == node.window_id for n in nodes) == 1)


def already_satisfied(graph, nodes, settings):
    from ai_agent.runtime.wifi import already_satisfied as wifi_already
    from ai_agent.runtime.targeting import eligible_nodes, selector_evidence
    from ai_agent.runtime.types import VerificationResult, VerificationStatus
    setting = graph.definition.required_slots.get('setting')
    if setting == 'wifi':
        return wifi_already(graph, nodes, settings)
    from ai_agent.task_graph.settings import CHOICES
    if setting in CHOICES:
        from ai_agent.runtime.setting_choices import already_satisfied as choice_already
        found = choice_already(graph, nodes, settings)
        if found is not None:
            return found
    for edge in graph.transitions:
        if not is_checked_transition(edge, graph):
            continue
        targets = [n for n in eligible_nodes(edge, nodes, graph)
            if selector_evidence(n, edge, nodes, graph)[0] >= 0.7]
        if len(targets) == 1 and targets[0].checked is (graph.definition.required_slots.get('state') == 'on'):
            return VerificationResult(VerificationStatus.SUCCESS, edge.from_state, edge.to_state,
                evidence='native_switch_already_set', task_completed=True, count_attempt=False)
    return None


def manual_handoff(graph, nodes, raw_nodes):
    """An independent safe heading grants instructions, never technical completion."""
    from ai_agent.runtime.classifier import _screen_titles
    from ai_agent.runtime.screen_privacy import screen_status
    if (graph.definition.task_type != 'settings_assisted'
            or screen_status(raw_nodes, graph.definition.required_slots.get('setting'))):
        return None
    for edge in graph.transitions:
        if edge.verification_predicate != 'manual_result_required':
            continue
        state = graph.states[edge.from_state]
        for title in _screen_titles(nodes):
            if (title.source_confidence is None and not title.node_id.startswith('vision:')
                    and title.package_name in state.package_candidates
                    and state.required_keyword_groups
                    and all(normalize_text(title.label) in {normalize_text(w) for w in group}
                        for group in state.required_keyword_groups)):
                from dataclasses import replace
                from ai_agent.task_graph.assisted_guidance import handoff_message
                page = next(w for group in state.required_keyword_groups for w in group
                    if normalize_text(w) == normalize_text(title.label))
                return replace(edge, guide_message=handoff_message(graph.definition.goal_type,page,edge.guide_message))
    return None
