"""Trace-driven Task Graph update proposal generation."""

from __future__ import annotations

from ai_agent.schemas import GraphUpdateProposal, RuntimeTrace


class GraphLearner:
    def generate_proposals(self, traces: list[RuntimeTrace]) -> list[GraphUpdateProposal]:
        """Generate graph update proposals from aggregated traces."""
        del traces
        return []
