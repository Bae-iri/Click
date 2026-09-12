"""Graph version promotion and rollback boundary."""

from __future__ import annotations


class GraphVersionManager:
    def promote(self, task_id: str, candidate_version: str) -> str:
        """Promote a validated candidate graph version."""
        return f"{task_id}:{candidate_version}"

    def rollback(self, task_id: str, target_version: str) -> str:
        """Rollback a task graph to a previous version."""
        return f"{task_id}:{target_version}"
