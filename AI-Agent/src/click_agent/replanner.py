"""Rule-based replanning decisions for unexpected screens."""

from __future__ import annotations

from click_agent.state_manager import RETRY_LIMIT_BEFORE_EXCLUSION
from click_agent.types import AgentSessionState, ReplanDecision, VerificationResult, VerificationStatus


def decide_replan(
    *,
    session: AgentSessionState | None,
    verification_result: VerificationResult | None,
    failed_node_ids: tuple[str, ...],
    transition_attempts: dict[str, int] | None = None,
) -> ReplanDecision:
    if verification_result is None:
        return ReplanDecision(should_replan=False)

    if verification_result.status == VerificationStatus.NO_CHANGE:
        pending_transition_id = session.pending_transition_id if session else None
        attempts = (
            (transition_attempts or session.transition_attempts).get(
                pending_transition_id,
                0,
            )
            if session and pending_transition_id
            else 0
        )
        if attempts >= RETRY_LIMIT_BEFORE_EXCLUSION:
            return ReplanDecision(
                should_replan=True,
                reason="same_screen_retry_limit_reached",
                excluded_node_ids=failed_node_ids,
            )
        return ReplanDecision(
            should_replan=True,
            reason="same_screen_retry",
            excluded_node_ids=(),
        )

    if verification_result.status == VerificationStatus.WRONG_STATE:
        return ReplanDecision(
            should_replan=True,
            reason="unexpected_state_observed",
            excluded_node_ids=failed_node_ids,
        )

    if verification_result.status == VerificationStatus.UNKNOWN:
        return ReplanDecision(
            should_replan=True,
            reason="state_unknown",
            excluded_node_ids=failed_node_ids,
        )

    return ReplanDecision(should_replan=False)
