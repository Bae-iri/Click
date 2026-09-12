"""Explicit runtime recovery; graph replacement remains the backend's responsibility."""
from ai_agent.runtime.types import ReplanDecision, VerificationStatus as Status


def decide_replan(*, session, verification_result, failed_node_ids,
                  recovery_count=0, recovery_limit=3,
                  total_failed_attempts=0, max_failed_attempts=6, match_result=None):
    def decision(strategy, reason=None):
        return ReplanDecision(strategy != 'continue', reason, failed_node_ids, strategy=strategy)
    if total_failed_attempts >= max_failed_attempts:
        return decision('backend_reselection', 'failed_attempt_budget_exhausted')
    if recovery_count >= recovery_limit:
        return decision('backend_reselection', 'observation_recovery_exhausted')
    if verification_result:
        status = verification_result.status
        if status == Status.WAITING_FOR_OBSERVATION:
            return decision('reobserve', verification_result.evidence)
        if status == Status.UNKNOWN:
            return decision('reobserve', verification_result.evidence)
        if status == Status.WAITING_FOR_ACTION and not (match_result and match_result.fallback_reason):
            return decision('wait_for_action', 'no_correlated_action')
        if status in {Status.NO_CHANGE, Status.WRONG_STATE}:
            if match_result and match_result.target_node_id:
                alternate = session and (match_result.target_key != (session.pending_attempt or {}).get('target_key')
                            or match_result.transition_id != session.pending_transition_id)
                return decision('alternate_transition' if alternate else 'retry_target',
                                'verified_attempt_failed')
    if match_result and match_result.fallback_reason:
        return decision('reobserve', match_result.fallback_reason)
    return decision('continue')
