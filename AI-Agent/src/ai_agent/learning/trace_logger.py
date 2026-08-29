"""Runtime trace collection boundary."""

from __future__ import annotations

from ai_agent.schemas import RuntimeTrace


class TraceLogger:
    def __init__(self) -> None:
        self.traces: list[RuntimeTrace] = []

    def record(self, trace: RuntimeTrace) -> None:
        self.traces.append(trace)
