"""Similarity scoring boundary.

Gemini API integration will live behind this interface later. The MVP-01 graph
and shared contracts do not depend on a concrete similarity model.
"""

from __future__ import annotations

from typing import Protocol
import json
import math
import os
from urllib.request import Request, urlopen


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


class KeywordFallbackSimilarityScorer:
    provider = "keyword-only"

    def score(self, source: str, candidate: str) -> float:
        left = set(source.lower().split())
        right = set(candidate.lower().split())
        return len(left & right) / max(1, len(left | right))


class GeminiSimilarityScorer:
    """Gemini embeddings with local fallback; enabled only when a key exists."""

    provider = "gemini-embedding-001"

    def __init__(self, api_key: str, fallback: SimilarityScorer | None = None) -> None:
        self.api_key = api_key
        self.fallback = fallback or KeywordFallbackSimilarityScorer()
        self._cache: dict[str, list[float]] = {}

    def score(self, source: str, candidate: str) -> float:
        try:
            a, b = self._embed(source), self._embed(candidate)
            denominator = math.sqrt(sum(x*x for x in a)) * math.sqrt(sum(x*x for x in b))
            return max(0.0, min(1.0, sum(x*y for x, y in zip(a, b)) / denominator)) if denominator else 0.0
        except Exception:
            return self.fallback.score(source, candidate)

    def _embed(self, text: str) -> list[float]:
        if text in self._cache:
            return self._cache[text]
        body = json.dumps({"model": "models/gemini-embedding-001", "content": {"parts": [{"text": text}]}}).encode()
        request = Request(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent",
            data=body,
            headers={"Content-Type": "application/json", "x-goog-api-key": self.api_key},
        )
        with urlopen(request, timeout=3) as response:
            values = json.load(response)["embedding"]["values"]
        self._cache[text] = values
        return values


def default_similarity_scorer() -> SimilarityScorer:
    key = os.getenv("GEMINI_API_KEY")
    return GeminiSimilarityScorer(key) if key else KeywordFallbackSimilarityScorer()
