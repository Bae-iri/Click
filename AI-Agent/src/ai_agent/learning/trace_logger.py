"""Runtime trace collection boundary."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any


class TraceLogger:
    def __init__(self) -> None:
        self.traces: list[dict[str, Any]] = []

    def record(self, trace: Mapping[str, Any]) -> None:
        payload = dict(trace)
        payload.setdefault("schema_version", "agent-runtime-trace.v1")
        self.traces.append(_redact_sensitive_values(payload))


def _redact_sensitive_values(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {
            key: "[redacted]"
            if key in {"screenshot_base64", "image_base64"}
            else _redact_sensitive_values(nested_value)
            for key, nested_value in value.items()
        }
    if isinstance(value, tuple):
        return tuple(_redact_sensitive_values(item) for item in value)
    if isinstance(value, list):
        return [_redact_sensitive_values(item) for item in value]
    return value
