"""Estimate progress using shortest graph paths, including branches and cycles."""
from math import inf
from collections import deque


def observed_path(previous, current, graph):
    path = list(previous)
    if current == "S0_UNKNOWN" and path:
        return tuple(path)
    if current not in graph.states or graph.states[current].verification_only or current == graph.definition.done_state:
        return tuple(path)
    if current in path:
        return tuple(path[:path.index(current) + 1])
    return tuple([*path, current])


def checklist(graph, session, match):
    """Observed screen arrivals plus the current graph route; instructions never tick rows."""
    if not session:
        return []
    rows = [{"id": "observed:" + state, "label": graph.states[state].name + " 확인",
             "status": "completed", "evidence": "screen_observed"}
            for state in session.observed_path[1:] if state != "S0_UNKNOWN"]
    verified = session.technical_outcome.get("transition_id") if session.task_completed else None
    first = next((t for t in graph.transitions if t.transition_id == (verified or match.transition_id)), None)
    if first is None:
        return rows
    queue = deque([(first.to_state, [first])])
    seen = {first.from_state}
    route = [first]
    while queue:
        state, path = queue.popleft()
        if state == graph.definition.done_state:
            route = path
            break
        if state in seen:
            continue
        seen.add(state)
        for edge in graph.transitions_from(state):
            queue.append((edge.to_state, [*path, edge]))
    for index, edge in enumerate(route):
        done = session.task_completed and (edge.transition_id == verified or not edge.requires_user_action)
        rows.append({"id": edge.transition_id, "label": edge.action_name,
                     "status": "completed" if done else "current" if index == 0 else "pending",
                     "evidence": ("settings_page_visible" if graph.definition.task_type == "settings_navigation" else "setting_change_verified") if done else None})
    return rows


def graph_progress(graph, state_id, completed=False):
    if graph is None:
        return 0, 0
    distances = {key: inf for key in graph.states}
    distances[graph.definition.done_state] = 0
    for _ in graph.states:
        changed = False
        for transition in graph.transitions:
            cost = int(transition.requires_user_action)
            candidate = distances.get(transition.to_state, inf) + cost
            if candidate < distances.get(transition.from_state, inf):
                distances[transition.from_state] = candidate
                changed = True
        if not changed:
            break
    starts = [distances.get(key, inf) for key in graph.definition.start_states]
    reachable = [value for value in starts if value != inf]
    if not reachable:
        return 0, 0
    total = int(max(reachable))
    left = distances.get(state_id, inf)
    if completed:
        return total, total
    return (max(0, total - int(left)) if left != inf else 0), total
