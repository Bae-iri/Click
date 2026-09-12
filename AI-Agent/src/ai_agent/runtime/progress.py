"""Estimate progress using shortest graph paths, including branches and cycles."""
from math import inf


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
