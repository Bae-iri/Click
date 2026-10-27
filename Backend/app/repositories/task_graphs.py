from hashlib import sha256
import json
from sqlalchemy import select

from app.task_graphs import approved_graphs, releases
from app.task_graphs.validation import load_graph
from app.models.task_graph import ApprovedTaskGraph

TASK_GRAPHS = approved_graphs()


def canonical_graph(graph):
    body = graph.to_dict()
    encoded = json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return json.loads(encoded), sha256(encoded.encode("utf-8")).hexdigest()


class NoCompatibleGraphError(ValueError):
    pass


class AmbiguousGraphError(ValueError):
    pass


class TaskGraphRepository:
    def __init__(self, factory):
        self.factory = factory

    def seed_mvp(self):
        with self.factory() as db:
            graphs = {graph.definition.task_id: graph for graph in TASK_GRAPHS.values()}
            for task_id, graph in graphs.items():
                body, digest = canonical_graph(graph)
                row = db.get(ApprovedTaskGraph, f"{task_id}:1.0.0")
                if row is None:
                    db.add(ApprovedTaskGraph(id=f"{task_id}:1.0.0", graph_id=task_id,
                        task_id=task_id, goal_type=graph.definition.goal_type,
                        target_app=graph.definition.target_app, graph_version="1.0.0",
                        graph_hash=digest, schema_version="runtime-task-graph.v1", body=body,
                        active=True, approved=True, priority=100))
            for release, graph in releases():
                if release["approved"]:
                    continue
                body, digest = canonical_graph(graph)
                task_id = graph.definition.task_id
                identifier = f"{task_id}:{release['version']}"
                if db.get(ApprovedTaskGraph, identifier) is None:
                    db.add(ApprovedTaskGraph(id=identifier, graph_id=task_id,
                        task_id=task_id, goal_type=graph.definition.goal_type,
                        target_app=graph.definition.target_app, graph_version=release["version"],
                        graph_hash=digest, schema_version="runtime-task-graph.v1", body=body,
                        active=False, approved=False, priority=release["priority"]))
            db.commit()

    def register(self, body, *, version, approved=False, priority=100):
        """Register an immutable release; candidates stay inactive until reviewed."""
        from app.schemas.agent_turn import TypedGoalRequest
        graph = load_graph(body, executable=approved)
        definition = graph.definition
        TypedGoalRequest(catalog_version="2.1.0", goal_type=definition.goal_type,
            target_app=definition.target_app, slots=definition.required_slots)
        if not isinstance(version, str) or not version or len(version) > 64:
            raise ValueError("Invalid graph version")
        if len(definition.task_id) > 100:
            raise ValueError("Invalid task ID")
        canonical, digest = canonical_graph(graph)
        identifier = f"{definition.task_id}:{version}"
        with self.factory() as db:
            row = db.get(ApprovedTaskGraph, identifier)
            if row is not None:
                if row.graph_hash != digest or row.approved != approved or row.priority != priority:
                    raise ValueError("GRAPH_RELEASE_IMMUTABLE")
            else:
                db.add(ApprovedTaskGraph(id=identifier, graph_id=definition.task_id,
                    task_id=definition.task_id, goal_type=definition.goal_type,
                    target_app=definition.target_app, graph_version=version,
                    graph_hash=digest, schema_version="runtime-task-graph.v1", body=canonical,
                    active=approved, approved=approved, priority=priority))
                db.commit()
        return {"task_id": definition.task_id, "version": version,
                "sha256": digest, "schema_version": "runtime-task-graph.v1"}

    @staticmethod
    def _decode(row):
        if row.schema_version != "runtime-task-graph.v1":
            raise ValueError("GRAPH_SCHEMA_INCOMPATIBLE")
        stored = sha256(json.dumps(row.body, sort_keys=True, ensure_ascii=False,
            separators=(",", ":")).encode("utf-8")).hexdigest()
        if stored != row.graph_hash:
            raise ValueError("GRAPH_REFERENCE_MISMATCH")
        graph = load_graph(row.body)
        definition = graph.definition
        if (definition.task_id != row.task_id or definition.goal_type != row.goal_type
                or definition.target_app != row.target_app):
            raise ValueError("GRAPH_REFERENCE_MISMATCH")
        return graph, {"task_id": row.task_id, "version": row.graph_version,
            "sha256": row.graph_hash, "schema_version": row.schema_version}

    def select_pinned(self, details):
        with self.factory() as db:
            row = db.scalar(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.task_id == details["task_id"],
                ApprovedTaskGraph.graph_version == details["graph_version"],
                ApprovedTaskGraph.goal_type == details["goal_type"],
                ApprovedTaskGraph.target_app == details["goal"]["target_app"],
                ApprovedTaskGraph.graph_hash == details["graph_hash"],
                ApprovedTaskGraph.approved.is_(True)))
            if row is None:
                raise NoCompatibleGraphError("NO_COMPATIBLE_GRAPH")
            return self._decode(row)

    def select(self, goal, *, excluded_hashes=()):
        with self.factory() as db:
            rows = list(db.scalars(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.goal_type == goal.goal_type,
                ApprovedTaskGraph.target_app == goal.target_app,
                ApprovedTaskGraph.graph_hash.not_in(tuple(excluded_hashes)),
                ApprovedTaskGraph.active.is_(True), ApprovedTaskGraph.approved.is_(True)
            ).order_by(ApprovedTaskGraph.priority.desc())))
            if not rows:
                raise NoCompatibleGraphError("NO_COMPATIBLE_GRAPH")
            if len(rows) > 1 and rows[0].priority == rows[1].priority:
                raise AmbiguousGraphError("GRAPH_SELECTION_AMBIGUOUS")
            row = rows[0]
            return self._decode(row)

    def select_for_inputs(self, goal, values):
        from app.services.goal_inputs import GoalInputRequirements
        GoalInputRequirements().validate_selection(goal, values)
        return self.select(goal)
