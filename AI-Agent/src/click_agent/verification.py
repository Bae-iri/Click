"""Verification helpers for post-action observe cycles."""

from __future__ import annotations

from click_agent.types import ScreenSnapshot, TaskGraph, VerificationResult, VerificationStatus


def verify_transition(
    *,
    graph: TaskGraph,
    previous_state_id: str | None,
    pending_transition_id: str | None,
    current_state_id: str,
    previous_snapshot: ScreenSnapshot | None = None,
    current_snapshot: ScreenSnapshot | None = None,
) -> VerificationResult | None:
    if not previous_state_id or not pending_transition_id:
        return None

    transition = next(
        (
            candidate
            for candidate in graph.transitions
            if candidate.transition_id == pending_transition_id
        ),
        None,
    )
    if transition is None:
        return VerificationResult(
            status=VerificationStatus.UNKNOWN,
            current_state_id=current_state_id,
            message="Pending transition is unknown.",
        )

    if _slider_value_increased(previous_snapshot, current_snapshot):
        return VerificationResult(
            status=VerificationStatus.SUCCESS,
            current_state_id=current_state_id,
            expected_state_id=transition.to_state,
            message="Slider value increased in the requested direction.",
        )

    if transition.to_state == graph.definition.done_state:
        return VerificationResult(
            status=VerificationStatus.DONE,
            current_state_id=current_state_id,
            expected_state_id=transition.to_state,
            message="Task is complete.",
        )

    if current_state_id == transition.to_state:
        return VerificationResult(
            status=VerificationStatus.SUCCESS,
            current_state_id=current_state_id,
            expected_state_id=transition.to_state,
            message="Expected screen transition succeeded.",
        )

    if current_state_id == previous_state_id:
        if (
            previous_snapshot
            and current_snapshot
            and previous_snapshot.fingerprint == current_snapshot.fingerprint
        ):
            return VerificationResult(
                status=VerificationStatus.NO_CHANGE,
                current_state_id=current_state_id,
                expected_state_id=transition.to_state,
                message="Screen snapshot did not change after user action.",
            )
        return VerificationResult(
            status=VerificationStatus.NO_CHANGE,
            current_state_id=current_state_id,
            expected_state_id=transition.to_state,
            message="Screen did not change after user action.",
        )

    return VerificationResult(
        status=VerificationStatus.WRONG_STATE,
        current_state_id=current_state_id,
        expected_state_id=transition.to_state,
        message="Observed screen differs from expected state.",
    )


def _slider_value_increased(
    previous_snapshot: ScreenSnapshot | None,
    current_snapshot: ScreenSnapshot | None,
) -> bool:
    if not previous_snapshot or not current_snapshot:
        return False

    for node_id, previous_value in previous_snapshot.slider_values.items():
        current_value = current_snapshot.slider_values.get(node_id)
        if current_value is not None and current_value > previous_value:
            return True
    return False
