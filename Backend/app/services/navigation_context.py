"""Describe the next approved menu using authored page titles, never private UI text."""
from collections import deque
from ai_agent.runtime.types import ActionType, SafetyLevel
from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.vision_vocabulary import labels_for


def menu_edge(edge):
    return (edge.action_type == ActionType.TAP and edge.target_role in {'menu_item','app_icon'}
        and edge.safety_level == SafetyLevel.LOW_RISK and not edge.completes_task and not edge.confirmation_message)


def distinct_menu_bindings(bindings, nodes, graph):
    """One native menu must not compete with its route from a different page.

    Only remove an equivalent route when a standalone native title uniquely
    identifies its source. Missing, inferred or conflicting evidence keeps the
    alternatives for Jev; this never selects a target or authorizes a change.
    """
    if graph.definition.target_app != 'android_settings' or graph.definition.task_type != 'settings_navigation':
        return bindings
    from collections import defaultdict
    from ai_agent.runtime.capabilities import document
    from ai_agent.runtime.classifier import _screen_titles, _required_groups
    titles = _screen_titles(nodes)
    if len(titles) != 1:
        return bindings
    title = titles[0]
    if (title.source_confidence is not None or title.node_id.startswith('vision:')
            or title.package_name not in document()['settings_packages']):
        return bindings
    duplicates = defaultdict(list)
    for key, (edge, node) in bindings.items():
        if (menu_edge(edge) and node.source_confidence is None and not node.node_id.startswith('vision:')
                and node.package_name == title.package_name and node.window_id == title.window_id):
            duplicates[node.node_id, edge.to_state, edge.action_type].append((key, edge))
    removed = set()
    for routes in duplicates.values():
        sources = {edge.from_state for _, edge in routes}
        matching = {source for source in sources
                    if (groups := _required_groups(graph.states[source], graph))
                    and all(normalize_text(title.label) in {normalize_text(word) for word in group}
                            for group in groups)}
        if len(sources) > 1 and len(matching) == 1:
            removed.update(key for key, edge in routes if edge.from_state not in matching)
    return {key: value for key, value in bindings.items() if key not in removed}


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


def immediate_menu_request(bindings, nodes, graph):
    """Frame one grounded menu action only when its independent source title agrees."""
    if len(bindings)!=1:
        return None
    edge,_=next(iter(bindings.values()))
    context=next_menu_context(edge,graph)
    if not context or not context['current_page_titles']:
        return None
    from ai_agent.runtime.classifier import _screen_titles
    if not any(title.source_confidence is None and not title.node_id.startswith('vision:')
            and all(any(normalize_text(title.label)==normalize_text(word) for word in group)
                for group in context['current_page_titles']) for title in _screen_titles(nodes)):
        return None
    return {'current_page':context['current_page_titles'],'open_menu':list(edge.target_keywords),
        'next_page':context['next_page_titles'],'scope':'immediate_menu_opening'}
