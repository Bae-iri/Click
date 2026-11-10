"""Describe the next approved menu using authored page titles, never private UI text."""
from collections import deque
from ai_agent.runtime.types import ActionType, SafetyLevel
from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.vision_vocabulary import labels_for


def menu_edge(edge):
    return (edge.action_type == ActionType.TAP and edge.target_role in {'menu_item','app_icon'}
        and edge.safety_level == SafetyLevel.LOW_RISK and not edge.completes_task and not edge.confirmation_message)


def next_menu_context(edge, graph):
    if not menu_edge(edge):
        return None
    vocabulary={normalize_text(w) for w in labels_for(graph.definition.required_slots.get('setting'))}
    def titles(key):
        s=graph.states[key]
        groups=s.required_keyword_groups or tuple((word,) for word in s.required_keywords)
        return [words for group in groups if (words:=[w for w in group if normalize_text(w) in vocabulary])]
    pending=deque([(edge.to_state,[edge.to_state])]);seen=set();route=None
    while pending:
        state,path=pending.popleft()
        if state in seen:continue
        seen.add(state);outgoing=graph.transitions_from(state)
        navigation=sorted((e for e in outgoing if menu_edge(e)),key=lambda e:(e.priority,e.transition_id))
        if any(e.completes_task or e.safety_level!=SafetyLevel.LOW_RISK for e in outgoing) or not navigation:
            route=path;break
        if len(path)<6:pending.extend((e.to_state,[*path,e.to_state]) for e in navigation if e.to_state not in path)
    current=titles(edge.from_state);remaining=[titles(key) for key in (route or [edge.to_state])]
    names=[groups[0][0] for groups in [current,*remaining] if groups]
    return {'scope':'open_next_menu_only','current_page_titles':current,'next_page_titles':titles(edge.to_state),
        'remaining_page_titles':remaining,'route_description':' → '.join(names),'completes_goal':False}
