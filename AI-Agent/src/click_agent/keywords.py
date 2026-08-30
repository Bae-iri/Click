"""Keyword normalization helpers for Korean/English Android Settings labels."""

from __future__ import annotations

import re


CANONICAL_REPLACEMENTS = (
    ("font size", "글자 크기"),
    ("text size", "글자 크기"),
    ("글꼴 크기", "글자 크기"),
    ("폰트 크기", "글자 크기"),
    ("글씨 크기", "글자 크기"),
    ("font", "글자"),
    ("text", "글자"),
    ("글꼴", "글자"),
    ("폰트", "글자"),
    ("글씨", "글자"),
)


def normalize_text(value: str | None) -> str:
    if not value:
        return ""

    normalized = value.strip().lower()
    normalized = re.sub(r"\s+", " ", normalized)
    normalized = re.sub(r"[^\w\s가-힣]", " ", normalized)
    normalized = re.sub(r"\s+", " ", normalized).strip()

    for source, target in CANONICAL_REPLACEMENTS:
        normalized = normalized.replace(source, target)

    return normalized


def keyword_matches(haystack: str, keyword: str) -> bool:
    return normalize_text(keyword) in normalize_text(haystack)


def any_keyword_matches(haystack: str, keywords: tuple[str, ...]) -> bool:
    return any(keyword_matches(haystack, keyword) for keyword in keywords)
