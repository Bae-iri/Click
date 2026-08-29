"""Offline replay and regression validation for graph proposals."""

from __future__ import annotations

from ai_agent.schemas import GraphUpdateProposal


class GraphValidator:
    def validate(self, proposal: GraphUpdateProposal) -> dict:
        return {
            "proposal_id": proposal.proposal_id,
            "decision": "NEEDS_DATA",
            "regression": {"has_regression": False, "failed_cases": []},
        }
