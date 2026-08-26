"""Select the target UI node for a transition."""

from __future__ import annotations

from ai_agent.schemas import NormalizedNode, TargetMatch, TransitionDefinition

from .scoring import fuse_scores


class UITargetMatcher:
    def match(self, transition: TransitionDefinition, nodes: list[NormalizedNode]) -> TargetMatch:
        best_node: NormalizedNode | None = None
        best_score = 0.0
        best_breakdown: dict[str, float] = {}

        for node in nodes:
            keyword_score = 1.0 if any(keyword in node.label for keyword in transition.target_keywords) else 0.0
            role_score = 1.0 if transition.target_role and transition.target_role in node.role else 0.0
            actionability_score = 1.0 if node.clickable and node.enabled else 0.0
            embedding_score = 0.0
            score = fuse_scores(keyword_score, embedding_score, role_score, actionability_score)
            if score > best_score:
                best_node = node
                best_score = score
                best_breakdown = {
                    "keyword_score": keyword_score,
                    "embedding_score": embedding_score,
                    "role_score": role_score,
                    "actionability_score": actionability_score,
                }

        return TargetMatch(
            action_type=transition.action_type,
            target_node_id=best_node.node_id if best_node else None,
            target_label=best_node.label if best_node else None,
            confidence=best_score,
            match_method="keyword+role+actionability",
            score_breakdown=best_breakdown,
        )
