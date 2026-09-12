"""Runtime state manager for a selected Task Graph."""

from __future__ import annotations

from ai_agent.task_graph.graph import TaskGraph


class StateManager:
    def __init__(self, graph: TaskGraph) -> None:
        self.graph = graph
        self.failed_transition_ids: set[str] = set()

    def next_transitions(self, state_id: str):
        return [
            transition
            for transition in self.graph.outgoing(state_id)
            if transition.transition_id not in self.failed_transition_ids
        ]

    def mark_failed(self, transition_id: str) -> None:
        self.failed_transition_ids.add(transition_id)
