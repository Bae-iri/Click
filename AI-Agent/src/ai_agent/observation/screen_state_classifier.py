"""Rule and embedding based screen state classification."""

from __future__ import annotations

from ai_agent.schemas import NormalizedNode

from ai_agent.task_graph.graph import TaskGraph


class ScreenStateClassifier:
    def classify(self, graph: TaskGraph, nodes: list[NormalizedNode], previous_state: str | None = None) -> tuple[str, float]:
        """Return current state and confidence."""
        del previous_state
        labels = " ".join(node.label for node in nodes)
        for state_id, state in graph.states.items():
            required_keywords = state.get("required_keywords", [])
            if required_keywords and all(keyword in labels for keyword in required_keywords):
                return state_id, 0.8
        return "S0_UNKNOWN", 0.0
