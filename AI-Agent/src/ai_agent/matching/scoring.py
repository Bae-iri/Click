"""Score fusion helpers for UI matching."""

from __future__ import annotations


def fuse_scores(keyword_score: float, embedding_score: float, role_score: float, actionability_score: float) -> float:
    return keyword_score * 0.35 + embedding_score * 0.35 + role_score * 0.15 + actionability_score * 0.15
