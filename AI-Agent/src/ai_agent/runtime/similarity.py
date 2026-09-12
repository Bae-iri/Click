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
from ai_agent.runtime.budget import remaining
from ai_agent.runtime.privacy import redact_text


def provider_text(text):
    # Only code-owned task vocabulary may leave the server. Regex alone
    # cannot reliably identify names, addresses or arbitrary secrets.
    from ai_agent.runtime.tasks import TASK_GRAPHS
    vocabulary = set()
    for graph in TASK_GRAPHS.values():
        for words in graph.synonyms.values():
            vocabulary.update(words)
        for transition in graph.transitions:
            vocabulary.update(transition.target_keywords)
        for state in graph.states.values():
            vocabulary.update(state.required_keywords)
            vocabulary.update(state.optional_keywords)
            for group in state.required_keyword_groups:
                vocabulary.update(group)
    lowered = redact_text(text).lower()
    return " ".join(sorted(word for word in vocabulary if word.lower() in lowered))


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
            source, candidate = provider_text(source), provider_text(candidate)
            if not source or not candidate:
                return self.fallback.score(source, candidate)
            a, b = self._embed(source), self._embed(candidate)
            if len(a) != len(b):
                raise ValueError("Embedding dimensions differ")
            denominator = math.sqrt(sum(x*x for x in a)) * math.sqrt(sum(x*x for x in b))
            return max(0.0, min(1.0, sum(x*y for x, y in zip(a, b)) / denominator)) if denominator else 0.0
        except Exception:
            return self.fallback.score(source, candidate)

    def _embed(self, text: str) -> list[float]:
        text = provider_text(text)
        if not text:
            raise ValueError("No approved provider vocabulary")
        if text in self._cache:
            return self._cache[text]
        body = json.dumps({"model": "models/gemini-embedding-001", "content": {"parts": [{"text": text}]}}).encode()
        request = Request(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent",
            data=body,
            headers={"Content-Type": "application/json", "x-goog-api-key": self.api_key},
        )
        with urlopen(request, timeout=remaining(3)) as response:
            raw = response.read(262145)
            if len(raw) > 262144:
                raise ValueError("Provider response too large")
            values = json.loads(raw)["embedding"]["values"]
        if (not isinstance(values, list) or not values or len(values) > 8192
                or any(type(value) not in (int, float) or not math.isfinite(value) for value in values)):
            raise ValueError("Invalid embedding")
        if len(self._cache) >= 128:
            self._cache.pop(next(iter(self._cache)))
        self._cache[text] = values
        return values


def default_similarity_scorer() -> SimilarityScorer:
    key = os.getenv("GEMINI_API_KEY")
    return GeminiSimilarityScorer(key) if key else KeywordFallbackSimilarityScorer()
