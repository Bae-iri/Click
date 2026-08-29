"""Similarity scoring boundary.

Gemini API integration will live behind this interface later. The MVP-01 graph
and shared contracts do not depend on a concrete similarity model.
"""

from __future__ import annotations

from typing import Protocol


class SimilarityScorer(Protocol):
    def score(self, source: str, candidate: str) -> float:
        """Return a semantic similarity score between 0.0 and 1.0."""
        ...


class SimilarityNotConfiguredError(RuntimeError):
    """Raised when semantic similarity is requested before a provider is wired."""


class UnconfiguredSimilarityScorer:
    def score(self, source: str, candidate: str) -> float:
        raise SimilarityNotConfiguredError(
            "Similarity scorer is not configured. Wire Gemini behind "
            "SimilarityScorer before using semantic matching."
        )
