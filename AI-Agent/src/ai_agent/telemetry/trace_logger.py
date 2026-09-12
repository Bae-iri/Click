"""Bounded, expiring diagnostics with no arbitrary text fields."""
from collections.abc import Mapping
from time import monotonic
import math

CONTAINERS = {"observation", "agent_chain", "goal_typing", "vision_fallback",
              "verification", "match", "guide", "fallback", "debug", "result"}
VISION = {"used", "not_requested", "not_configured", "provider_error", "no_signal", "consent_required"}
ENUMS = {
    "status": VISION | {"SUCCESS", "DONE", "NO_CHANGE", "WRONG_STATE", "UNKNOWN"},
    "vision_status": VISION,
    "command_type": {"highlight", "gesture_hint", "confirmation", "done", "blocked"},
    "action_type": {"tap", "drag", "scroll", "observe_only"},
    "safety_level": {"LOW_RISK", "CONFIRM_REQUIRED", "BLOCKED"},
    "type": {"none", "settings_app_recovery", "vision_fallback", "vision_augmented_match", "target_match_fallback", "replan"},
}
METRICS = {"confidence", "element_count", "accessibility_node_count", "vision_element_count",
           "normalized_node_count", "actionable_node_count", "synthetic_vision_node_count",
           "accessibility_tree_present", "vision_snapshot_present", "vision_has_screenshot",
           "vision_has_ocr_text", "requires_user_action", "session_stateful", "matched_from_vision"}


def diagnostic_payload(value, depth=0):
    if not isinstance(value, Mapping) or depth > 5:
        return {}
    output = {}
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
        payload["schema_version"] = "agent-runtime-trace.v1"
        self._records.append(payload)
        self._times.append(self.clock())
        self._prune()
