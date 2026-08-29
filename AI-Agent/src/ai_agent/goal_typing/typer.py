"""Raw natural language to Typed Goal pipeline."""

from __future__ import annotations

from ai_agent.schemas import RawGoalInput, TypedGoal

from .catalog import GoalCatalog
from .normalizer import GoalTextNormalizer
from .retriever import GoalRetriever
from .slot_extractor import SlotExtractor


class GoalTyper:
    def __init__(
        self,
        catalog: GoalCatalog,
        normalizer: GoalTextNormalizer | None = None,
        retriever: GoalRetriever | None = None,
        slot_extractor: SlotExtractor | None = None,
    ) -> None:
        self.catalog = catalog
        self.normalizer = normalizer or GoalTextNormalizer()
        self.retriever = retriever or GoalRetriever()
        self.slot_extractor = slot_extractor or SlotExtractor()

    def type_goal(self, raw_goal: RawGoalInput) -> TypedGoal | None:
        normalized_text = self.normalizer.normalize(raw_goal.source_text)
        slots = self.slot_extractor.extract(normalized_text)
        candidates = self.retriever.retrieve(normalized_text, self.catalog.entries)
        if not candidates:
            return None

        best = max(candidates, key=lambda candidate: candidate.confidence)
        return TypedGoal(
            goal_type=best.entry.goal_type,
            target_app=best.entry.target_app,
            task_type=best.entry.task_type,
            slots=slots,
            source_text=raw_goal.source_text,
            confidence=best.confidence,
            top_candidates=[
                {"goal_type": candidate.entry.goal_type, "confidence": candidate.confidence}
                for candidate in sorted(candidates, key=lambda item: item.confidence, reverse=True)[:5]
            ],
        )
