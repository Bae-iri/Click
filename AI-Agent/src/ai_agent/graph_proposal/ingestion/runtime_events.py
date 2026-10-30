"""Conservative offline labels; waiting or absent evidence is never success/failure."""
from ai_agent.telemetry.execution_events import SCHEMA


def label_event(event):
    runtime = event.get("runtime") or {}
    if runtime.get("schema_version") != SCHEMA:
        return {"policy_version": "runtime-event-label.v1", "label": "unknown"}
    if runtime.get("reason_code") in {"user_back_navigation", "user_home_navigation"}:
        return {"policy_version": "runtime-event-label.v1", "label": "navigation_recovery",
                "reason_code": runtime["reason_code"], "state_id": runtime.get("state_id")}
    status = runtime.get("verification_status")
    label = {"SUCCESS": "verified_success", "NO_CHANGE": "verified_failure", "WRONG_STATE": "verified_failure",
             "WAITING_FOR_ACTION": "waiting", "WAITING_FOR_OBSERVATION": "waiting"}.get(status, "unknown")
    if label == "verified_success" and not runtime.get("count_attempt"):
        label = "observed_progress"
    if label == "verified_failure" and not runtime.get("count_attempt"):
        label = "unknown"
    return {"policy_version": "runtime-event-label.v1", "label": label,
            "reason_code": runtime.get("reason_code"), "state_id": runtime.get("state_id")}
