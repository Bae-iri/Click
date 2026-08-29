"""Fallback boundary for low-confidence or unknown UI cases."""

from __future__ import annotations


class LLMVLMFallback:
    def analyze(self, context: dict) -> dict:
        """Analyze only after privacy masking and policy checks."""
        return {"status": "not_configured", "context_keys": sorted(context.keys())}
