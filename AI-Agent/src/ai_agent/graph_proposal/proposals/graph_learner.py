"""Trace-driven Task Graph update proposal generation."""

from __future__ import annotations

from ai_agent.graph_proposal.schemas import GraphUpdateProposal
from ai_agent.telemetry.schemas import RuntimeTrace


class GraphLearner:
    def generate_proposals(self, traces: list[RuntimeTrace]) -> list[GraphUpdateProposal]:
        """Generate graph update proposals from aggregated traces."""
        del traces
        return []
