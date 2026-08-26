"""Verify state transitions and task completion."""

from __future__ import annotations

from ai_agent.schemas import VerificationResult


class Verifier:
    def verify_transition(self, expected_state: str, observed_state: str) -> VerificationResult:
        if observed_state == expected_state:
            return "SUCCESS"
        if observed_state == "S_DONE":
            return "DONE"
        if observed_state == "S0_UNKNOWN":
            return "UNKNOWN"
        return "WRONG_STATE"
