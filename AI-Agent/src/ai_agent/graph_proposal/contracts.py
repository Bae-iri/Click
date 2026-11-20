"""Versioned, privacy-minimized offline inputs. Runtime decisions are weak evidence."""
from copy import deepcopy
from ai_agent.task_graph.serialization import canonical, fingerprint
import math
import re

EVENT_SCHEMA = "ai2-event.v1"
MANIFEST_SCHEMA = "ai2-dataset.v1"
SOURCE_KINDS = {"synthetic", "device_test", "runtime", "public_reference"}
SPLITS = {"train", "validation", "holdout"}


class EvidenceRequired(ValueError):
    """A candidate needs new observations, rather than an evaluation tool repair."""


def identifier(value, name="identifier"):
    if not isinstance(value, str) or not re.fullmatch(r"[\w.:-]{1,200}", value):
        raise ValueError(f"Invalid {name}")
    return value


def hash_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        raise ValueError("Invalid SHA-256")
    return value


def finite_number(value):
    return type(value) in (int, float) and math.isfinite(value)


def validate_device_cohort(value):
    if not isinstance(value, str) or not re.fullmatch(
            r"(?:unknown|samsung|google|xiaomi|oppo|vivo|motorola|sony|lg|oneplus|huawei):android-(?:\d{1,2}|unknown)", value):
        raise ValueError("Invalid device cohort")
    return value


def validate_event(event):
    """Reject extra fields rather than accidentally carrying raw text into datasets."""
    required = {"schema_version", "event_id", "session_key", "episode_id", "sequence", "created_at",
                "expires_at", "task_id", "graph_version", "graph_hash", "source_kind", "runtime", "control"}
    if (not isinstance(event, dict) or set(event) - {"device_cohort"} != required
            or event["schema_version"] != EVENT_SCHEMA):
        raise ValueError("Invalid offline event envelope")
    if "device_cohort" in event:
        validate_device_cohort(event["device_cohort"])
    for key in ("event_id", "session_key", "episode_id", "task_id", "graph_version"):
        identifier(event[key], key)
    hash_value(event["graph_hash"])
    if event["source_kind"] not in SOURCE_KINDS:
        raise ValueError("Unknown source kind")
    for key in ("sequence", "created_at", "expires_at"):
        if type(event[key]) is not int or event[key] < 0:
            raise ValueError(f"Invalid {key}")
    if event["sequence"] < 1 or event["expires_at"] <= event["created_at"]:
        raise ValueError("Invalid event sequence or expiry")
    runtime = event["runtime"]
    allowed = {"schema_version", "state_id", "before_state_id", "attempt_hash", "previous_guide_hash",
               "attempt_target_hash", "before_observation_hash", "after_observation_hash", "guide_hash",
               "action_type", "command_type", "target", "verification_status", "count_attempt",
               "task_completed", "reason_code", "before_values", "after_values", "graph_hash", "duration_ms", "jev_decision"}
    if not isinstance(runtime, dict) or set(runtime) - allowed:
        raise ValueError("Unexpected runtime evidence fields")
    if "jev_decision" in runtime:
        from ai_agent.telemetry.jev_decisions import validate_decision
        validate_decision(runtime["jev_decision"])
    if runtime.get("duration_ms") is not None and (type(runtime["duration_ms"]) is not int
            or not 0 <= runtime["duration_ms"] <= 86_400_000):
        raise ValueError("Invalid action duration")
    for key in ("attempt_hash", "previous_guide_hash", "attempt_target_hash", "before_observation_hash",
                "after_observation_hash", "guide_hash", "graph_hash"):
        if runtime.get(key) is not None:
            hash_value(runtime[key])
    if runtime.get("graph_hash") and runtime["graph_hash"] != event["graph_hash"]:
        raise ValueError("Event graph mismatch")
    for key in ("state_id", "before_state_id"):
        if runtime.get(key) is not None:
            identifier(runtime[key], key)
    if runtime.get("schema_version") not in {None, "agent-execution-event.v1"}:
        raise ValueError("Unsupported runtime schema")
    for key in ("count_attempt", "task_completed"):
        if key in runtime and type(runtime[key]) is not bool:
            raise ValueError("Invalid evidence boolean")
    from ai_agent.telemetry.execution_events import REASONS
    if runtime.get("reason_code") not in REASONS | {None, "other"}:
        raise ValueError("Unknown evidence reason")
    if runtime.get("verification_status") not in {None, "SUCCESS", "DONE", "NO_CHANGE", "WRONG_STATE",
            "UNKNOWN", "WAITING_FOR_ACTION", "WAITING_FOR_OBSERVATION"}:
        raise ValueError("Unknown verification status")
    if runtime.get("action_type") not in {None, "tap", "drag", "scroll", "observe_only"}:
        raise ValueError("Unknown action")
    if runtime.get("command_type") not in {None, "highlight", "gesture_hint", "confirmation", "done", "cancelled", "blocked"}:
        raise ValueError("Unknown command")
    target = runtime.get("target") or {}
    if not isinstance(target, dict) or set(target) - {"node_hash", "role", "transition_id"}:
        raise ValueError("Unexpected target fields")
    if target.get("node_hash") is not None:
        hash_value(target["node_hash"])
    if target.get("transition_id") is not None:
        identifier(target["transition_id"])
    if target.get("role") not in {None, "slider", "button", "menu_item", "other"}:
        raise ValueError("Unknown target role")
    for key in ("before_values", "after_values"):
        values = runtime.get(key, {})
        if not isinstance(values, dict) or len(values) > 16:
            raise ValueError("Invalid numeric evidence")
        for node, value in values.items():
            hash_value(node)
            if not finite_number(value) or abs(value) > 1_000_000:
                raise ValueError("Invalid numeric evidence")
    control = event["control"]
    if not isinstance(control, dict) or set(control) - {"command_type", "status", "user_result", "result_response", "adjustment_reason"}:
        raise ValueError("Unexpected control evidence")
    enums = {"command_type": {"resolve_goal", "cancel_goal", "start_task", "pause_task", "resume_task", "cancel_task", "replace_task", "observe", "respond_result", "adjust_task", "respond_confirmation", "request_result"},
             "status": {"IN_PROGRESS", "PAUSED", "CANCELLED", "COMPLETED", "BLOCKED", "EXPIRED", "WAITING_RESULT", "WAITING_ADJUSTMENT"},
             "user_result": {"satisfied", "needs_help", "stopped", "no_response"},
             "result_response": {"accepted", "rejected"},
             "adjustment_reason": {"too_small", "too_large", "other", "unsure"}}
    for key, value in control.items():
        if value is not None and value not in enums[key]:
            raise ValueError("Unknown control value")
    return deepcopy(event)


def validate_manifest(manifest):
    required = {"schema_version", "dataset_id", "revision", "source_revision", "records"}
    if not isinstance(manifest, dict) or set(manifest) != required or manifest["schema_version"] != MANIFEST_SCHEMA:
        raise ValueError("Invalid dataset manifest")
    for key in ("dataset_id", "revision", "source_revision"):
        identifier(manifest[key], key)
    if not isinstance(manifest["records"], list):
        raise ValueError("Manifest records must be a list")
    seen, groups = set(), {}
    for record in manifest["records"]:
        fields = {"episode_id", "group_id", "split", "source_kind", "review_status", "reviewer",
                  "review_revision", "evidence_refs", "label", "label_method"}
        if not isinstance(record, dict) or set(record) != fields:
            raise ValueError("Invalid review record")
        for key in ("episode_id", "group_id"):
            identifier(record[key], key)
        if record["episode_id"] in seen:
            raise ValueError("Duplicate manifest episode")
        seen.add(record["episode_id"])
        if record["split"] not in SPLITS or record["source_kind"] not in SOURCE_KINDS:
            raise ValueError("Invalid source/split")
        if groups.setdefault(record["group_id"], record["split"]) != record["split"]:
            raise ValueError("Group leakage across dataset splits")
        if record["label"] not in {"success", "fail", "unknown", "cancelled"}:
            raise ValueError("Invalid reviewed label")
        if record["review_status"] not in {"pending", "independent_review"}:
            raise ValueError("Invalid review status")
        if record["label_method"] not in {"weak", "independent_evidence"}:
            raise ValueError("Invalid label method")
        for key in ("reviewer", "review_revision"):
            if record[key] is not None:
                identifier(record[key], key)
        if not isinstance(record["evidence_refs"], list):
            raise ValueError("Evidence references must be a list")
        for ref in record["evidence_refs"]:
            identifier(ref)
        if record["review_status"] == "independent_review":
            identifier(record["reviewer"], "reviewer")
            identifier(record["review_revision"], "review revision")
            if not record["evidence_refs"] or record["label_method"] != "independent_evidence":
                raise ValueError("Independent review requires independent evidence")
    return deepcopy(manifest)
