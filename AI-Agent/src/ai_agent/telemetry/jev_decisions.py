"""Closed, text-free Jev evidence for offline assessment; old events remain valid."""
import math
import re

from ai_agent.telemetry.trace_logger import diagnostic_payload, UI_SELECTION_STATUSES, UI_SELECTION_HOLD_REASONS

SCHEMA = "jev-decision.v1"
SCORES = {"confidence", "raw_confidence", "normalized_probability", "minimum_confidence",
          "top_probability", "probability_margin"}
HASHES = {"decision_hash", "screen_hash", "chosen_target_hash", "chosen_transition_hash", "code_hash"}
COUNTS = {"choice_count": 252, "candidate_count": 250, "grounded_candidate_count": 250}
FLAGS = {"called", "retained", "paused"}
FIELDS = SCORES | HASHES | set(COUNTS) | FLAGS | {
    "schema_version", "provider", "mode", "status", "model", "hold_reason", "choice_kind"}


def validate_decision(record):
    if (not isinstance(record, dict) or set(record) - FIELDS
            or record.get("schema_version") != SCHEMA or record.get("provider") != "jev"
            or record.get("mode") != "primary" or record.get("status") not in UI_SELECTION_STATUSES
            or type(record.get("called")) is not bool):
        raise ValueError("Invalid Jev decision evidence")
    for key in HASHES & record.keys():
        if not isinstance(record[key], str) or not re.fullmatch(r"[a-f0-9]{64}", record[key]):
            raise ValueError("Invalid Jev evidence hash")
    for key in SCORES & record.keys():
        if type(record[key]) not in (int, float) or not math.isfinite(record[key]) or not 0 <= record[key] <= 1:
            raise ValueError("Invalid Jev score")
    for key in FLAGS & record.keys():
        if type(record[key]) is not bool:
            raise ValueError("Invalid Jev evidence flag")
    for key, maximum in COUNTS.items():
        if key in record and (type(record[key]) is not int or not 0 <= record[key] <= maximum):
            raise ValueError("Invalid Jev candidate count")
    if "model" in record and (not isinstance(record["model"], str)
            or not re.fullmatch(r"jev-[A-Za-z0-9_.-]{1,60}", record["model"])):
        raise ValueError("Invalid Jev model identity")
    if "hold_reason" in record and record["hold_reason"] not in UI_SELECTION_HOLD_REASONS:
        raise ValueError("Invalid Jev hold reason")
    if "choice_kind" in record and record["choice_kind"] not in {"target", "wait_for_result", "insufficient_evidence"}:
        raise ValueError("Invalid Jev choice kind")
    if record["called"] and not {"decision_hash", "screen_hash"} <= record.keys():
        raise ValueError("A provider call requires immutable decision and screen identities")
    return record


def decision_evidence(debug, configuration=None):
    safe = diagnostic_payload({"ui_selection": debug.get("ui_selection", {})}).get("ui_selection", {})
    if safe.get("provider") != "jev" or safe.get("mode") != "primary":
        return None
    record = {key: value for key, value in safe.items() if key in FIELDS}
    record.update(schema_version=SCHEMA, called=bool(safe.get("called")))
    choice = safe.get("choice")
    if choice:
        record["choice_kind"] = "target" if choice.startswith("candidate_") else choice
    configuration = configuration or {}
    for key in ("model", "code_hash"):
        if key in configuration:
            record[key] = configuration[key]
    return validate_decision(record)
