"""Rule-based Safety Gate."""

from __future__ import annotations

from ai_agent.schemas import SafetyLevel, TransitionDefinition


class SafetyGate:
    def evaluate(self, transition: TransitionDefinition) -> SafetyLevel:
        return transition.safety_level
