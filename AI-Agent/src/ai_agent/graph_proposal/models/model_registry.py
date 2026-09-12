"""Model artifact registry boundary."""

from __future__ import annotations


class ModelRegistry:
    def __init__(self) -> None:
        self._artifacts: dict[str, dict] = {}

    def register(self, model_id: str, artifact: dict) -> None:
        self._artifacts[model_id] = artifact

    def get(self, model_id: str) -> dict:
        return self._artifacts[model_id]
