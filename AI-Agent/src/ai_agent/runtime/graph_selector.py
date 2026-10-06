"""Agent1-owned selection of an approved candidate Task Graph."""

from __future__ import annotations

from dataclasses import dataclass

from ai_agent.runtime.types import TaskGraph, TypedGoal


class NoCompatibleGraphError(ValueError):
    pass


class AmbiguousGraphError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class GraphSelection:
    graph: TaskGraph
    selector_version: str
    score: int
    reason_codes: tuple[str, ...]
    consumed_slots: tuple[str, ...]


def select_task_graph(goal: TypedGoal, candidates: tuple[TaskGraph, ...]) -> GraphSelection:
    """Choose by semantic capability, never by a Goal-ID-to-graph lookup table."""

    domain, entity, operation = _goal_semantics(goal.goal_type)
    ranked: list[GraphSelection] = []
    seen_task_ids: set[str] = set()
    for graph in candidates:
        definition = graph.definition
        if definition.task_id in seen_task_ids:
            continue
        seen_task_ids.add(definition.task_id)
        capability = definition.capability
        if definition.target_app != goal.target_app:
            continue
        if capability.domain != domain or capability.entity != entity:
            continue
        if operation not in capability.operations:
            continue
        if any(goal.slots.get(key) != value for key, value in definition.required_slots.items()):
            continue
        ranked.append(GraphSelection(
            graph=graph,
            selector_version="agent1-capability-selector.v1",
            score=100,
            reason_codes=("target_app", "domain", "entity", "operation", "required_slots"),
            consumed_slots=tuple(sorted(definition.required_slots)),
        ))
    if not ranked:
        raise NoCompatibleGraphError("NO_COMPATIBLE_GRAPH")
    if len(ranked) > 1 and ranked[0].score == ranked[1].score:
        raise AmbiguousGraphError("GRAPH_SELECTION_AMBIGUOUS")
    return ranked[0]


def _goal_semantics(goal_type_id: str) -> tuple[str, str, str]:
    parts = goal_type_id.split(".")
    if len(parts) < 3 or not all(parts):
        raise NoCompatibleGraphError("INVALID_GOAL_TYPE_ID")
    return parts[0], parts[1], ".".join(parts[2:])
