from hashlib import sha256
import json
from sqlalchemy import select

from ai_agent.runtime.tasks import TASK_GRAPHS
from app.models.task_graph import ApprovedTaskGraph


def canonical_graph(graph):
    body = graph.to_dict()
    encoded = json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return body, sha256(encoded.encode("utf-8")).hexdigest()


class TaskGraphRepository:
    def __init__(self, factory):
        self.factory = factory

    def seed_mvp(self):
        with self.factory() as db:
            graphs = {graph.definition.task_id: graph for graph in TASK_GRAPHS.values()}
            for task_id, graph in graphs.items():
                body, digest = canonical_graph(graph)
                row = db.get(ApprovedTaskGraph, f"{task_id}:2.0.0")
                if row is None:
                    db.add(ApprovedTaskGraph(id=f"{task_id}:2.0.0", graph_id=task_id,
                        task_id=task_id, target_app=graph.definition.target_app, graph_version="2.0.0",
                        graph_hash=digest, schema_version="runtime-task-graph.v1", body=body,
                        active=True, approved=True, priority=100))
            db.commit()

    def candidates(self):
        with self.factory() as db:
            rows = list(db.scalars(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.active.is_(True), ApprovedTaskGraph.approved.is_(True)
            ).order_by(ApprovedTaskGraph.task_id, ApprovedTaskGraph.graph_version.desc())))
            return [self._validated(row) for row in rows]

    def pinned(self, task_id, version, digest):
        if not task_id or not version or not digest:
            raise ValueError("PINNED_GRAPH_REFERENCE_MISSING")
        with self.factory() as db:
            row = db.scalar(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.task_id == task_id,
                ApprovedTaskGraph.graph_version == version,
                ApprovedTaskGraph.graph_hash == digest,
                ApprovedTaskGraph.active.is_(True), ApprovedTaskGraph.approved.is_(True)))
            if row is None:
                raise ValueError("PINNED_GRAPH_UNAVAILABLE")
            return self._validated(row)

    @staticmethod
    def _validated(row):
        graph = TASK_GRAPHS.get(row.task_id)
        if graph is None:
            raise ValueError("APPROVED_GRAPH_IMPLEMENTATION_MISSING")
        body, digest = canonical_graph(graph)
        stored = sha256(json.dumps(row.body, sort_keys=True, ensure_ascii=False,
            separators=(",", ":")).encode("utf-8")).hexdigest()
        if digest != row.graph_hash or stored != row.graph_hash:
            raise ValueError("GRAPH_REFERENCE_MISMATCH")
        return graph, {"task_id": row.task_id, "version": row.graph_version,
            "sha256": row.graph_hash, "schema_version": row.schema_version}
