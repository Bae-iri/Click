"""Diagnostic metadata for AI1 responses and traces; no runtime decisions."""

from ai_agent.runtime.observation_context import device_context, vision_package_name
from ai_agent.runtime.types import (
    AgentTurnRequest, GuideCommand, JsonDict, MatchResult, NormalizedNode,
    TypedGoal, VerificationResult, VerificationStatus,
)
from ai_agent.runtime.vision import VisionFallbackAnalysis


def raw_observation_debug(request: AgentTurnRequest) -> JsonDict:
    vision_snapshot = request.vision_snapshot
    return {
        "accessibility_tree_present": bool(request.accessibility_tree),
        "accessibility_node_count": len(request.accessibility_tree),
        "vision_snapshot_present": vision_snapshot is not None,
        "vision_element_count": len(vision_snapshot.elements) if vision_snapshot else 0,
        "vision_has_screenshot": bool(vision_snapshot and vision_snapshot.screenshot_base64),
        "vision_has_ocr_text": bool(vision_snapshot and vision_snapshot.ocr_text),
        "vision_processing_time_ms": (
            vision_snapshot.processing_time_ms if vision_snapshot else None
        ),
        "foreground_app": device_context(request).foreground_app
        if device_context(request) else None,
        "app_package": vision_package_name(request),
    }


def observation_debug(
    *,
    request: AgentTurnRequest,
    normalized_nodes: tuple[NormalizedNode, ...],
    current_state_id: str,
    effective_state_id: str,
    vision_analysis: VisionFallbackAnalysis | None,
) -> JsonDict:
    raw_debug = raw_observation_debug(request)
    packages = sorted(
        {
            node.package_name
            for node in normalized_nodes
            if node.package_name
        }
    )
    roles = sorted({node.role for node in normalized_nodes})
    tags = sorted(
        {
            tag
            for node in normalized_nodes
            for tag in node.semantic_tags
        }
    )
    actionable_count = sum(1 for node in normalized_nodes if node.actionable)
    synthetic_vision_count = sum(
        1 for node in normalized_nodes if node.node_id.startswith("vision:")
    )
    raw_debug.update({
        "normalized_node_count": len(normalized_nodes),
        "actionable_node_count": actionable_count,
        "synthetic_vision_node_count": synthetic_vision_count,
        "current_state_id": current_state_id,
        "effective_state_id": effective_state_id,
        "observed_packages": packages,
        "visible_roles": roles,
        "visible_tags": tags,
        "vision_status": vision_analysis.status if vision_analysis else "not_requested",
    })
    return raw_debug


def fallback_debug(
    *,
    match_result: MatchResult,
    guide_command: GuideCommand,
    vision_analysis: VisionFallbackAnalysis | None,
    verification_result: VerificationResult | None,
) -> JsonDict:
    render_policy = guide_command.render_policy
    recovery_action = render_policy.get("recovery_action")
    fallback_action = render_policy.get("fallback_action")
    expected_next_observation = (
        render_policy.get("expected_next_observation")
        or (fallback_action or {}).get("expected_next_observation")
    )
    matched_from_vision = bool(
        match_result.target_node_id
        and match_result.target_node_id.startswith("vision:")
    )
    fallback_type = "none"
    if recovery_action:
        fallback_type = "settings_app_recovery"
    elif render_policy.get("vision_fallback"):
        fallback_type = "vision_fallback"
    elif matched_from_vision:
        fallback_type = "vision_augmented_match"
    elif match_result.fallback_reason:
        fallback_type = "target_match_fallback"
    elif verification_result and verification_result.status in {
        VerificationStatus.NO_CHANGE,
        VerificationStatus.WRONG_STATE,
        VerificationStatus.UNKNOWN,
    }:
        fallback_type = "replan"

    return {
        "type": fallback_type,
        "reason": (
            match_result.fallback_reason
            or (verification_result.message if verification_result else None)
        ),
        "matched_from_vision": matched_from_vision,
        "vision_status": vision_analysis.status if vision_analysis else "not_requested",
        "recovery_action": recovery_action,
        "fallback_action": fallback_action,
        "expected_next_observation": expected_next_observation,
    }


def agent_chain_debug(
    *,
    typed_goal: TypedGoal | None,
    vision_analysis: VisionFallbackAnalysis | None,
    match_result: MatchResult | None,
    guide_command: GuideCommand,
) -> JsonDict:
    fallback_used = (
        bool(match_result and match_result.fallback_reason)
        or bool(vision_analysis and vision_analysis.status != "not_requested")
        or bool(guide_command.render_policy.get("recovery_action"))
        or bool(guide_command.render_policy.get("vision_fallback"))
    )
    return {
        "goal_typing": {
            "method": typed_goal.typing_method if typed_goal else None,
            "goal_type": typed_goal.goal_type if typed_goal else None,
            "confidence": typed_goal.confidence if typed_goal else None,
        },
        "screen_classifier": "rule_based_task_graph",
        "matcher": getattr(match_result, "match_method", None),
        "similarity": "runtime_similarity_scorer",
        "verification": "snapshot_and_state_transition",
        "replanner": "rule_based_retry_exclusion",
        "fallback": "enabled" if fallback_used else "not_used",
        "guide_builder": guide_command.command_type.value,
    }
