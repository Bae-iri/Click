"""Closed failure facts and the change required before another selection attempt."""
from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class SelectionFailure:
    stage: str
    cause: str
    retryable: bool
    required_change: str
    outcome_uncertain: bool
    reason_code: str
    next_action: str

    def to_dict(self):
        return asdict(self)


# Retryable means retryable AFTER required_change, never repeat identical input.
FAILURES = {
    "unsupported_screen": ("observation", "UNSUPPORTED_SCREEN", "supported_screen", "manual_navigation"),
    "sensitive_screen": ("observation", "SENSITIVE_SCREEN", "safe_screen", "manual_navigation"),
    "empty_observation": ("observation", "OBSERVATION_INSUFFICIENT", "new_observation", "reobserve"),
    "candidate_limit": ("candidate_generation", "CANDIDATE_LIMIT", "narrow_observation", "narrow_observation"),
    "no_candidates": ("candidate_generation", "NO_CANDIDATES", "new_target_evidence", "manual_navigation"),
    "ungrounded_replan": ("planning", "REPLAN_START_NOT_VISIBLE", "valid_plan", "reobserve"),
    "invalid_candidates": ("candidate_generation", "INVALID_CANDIDATES", "valid_candidates", "user_assistance"),
    "ambiguous_candidates": ("candidate_generation", "TARGET_AMBIGUOUS", "user_clarification", "ask_user"),
    "input_too_large": ("candidate_generation", "OBSERVATION_TOO_LARGE", "narrow_observation", "narrow_observation"),
    "not_configured": ("configuration", "MODEL_NOT_CONFIGURED", "provider_configuration", "restore_provider"),
    "network_error": ("provider", "MODEL_UNAVAILABLE", "connection_restored", "restore_connection"),
    "provider_error": ("provider", "MODEL_UNAVAILABLE", "provider_available", "restore_provider"),
    "timeout": ("provider", "MODEL_UNAVAILABLE", "provider_available", "restore_provider"),
    "deadline_exceeded": ("provider", "DEADLINE_EXCEEDED", "fresh_time_budget", "restore_provider"),
    "invalid_response": ("model_response", "MODEL_UNAVAILABLE", "valid_model_response", "restore_provider"),
    "low_confidence": ("model_selection", "LOW_CONFIDENCE", "user_clarification", "ask_user"),
    "abstained": ("model_selection", "MODEL_ABSTAINED", "user_clarification", "ask_user"),
    "waiting": ("model_selection", "MODEL_WAIT_WITHOUT_ACTION", "user_clarification", "ask_user"),
    "result_unobservable": ("verification", "ACTION_RESULT_UNOBSERVABLE", "action_result_evidence", "reobserve"),
    "settling": ("verification", "WAITING_FOR_SETTLE", "settle_interval", "reobserve"),
    "unknown_failure": ("selection", "SELECTION_FAILED", "diagnostic_review", "user_assistance"),
}
EXTERNAL_CHANGES = {"connection_restored", "provider_available", "provider_configuration",
                    "fresh_time_budget", "valid_model_response"}


def selection_failure(diagnostics, *, outcome_uncertain=False, cause=None):
    cause = cause or diagnostics.get("status")
    # A model decision exists only after a call and a validated response.
    if cause not in FAILURES or (cause in {"abstained", "low_confidence", "waiting", "invalid_response"}
                                and diagnostics.get("called") is not True):
        cause = "unknown_failure"
    stage, reason, change, action = FAILURES[cause]
    if cause == "deadline_exceeded" and diagnostics.get("called") is not True:
        stage = "request_preparation"
    return SelectionFailure(stage, cause, cause != "unknown_failure", change,
                            outcome_uncertain, reason, action)
