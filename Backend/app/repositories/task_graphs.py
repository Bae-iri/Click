from hashlib import sha256
import json
from sqlalchemy import select

from ai_agent.runtime.tasks import TASK_GRAPHS
from app.models.task_graph import ApprovedTaskGraph


def canonical_graph(graph):
    body = graph.to_dict()
    encoded = json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return body, sha256(encoded.encode("utf-8")).hexdigest()


class NoCompatibleGraphError(ValueError):
    pass


class AmbiguousGraphError(ValueError):
    pass


class TaskGraphRepository:
    def __init__(self, factory):
        self.factory = factory

    def seed_mvp(self):
        graph = TASK_GRAPHS["MVP-01"]
        body, digest = canonical_graph(graph)
        with self.factory() as db:
            row = db.get(ApprovedTaskGraph, "MVP-01:1.0.0")
            if row is None:
                db.add(ApprovedTaskGraph(id="MVP-01:1.0.0", graph_id="MVP-01",
                    task_id="MVP-01", goal_type=graph.definition.goal_type,
                    target_app=graph.definition.target_app, graph_version="1.0.0",
                    graph_hash=digest, schema_version="runtime-task-graph.v1", body=body,
                    active=True, approved=True, priority=100))
                db.commit()

    def select(self, goal):
        with self.factory() as db:
            rows = list(db.scalars(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.goal_type == goal.goal_type,
                ApprovedTaskGraph.target_app == goal.target_app,
                ApprovedTaskGraph.active.is_(True), ApprovedTaskGraph.approved.is_(True)
            ).order_by(ApprovedTaskGraph.priority.desc())))
            if not rows:
                raise NoCompatibleGraphError("NO_COMPATIBLE_GRAPH")
            if len(rows) > 1 and rows[0].priority == rows[1].priority:
                raise AmbiguousGraphError("GRAPH_SELECTION_AMBIGUOUS")
            row = rows[0]
            graph = TASK_GRAPHS.get(row.task_id)
            if graph is None:
                raise NoCompatibleGraphError("NO_COMPATIBLE_GRAPH")
            body, digest = canonical_graph(graph)
            stored = sha256(json.dumps(row.body, sort_keys=True, ensure_ascii=False,
                separators=(",", ":")).encode("utf-8")).hexdigest()
            if digest != row.graph_hash or stored != row.graph_hash:
                raise ValueError("GRAPH_REFERENCE_MISMATCH")
            return graph, {"task_id": row.task_id, "version": row.graph_version,
                "sha256": row.graph_hash, "schema_version": row.schema_version}
