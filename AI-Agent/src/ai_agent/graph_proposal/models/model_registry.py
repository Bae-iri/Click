"""Model artifact registry boundary."""

from __future__ import annotations
from copy import deepcopy
import json
from pathlib import Path
from ai_agent.graph_proposal.contracts import canonical, identifier


class ModelRegistry:
    def __init__(self, directory=None) -> None:
        self._artifacts: dict[str, dict] = {}
        self.directory = Path(directory) if directory else None

    def register(self, model_id: str, artifact: dict) -> None:
        identifier(model_id, "model ID")
        if artifact.get("model_id") != model_id:
            raise ValueError("Artifact model ID mismatch")
        body = json.loads(canonical(artifact))
        if self.directory:
            self.directory.mkdir(parents=True, exist_ok=True)
            path = self.directory / (model_id + ".json")
            try:
                with path.open("x", encoding="utf-8") as handle:
                    handle.write(canonical(body))
            except FileExistsError:
                if json.loads(path.read_text(encoding="utf-8")) != body:
                    raise ValueError("Model artifacts are immutable") from None
        elif model_id in self._artifacts and self._artifacts[model_id] != body:
            raise ValueError("Model artifacts are immutable")
        self._artifacts[model_id] = deepcopy(body)

    def get(self, model_id: str) -> dict:
        identifier(model_id, "model ID")
        if self.directory:
            return json.loads((self.directory / (model_id + ".json")).read_text(encoding="utf-8"))
        return deepcopy(self._artifacts[model_id])
