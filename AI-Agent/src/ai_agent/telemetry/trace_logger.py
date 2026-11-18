"""Bounded, expiring diagnostics with no arbitrary text fields."""
from collections.abc import Mapping
from time import monotonic
import math
import re
from hashlib import sha256

CONTAINERS = {"observation", "agent_chain", "goal_typing", "vision_fallback",
              "verification", "match", "guide", "fallback", "debug", "result", "correlation", "replan_decision", "recovery", "state_classification", "failure"}
VISION = {"used", "not_requested", "not_configured", "provider_error", "no_signal", "consent_required", "masking_failed"}
ENUMS = {
    "strategy": {"continue", "wait_for_action", "reobserve", "retry_target", "alternate_transition", "backend_reselection"},
    "status": VISION | {"SUCCESS", "DONE", "NO_CHANGE", "WRONG_STATE", "UNKNOWN", "WAITING_FOR_ACTION", "WAITING_FOR_OBSERVATION"},
    "vision_status": VISION,
    "command_type": {"highlight", "gesture_hint", "confirmation", "done", "cancelled", "blocked"},
    "action_type": {"tap", "drag", "scroll", "observe_only"},
    "safety_level": {"LOW_RISK", "CONFIRM_REQUIRED", "BLOCKED"},
    "type": {"none", "settings_app_recovery", "vision_fallback", "vision_augmented_match", "target_match_fallback", "replan"},
    "decision": {"continue", "recover", "wait", "handoff"},
    "reason_code": {"NONE", "TARGET_NOT_VISIBLE", "STATE_IDENTIFIED", "STATE_AMBIGUOUS", "STATE_UNKNOWN", "TARGET_AMBIGUOUS",
        "TARGET_NOT_FOUND", "OBSERVATION_INSUFFICIENT", "OBSERVATION_STALE", "WAITING_FOR_SETTLE",
        "ACTION_EVENT_MISSING", "ACTION_RESULT_UNOBSERVABLE", "VISION_UNAVAILABLE",
        "ACTION_BUDGET_EXHAUSTED", "GRAPH_PATH_UNAVAILABLE", "GRAPH_RESELECTED", "APP_NOT_OPEN",
        "LOW_CONFIDENCE", "MODEL_ABSTAINED", "MODEL_UNAVAILABLE", "MODEL_WAIT_WITHOUT_ACTION"},
    "next_action": {"none", "reobserve", "manual_navigation", "user_assistance", "reselect_graph", "vision_analysis", "open_settings"},
    "waiting_for": {"none", "user", "consent", "observation"},
}
# Shared telemetry owns its closed wire vocabulary and never imports the runtime.
ENUMS.update(stage={"observation", "candidate_generation", "configuration", "request_preparation", "provider",
                   "model_response", "model_selection", "verification", "selection"},
    cause={"unsupported_screen", "sensitive_screen", "empty_observation", "candidate_limit",
           "no_candidates", "invalid_candidates", "ambiguous_candidates", "input_too_large",
           "not_configured", "network_error", "provider_error", "timeout", "deadline_exceeded",
           "invalid_response", "low_confidence", "abstained", "waiting", "result_unobservable",
           "settling", "unknown_failure"},
    required_change={"supported_screen", "safe_screen", "new_observation", "narrow_observation",
        "new_target_evidence", "valid_candidates", "user_clarification", "provider_configuration",
        "connection_restored", "provider_available", "fresh_time_budget", "valid_model_response",
        "action_result_evidence", "settle_interval", "diagnostic_review"})
ENUMS["reason_code"].update({"UNSUPPORTED_SCREEN", "SENSITIVE_SCREEN", "CANDIDATE_LIMIT", "NO_CANDIDATES",
    "INVALID_CANDIDATES", "OBSERVATION_TOO_LARGE", "MODEL_NOT_CONFIGURED", "DEADLINE_EXCEEDED", "SELECTION_FAILED"})
ENUMS["next_action"].update({"narrow_observation", "ask_user", "restore_connection", "restore_provider"})
METRICS = {"count_attempt", "task_completed", "state_score", "score_gap", "recovery_count", "confidence", "element_count", "accessibility_node_count", "vision_element_count",
           "normalized_node_count", "actionable_node_count", "synthetic_vision_node_count",
           "score", "title_match", "control_match", "ambiguous", "title_conflict", "attempts", "retry_after_ms", "recoverable", "accessibility_tree_present", "vision_snapshot_present", "vision_has_screenshot",
           "vision_has_ocr_text", "requires_user_action", "session_stateful", "matched_from_vision"}
METRICS.add("vision_processing_time_ms")
METRICS.update({"retryable", "outcome_uncertain"})
UI_SELECTION_STATUSES = {"selected", "disabled", "skipped", "ineligible", "no_candidates", "candidate_limit",
    "not_configured", "invalid_candidates", "input_too_large", "deadline_exceeded", "provider_error",
    "timeout", "invalid_response", "uncertain", "low_confidence", "abstained", "invalid_choice", "waiting",
    "sensitive_screen", "unsupported_screen", "empty_observation", "network_error", "ambiguous_candidates"}
UI_SELECTION_HOLD_REASONS = {"none", "raw_confidence_below_minimum",
    "normalized_probability_below_minimum", "both_below_minimum",
    "wait_for_result", "insufficient_evidence", "target_not_supported",
    "page_not_identified", "page_confidence_below_minimum", "page_target_conflict"}


def diagnostic_payload(value, depth=0):
    if not isinstance(value, Mapping) or depth > 5:
        return {}
    output = {}
    # AI1 provider diagnostics are a closed schema, never a provider response body.
    selection = value.get("ui_selection")
    if isinstance(selection, Mapping):
        safe = {}
        if isinstance(selection.get("failure"), Mapping):
            safe["failure"] = diagnostic_payload(selection["failure"], depth + 1)
        OBSERVATION_REASONS = {'unsupported_scope', 'unsupported_app', 'input_present', 'credential_input',
            'protected_screen', 'unrecognized_settings_screen', 'safe_native_labels', 'empty_observation'}
        if selection.get('observation_reason') in OBSERVATION_REASONS:
            safe['observation_reason'] = selection['observation_reason']
        for key, allowed in (("provider", {"rules", "jev"}), ("status", UI_SELECTION_STATUSES),
                             ("mode", {"primary", "auxiliary"}), ("judgment_mode", {"atomic", "legacy"})):
            if isinstance(selection.get(key), str) and selection[key] in allowed:
                safe[key] = selection[key]
        if type(selection.get("called")) is bool:
            safe["called"] = selection["called"]
        for key in ("retained", "paused", "page_target_consistent"):
            if type(selection.get(key)) is bool:
                safe[key] = selection[key]
        choice = selection.get("choice")
        if isinstance(choice, str) and (choice in {"wait_for_result", "insufficient_evidence"}
                or (re.fullmatch(r"candidate_(0|[1-9][0-9]{0,2})", choice)
                    and int(choice.removeprefix("candidate_")) < 250)):
            safe["choice"] = choice
        hold_reason = selection.get("hold_reason")
        if isinstance(hold_reason, str) and hold_reason in UI_SELECTION_HOLD_REASONS:
            safe["hold_reason"] = hold_reason
        for key in ("chosen_transition_hash", "chosen_target_hash", "decision_hash", "screen_hash"):
            hashed = selection.get(key)
            if isinstance(hashed, str) and re.fullmatch(r"[0-9a-f]{64}", hashed):
                safe[key] = hashed
        model = selection.get("model")
        if isinstance(model, str) and re.fullmatch(r"jev-[A-Za-z0-9_.-]{1,60}", model):
            safe["model"] = model
        choice_count = selection.get("choice_count")
        if type(choice_count) is int and 3 <= choice_count <= 252:
            safe["choice_count"] = choice_count
        if type(selection.get("question_count")) is int and selection["question_count"] in {1, 3}:
            safe["question_count"] = selection["question_count"]
        temperature = selection.get("choice_temperature")
        if type(temperature) in (int, float) and math.isfinite(temperature) and 0 < temperature <= 10:
            safe["choice_temperature"] = temperature
        if selection.get("limit_kind") in {"candidates", "observation_nodes", "payload_bytes"}:
            safe["limit_kind"] = selection["limit_kind"]
        if type(selection.get("limit")) is int and selection["limit"] in {160, 250, 65536}:
            safe["limit"] = selection["limit"]
        for key, maximum in (("candidate_count", 100_000_000 if selection.get("status") == "candidate_limit" else 250),
                             ("grounded_candidate_count", 250)):
            count = selection.get(key)
            if type(count) is int and 0 <= count <= maximum:
                safe[key] = count
        for key, maximum in (("payload_bytes", 100_000_000), ("exported_node_count", 100_000_000),
                             ("filter_time_ms", 100_000_000), ("provider_latency_ms", 100_000_000)):
            metric = selection.get(key)
            if type(metric) in (int, float) and math.isfinite(metric) and 0 <= metric <= maximum:
                safe[key] = metric
        for key in ("confidence", "minimum_confidence", "top_probability", "probability_margin",
                    "raw_confidence", "normalized_probability", "page_confidence", "target_support_probability",
                    "tempered_top_probability", "tempered_probability_margin", "tempered_confidence"):
            confidence = selection.get(key)
            if type(confidence) in (int, float) and math.isfinite(confidence) and 0 <= confidence <= 1:
                safe[key] = confidence
        output["ui_selection"] = safe
    if isinstance(value.get("candidates"), (list, tuple)):
        output["candidates"] = []
        for candidate in value["candidates"][:3]:
            if isinstance(candidate, Mapping) and (isinstance(candidate.get("state_id"), str)
                    or (isinstance(candidate.get("state_hash"), str) and re.fullmatch(r"[0-9a-f]{64}", candidate["state_hash"]))):
                item = diagnostic_payload(candidate, depth + 1)
                item["state_hash"] = (sha256(candidate["state_id"].encode()).hexdigest()
                    if isinstance(candidate.get("state_id"), str) else candidate["state_hash"])
                output["candidates"].append(item)
    for key in {"session_hash", "request_hash", "graph_hash", "observation_hash", "guide_hash", "attempt_hash", "action_hash"} & value.keys():
        if isinstance(value[key], str) and re.fullmatch(r"[0-9a-f]{64}", value[key]):
            output[key] = value[key]
    for key in CONTAINERS & value.keys():
        output[key] = diagnostic_payload(value[key], depth + 1)
    for key in ENUMS.keys() & value.keys():
        if isinstance(value[key], str) and value[key] in ENUMS[key]:
            output[key] = value[key]
    for key in METRICS & value.keys():
        item = value[key]
        if type(item) in (bool, int, float) and abs(item) <= 100_000_000 and math.isfinite(item):
            output[key] = item
    return output


class TraceLogger:
    def __init__(self, max_records=100, ttl_seconds=900, clock=monotonic):
        if max_records <= 0 or ttl_seconds <= 0:
            raise ValueError("Trace limits must be positive")
        self.max_records, self.ttl_seconds, self.clock = max_records, ttl_seconds, clock
        self._records = []
        self._times = []

    @property
    def traces(self):
        self._prune()
        return list(self._records)

    def _prune(self):
        now = self.clock()
        while self._times and (self._times[0] <= now - self.ttl_seconds or len(self._times) > self.max_records):
            self._times.pop(0)
            self._records.pop(0)

    def record(self, trace):
        payload = diagnostic_payload(trace)
        payload["schema_version"] = "agent-runtime-trace.v2"
        self._records.append(payload)
        self._times.append(self.clock())
        self._prune()
