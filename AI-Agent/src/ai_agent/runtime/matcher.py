"""Select within an explicitly prioritized graph edge; abstain on ambiguity."""
from ai_agent.runtime.types import ActionType, MatchResult
from ai_agent.runtime.targeting import eligible_nodes, selector_evidence, selector_keywords, target_key
from ai_agent.runtime.keywords import any_keyword_matches

MATCH_THRESHOLD = 0.7
AMBIGUITY_MARGIN = 0.05


def match_next_ui(state_id, nodes, graph, *, excluded_node_ids=(),
                  excluded_target_keys=None, similarity_scorer=None):
    transitions = graph.transitions_from(state_id)
    if not transitions:
        return MatchResult(state_id, state_id, ActionType.OBSERVE_ONLY, None, None, None,
                           'none', 0.0, fallback_reason='no_transition')
    fallback_reason = 'target_not_found'
    for transition in transitions:
        if transition.target_role is None:
            return MatchResult(state_id, transition.to_state, transition.action_type,
                               None, None, None, 'observe_only', 1.0,
                               transition_id=transition.transition_id)
        excluded_keys = (excluded_target_keys or {}).get(transition.transition_id, ())
        candidates = []
        for node in eligible_nodes(transition, nodes, graph):
            if node.node_id in excluded_node_ids or target_key(node) in excluded_keys:
                continue
            score, method = selector_evidence(node, transition, nodes, graph)
            # Similarity can recover labels after all role/package/geometry constraints.
            # An unrelated named slider must never be rescued by language similarity.
            if (score == 0 and node.label and node.role != 'slider' and similarity_scorer
                    and not any_keyword_matches(node.label, selector_keywords(transition, graph))):
                similarity = max((similarity_scorer.score(node.label, keyword)
                                  for keyword in selector_keywords(transition, graph)), default=0)
                score, method = min(0.79, max(0.0, similarity) * 0.79), 'similarity'
            candidates.append((score, node, method))
        candidates.sort(key=lambda candidate: (-candidate[0], candidate[1].node_id))
        if not candidates or candidates[0][0] < MATCH_THRESHOLD:
            continue
        score, node, method = candidates[0]
        gap = score - candidates[1][0] if len(candidates) > 1 else None
        if gap is not None and gap < AMBIGUITY_MARGIN:
            # Do not silently bypass an ambiguous preferred edge with another path.
            return MatchResult(state_id, transition.to_state, ActionType.OBSERVE_ONLY,
                               None, None, None, 'ambiguous', score,
                               transition_id=transition.transition_id,
                               fallback_reason='ambiguous_target', score_gap=gap)
        return MatchResult(state_id, transition.to_state, transition.action_type,
                           node.node_id, node.label, node.bounds, method, score,
                           transition_id=transition.transition_id, target_key=target_key(node),
                           score_gap=gap)
    transition = transitions[0]
    return MatchResult(state_id, transition.to_state, transition.action_type,
                       None, None, None, 'none', 0.0,
                       transition_id=transition.transition_id, fallback_reason=fallback_reason)
