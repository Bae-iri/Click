"""Normalize raw natural language before goal retrieval."""

from __future__ import annotations


class GoalTextNormalizer:
    def normalize(self, source_text: str) -> str:
        """Return normalized user text for rule and embedding matching."""
        return " ".join(source_text.strip().split())
