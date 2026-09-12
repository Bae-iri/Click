"""Build model-specific training samples from runtime traces."""

from __future__ import annotations

from ai_agent.telemetry.schemas import RuntimeTrace


class TrainingDatasetBuilder:
    def build(self, traces: list[RuntimeTrace], sample_type: str) -> list[dict]:
        del traces
        return [{"sample_type": sample_type, "samples": []}]
