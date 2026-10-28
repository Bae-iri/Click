"""Finite, reviewed OCR labels shared with the Android asset bundle.

Catalog vocabulary does not approve a task graph or authorize an operation.
Never add SSIDs, device names, account text, user slots or OCR-derived strings.
"""
import json
from pathlib import Path

_DATA = json.loads((Path(__file__).resolve().parents[3] / "goal_catalog"
                    / "vision_vocabulary.json").read_text(encoding="utf-8"))
_COMMON = frozenset(_DATA["common"])
GROUPS = {key: _COMMON | frozenset(labels) for key, labels in _DATA["groups"].items()}
VISION_LABELS = frozenset().union(*GROUPS.values())


def labels_for(vocabulary_key: str | None) -> frozenset[str]:
    # None is used only by the first privacy pass, before the goal is resolved.
    # An unknown/missing resolved setting exports nothing, including common titles.
    return VISION_LABELS if vocabulary_key is None else GROUPS.get(vocabulary_key, frozenset())
