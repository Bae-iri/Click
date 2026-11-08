"""Verify read-only page arrival from a native independent heading, never a menu row."""
from ai_agent.runtime.classifier import _screen_titles
from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.screen_privacy import screen_status
from ai_agent.runtime.types import ActionType, SafetyLevel, VerificationResult, VerificationStatus


def page_arrival(graph, nodes, raw_nodes):
    if (graph.definition.task_type != 'settings_navigation'
            or graph.definition.required_slots.get('action') != 'open'
            or screen_status(raw_nodes, graph.definition.required_slots.get('setting'))):
        return None
    titles = _screen_titles(nodes)
    for transition in graph.transitions:
        if (not transition.completes_task or transition.verification_predicate != 'settings_page_visible'
                or transition.action_type != ActionType.OBSERVE_ONLY or transition.requires_user_action
                or transition.safety_level != SafetyLevel.LOW_RISK):
            continue
        state = graph.states[transition.from_state]
        if not state.required_keyword_groups:
            continue
        for title in titles:
            if (title.node_id.startswith('vision:') or title.source_confidence is not None
                    or title.package_name not in state.package_candidates):
                continue
            if all(normalize_text(title.label) in {normalize_text(w) for w in group}
                   for group in state.required_keyword_groups):
                return VerificationResult(VerificationStatus.SUCCESS, state.state_id, graph.definition.done_state,
                    evidence='settings_page_visible', task_completed=True, count_attempt=False)
    return None
