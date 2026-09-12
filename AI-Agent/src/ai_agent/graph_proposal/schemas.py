"""AI-Agent(2)-owned graph proposal contracts.

Runtime observations live in ai_agent.telemetry.schemas. User guidance and
online verification contracts are not defined or exported by this package.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class GraphUpdateProposal:
    proposal_id: str
    task_id: str
    base_graph_version: str
    proposal_type: str
    change: dict[str, Any]
    evidence: dict[str, Any]
    proposal_confidence: float
    requires_human_review: bool
