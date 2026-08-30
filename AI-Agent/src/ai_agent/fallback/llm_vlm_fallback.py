"""Fallback boundary for low-confidence or unknown UI cases."""

from __future__ import annotations


class LLMVLMFallback:
    def analyze(self, context: dict, *, user_consented: bool = False) -> dict:
        """Analyze only after privacy masking and policy checks."""
        if not user_consented:
            return {"status": "consent_required", "reason": "external_vlm_processing"}
        safe_context = {
            key: value for key, value in context.items()
            if key not in {"text", "raw_text", "screenshot", "conversation"}
        }
        return {"status": "not_configured", "masked_context": safe_context}
