"""Runtime trace collection boundary."""

from __future__ import annotations

from ai_agent.schemas import RuntimeTrace


class TraceLogger:
    def __init__(self) -> None:
        self.traces: list[RuntimeTrace | dict] = []

    def record(self, trace: RuntimeTrace | dict) -> None:
        self.traces.append(trace)
