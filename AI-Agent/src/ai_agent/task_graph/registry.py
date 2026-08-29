"""Versioned Task Graph registry boundary."""

from __future__ import annotations

from .graph import TaskGraph


class TaskGraphRegistry:
    def __init__(self) -> None:
        self._graphs: dict[tuple[str, str], TaskGraph] = {}

    def register(self, graph: TaskGraph) -> None:
        self._graphs[(graph.task.task_id, graph.task.graph_version)] = graph

    def get(self, task_id: str, graph_version: str) -> TaskGraph:
        return self._graphs[(task_id, graph_version)]
