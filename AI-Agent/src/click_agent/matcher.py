"""UI target matching for the current task state."""

from __future__ import annotations

from click_agent.keywords import any_keyword_matches
from click_agent.types import ActionType, MatchResult, NormalizedNode, TaskGraph, Transition


MATCH_THRESHOLD = 0.7


def match_next_ui(
    state_id: str,
    nodes: tuple[NormalizedNode, ...],
    graph: TaskGraph,
    *,
    excluded_node_ids: tuple[str, ...] = (),
) -> MatchResult:
    transitions = graph.transitions_from(state_id)
    if not transitions:
        return MatchResult(
            state_id=state_id,
            next_state_id=state_id,
            action_type=ActionType.OBSERVE_ONLY,
            target_node_id=None,
            target_label=None,
            target_bounds=None,
            match_method="none",
            confidence=0.0,
            fallback_reason="No transition is available from current state.",
        )

    for transition in transitions:
        if transition.target_role is None:
            return MatchResult(
                state_id=state_id,
                next_state_id=transition.to_state,
                action_type=transition.action_type,
                target_node_id=None,
                target_label=None,
                target_bounds=None,
                match_method="observe_only",
                confidence=1.0,
                transition_id=transition.transition_id,
            )

        candidate = _best_candidate(transition, nodes, excluded_node_ids)
        if candidate and candidate[1] >= MATCH_THRESHOLD:
            node, confidence, method = candidate
            return MatchResult(
                state_id=state_id,
                next_state_id=transition.to_state,
                action_type=transition.action_type,
                target_node_id=node.node_id,
                target_label=node.label,
                target_bounds=node.bounds,
                match_method=method,
                confidence=confidence,
                transition_id=transition.transition_id,
            )

    fallback_transition = transitions[0]
    return MatchResult(
        state_id=state_id,
        next_state_id=fallback_transition.to_state,
        action_type=fallback_transition.action_type,
        target_node_id=None,
        target_label=None,
        target_bounds=None,
        match_method="keyword",
        confidence=0.0,
        transition_id=fallback_transition.transition_id,
        fallback_reason="No matching target node found in current viewport.",
    )


def _best_candidate(
    transition: Transition,
    nodes: tuple[NormalizedNode, ...],
    excluded_node_ids: tuple[str, ...],
) -> tuple[NormalizedNode, float, str] | None:
    best: tuple[NormalizedNode, float, str] | None = None
    excluded = set(excluded_node_ids)

    for node in nodes:
        if node.node_id in excluded:
            continue
        confidence = 0.0
        methods: list[str] = []

        if any_keyword_matches(node.label, transition.target_keywords):
            confidence += 0.72
            methods.append("keyword")

        if set(node.semantic_tags).intersection(_semantic_targets(transition)):
            confidence += 0.12
            methods.append("semantic_tag")

        if transition.target_role and node.role == transition.target_role:
            confidence += 0.08
            methods.append("role")
        elif transition.target_role == "menu_item" and node.actionable:
            confidence += 0.04
            methods.append("actionable")

        if node.actionable:
            confidence += 0.08

        if transition.target_role == "slider" and node.role == "slider":
            confidence = max(confidence, 0.82)
            methods.append("slider_role")

        confidence = min(1.0, confidence)
        method = "+".join(dict.fromkeys(methods)) if methods else "none"
        if best is None or confidence > best[1]:
            best = (node, confidence, method)

    return best


def _semantic_targets(transition: Transition) -> set[str]:
    targets: set[str] = set()
    joined_keywords = " ".join(transition.target_keywords)
    if any_keyword_matches(joined_keywords, ("디스플레이", "화면", "display")):
        targets.add("display")
    if any_keyword_matches(joined_keywords, ("접근성", "시인성", "accessibility")):
        targets.add("accessibility")
    if any_keyword_matches(joined_keywords, ("글자 크기", "글자", "font size")):
        targets.add("font_size")
    if any_keyword_matches(joined_keywords, ("크게", "확대", "오른쪽")):
        targets.add("increase")
    return targets
