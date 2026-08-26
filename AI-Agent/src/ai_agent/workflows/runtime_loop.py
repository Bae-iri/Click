"""End-to-end runtime loop skeleton."""

from __future__ import annotations

from ai_agent.schemas import RawGoalInput


class RuntimeLoop:
    def run(self, raw_goal: RawGoalInput) -> dict:
        """Run Raw Goal -> Guide/Scroll/Verify loop.

        Concrete dependencies are intentionally injected later, once the BE and
        FE contracts settle.
        """
        return {"status": "not_implemented", "source_text": raw_goal.source_text}
