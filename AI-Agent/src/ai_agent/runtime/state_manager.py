"""State tracking helpers for L3-style multi-turn task execution."""

from __future__ import annotations

from ai_agent.runtime.types import (
    AgentSessionState,
    NormalizedNode,
    ScreenSnapshot,
    UserActionEvent,
    VerificationResult,
    VerificationStatus,
)


RETRY_LIMIT_BEFORE_EXCLUSION = 2


def build_screen_snapshot(
    state_id: str,
    nodes: tuple[NormalizedNode, ...],
) -> ScreenSnapshot:
    stable_parts = sorted(
        "|".join(
            (
                node.node_id,
                node.label,
                node.role,
                str(node.bounds),
                str(node.selected),
                str(node.value),
            )
        )
        for node in nodes
    )
    slider_values = {
        node.node_id: node.value
        for node in nodes
        if node.role == "slider" and node.value is not None
    }
    return ScreenSnapshot(
        state_id=state_id,
        fingerprint="\n".join(stable_parts),
        slider_values=slider_values,
    )


def register_attempt_outcome(
    session: AgentSessionState | None,
    *,
    pending_transition_id: str | None,
    verification_result: VerificationResult | None,
) -> tuple[dict[str, int], tuple[str, ...]]:
    if session is None:
        return {}, ()

    attempts = dict(session.transition_attempts)
    failed_nodes = set(session.failed_node_ids)
    if not pending_transition_id or not verification_result:
        return attempts, tuple(failed_nodes)

    if verification_result.status in {
        VerificationStatus.NO_CHANGE,
        VerificationStatus.WRONG_STATE,
        VerificationStatus.UNKNOWN,
    }:
        attempts[pending_transition_id] = attempts.get(pending_transition_id, 0) + 1
        if attempts[pending_transition_id] >= RETRY_LIMIT_BEFORE_EXCLUSION:
            if session.last_target_node_id:
                failed_nodes.add(session.last_target_node_id)
    elif verification_result.status in {
        VerificationStatus.SUCCESS,
        VerificationStatus.DONE,
    }:
        attempts.pop(pending_transition_id, None)

    return attempts, tuple(sorted(failed_nodes))


def should_treat_as_manual_override(event: UserActionEvent | None) -> bool:
    return bool(event and event.action_type.value == "manual_override")
