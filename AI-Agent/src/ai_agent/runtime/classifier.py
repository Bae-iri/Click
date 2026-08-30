"""Screen state classification."""

from __future__ import annotations

from ai_agent.runtime.keywords import any_keyword_matches
from ai_agent.runtime.types import DeviceContext, NormalizedNode, TaskGraph, TaskState


def classify_screen_state(
    nodes: tuple[NormalizedNode, ...],
    graph: TaskGraph,
    *,
    device_context: DeviceContext | None = None,
    previous_state_id: str | None = None,
) -> str:
    best_state_id = "S0_UNKNOWN"
    best_score = 0.0

    screen_text = " ".join(node.label for node in nodes)
    packages = {node.package_name for node in nodes if node.package_name}
    roles = {node.role for node in nodes}

    for state in graph.states.values():
        if state.state_id in {"S0_UNKNOWN", graph.definition.done_state}:
            continue

        if state.package_candidates and packages:
            package_match = bool(packages.intersection(state.package_candidates))
            if not package_match:
                continue
        else:
            package_match = bool(
                device_context
                and device_context.app_package in state.package_candidates
            )

        required_groups = _required_groups(state)
        if required_groups and not _all_required_groups_match(screen_text, required_groups):
            continue

        required_score = 0.55 if required_groups else 0.0
        optional_score = _optional_keyword_score(screen_text, state.optional_keywords)
        role_score = 0.2 if roles.intersection(state.role_hints) else 0.0
        package_score = 0.1 if package_match else 0.0
        continuity_score = _continuity_score(graph, previous_state_id, state.state_id)
        score = required_score + optional_score + role_score + package_score + continuity_score

        if score >= state.confidence_threshold and score > best_score:
            best_state_id = state.state_id
            best_score = score

    return best_state_id


def _required_groups(state: TaskState) -> tuple[tuple[str, ...], ...]:
    if state.required_keyword_groups:
        return state.required_keyword_groups
    return tuple((keyword,) for keyword in state.required_keywords)


def _all_required_groups_match(
    screen_text: str,
    required_groups: tuple[tuple[str, ...], ...],
) -> bool:
    return all(any_keyword_matches(screen_text, group) for group in required_groups)


def _optional_keyword_score(screen_text: str, optional_keywords: tuple[str, ...]) -> float:
    if not optional_keywords:
        return 0.0
    matches = sum(
        1 for keyword in optional_keywords if any_keyword_matches(screen_text, (keyword,))
    )
    return min(0.15, 0.05 * matches)


def _continuity_score(
    graph: TaskGraph,
    previous_state_id: str | None,
    candidate_state_id: str,
) -> float:
    if not previous_state_id:
        return 0.0
    if previous_state_id == candidate_state_id:
        return 0.05
    next_states = {
        transition.to_state for transition in graph.transitions_from(previous_state_id)
    }
    return 0.05 if candidate_state_id in next_states else 0.0
