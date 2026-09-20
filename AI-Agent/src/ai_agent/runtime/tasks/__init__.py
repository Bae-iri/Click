"""Task graph registry."""

from .mvp_01_font_size_increase import MVP_01_TASK_GRAPH
from .mvp_02_font_size_decrease import MVP_02_TASK_GRAPH

TASK_GRAPHS = {
    MVP_01_TASK_GRAPH.definition.task_id: MVP_01_TASK_GRAPH,
    MVP_01_TASK_GRAPH.definition.goal_type: MVP_01_TASK_GRAPH,
    MVP_02_TASK_GRAPH.definition.task_id: MVP_02_TASK_GRAPH,
    MVP_02_TASK_GRAPH.definition.goal_type: MVP_02_TASK_GRAPH,
}

__all__ = ["MVP_01_TASK_GRAPH", "MVP_02_TASK_GRAPH", "TASK_GRAPHS"]
