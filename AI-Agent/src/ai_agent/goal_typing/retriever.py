"""Embedding-style goal candidate retrieval boundary."""

from __future__ import annotations

from dataclasses import dataclass

from .catalog import GoalCatalogEntry


@dataclass(frozen=True)
class GoalCandidate:
    entry: GoalCatalogEntry
    embedding_score: float
    keyword_score: float
    slot_score: float
    app_prior: float

    @property
    def confidence(self) -> float:
        return (
            self.embedding_score * 0.45
            + self.keyword_score * 0.30
            + self.slot_score * 0.20
            + self.app_prior * 0.05
        )


class GoalRetriever:
    def retrieve(self, normalized_text: str, entries: list[GoalCatalogEntry]) -> list[GoalCandidate]:
        """Return top-k goal candidates.

        MVP can start with keyword scoring and later plug in a vector index.
        """
        del normalized_text
        return [
            GoalCandidate(entry=entry, embedding_score=0.0, keyword_score=0.0, slot_score=0.0, app_prior=0.0)
            for entry in entries
        ]
