"""Model-assisted choice among graph-grounded controls from one observation."""
from dataclasses import dataclass, replace
from typing import Protocol

from ai_agent.runtime.classifier import _screen_titles
from ai_agent.runtime.keywords import any_phrase_matches
from ai_agent.runtime.matcher import MATCH_THRESHOLD
from ai_agent.runtime.targeting import eligible_nodes, selector_evidence, selector_keywords, target_key
from ai_agent.runtime.types import ActionType, SafetyLevel

MAX_CANDIDATES = 32


@dataclass(frozen=True)
class UiChoice:
    choice: str | None = None
    confidence: float | None = None
    status: str = "unavailable"
    called: bool = False


class UiSelector(Protocol):
    provider: str

    def select(self, observation: dict) -> UiChoice: ...


def select_ui_target(match, nodes, graph, selector, *, excluded_target_keys=None, screen_bounds=None):
    """Keep graph order, consent and completion rules; only replace the observed target."""
    info = {"provider": getattr(selector, "provider", "rules"), "status": "disabled", "called": False}
    if selector is None:
        return match, info
    info["status"] = "ineligible"
    transition = next((edge for edge in graph.transitions_from(match.state_id)
                       if edge.transition_id == match.transition_id), None)
    if (transition is None or transition.safety_level == SafetyLevel.BLOCKED
            or transition.action_type not in {ActionType.TAP, ActionType.DRAG}
            or not nodes or len({node.window_id for node in nodes}) > 1
            or len({node.node_id for node in nodes}) != len(nodes)):
        return match, info

    excluded = (excluded_target_keys or {}).get(transition.transition_id, ())
    candidates = []
    for node in eligible_nodes(transition, nodes, graph):
        if target_key(node) in excluded:
            continue
        if screen_bounds:
            x, y = (node.bounds[0] + node.bounds[2]) / 2, (node.bounds[1] + node.bounds[3]) / 2
            if not (screen_bounds[0] <= x < screen_bounds[2] and screen_bounds[1] <= y < screen_bounds[3]):
                continue
        score, method = selector_evidence(node, transition, nodes, graph)
        if node.source_confidence is not None:
            score = min(score, node.source_confidence)
        # Language confidence cannot make an unrelated control executable.
        if score >= MATCH_THRESHOLD:
            candidates.append((node, score, method))
    identified = [candidate for candidate in candidates if candidate[2] == "resource_id"]
    if identified:
        candidates = identified
    if not candidates or len(candidates) > MAX_CANDIDATES:
        info["status"] = "no_candidates" if not candidates else "candidate_limit"
        return match, info

    vocabulary = set(selector_keywords(transition, graph))
    for state in graph.states.values():
        vocabulary.update(state.required_keywords)
        vocabulary.update(state.optional_keywords)
        for group in state.required_keyword_groups:
            vocabulary.update(group)
    for words in graph.synonyms.values():
        vocabulary.update(words)

    def terms(text):
        return sorted(word for word in vocabulary if any_phrase_matches(text, (word,)))

    def describe(node):
        return {"terms": terms(" ".join(node.label_parts or (node.label,))),
                "primary_terms": terms(node.label_parts[0] if node.label_parts else node.label),
                "role": node.role, "position": node.position_hint, "selected": node.selected}

    references = {f"ui_{index}": candidate for index, candidate in enumerate(candidates)}
    observation = {
        "goal": {"type": graph.definition.goal_type, "target_app": graph.definition.target_app},
        "step": {"state_id": match.state_id, "next_state_id": transition.to_state,
                 "action": transition.action_type.value, "target_terms": list(selector_keywords(transition, graph))},
        "screen_titles": [describe(node) for node in _screen_titles(nodes) if terms(node.label)][:8],
        "candidates": [{"id": ref, **describe(node), "match_basis": method}
                       for ref, (node, _, method) in references.items()],
    }
    # Original labels, node/resource IDs, coordinates, screenshots and request IDs stay local.
    info["candidate_count"] = len(candidates)
    try:
        answer = selector.select(observation)
        if not isinstance(answer, UiChoice):
            raise ValueError("Invalid UI selection result")
        info.update(status=answer.status, called=answer.called)
        if answer.confidence is not None:
            info["confidence"] = answer.confidence
        if answer.status != "selected":
            return match, info
        selected = references.get(answer.choice)
        if selected is None:
            info["status"] = "invalid_choice"
            return match, info
    except Exception:
        info["status"] = "provider_error"
        return match, info

    node, score, _ = selected
    return replace(match, next_state_id=transition.to_state, action_type=transition.action_type,
                   target_node_id=node.node_id, target_label=node.label, target_bounds=node.bounds,
                   match_method="vision_jev_choice" if node.source_confidence is not None else "jev_choice",
                   confidence=score, fallback_reason=None,
                   target_key=target_key(node), score_gap=None), info
