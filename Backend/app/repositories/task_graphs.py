from hashlib import sha256
import json
from sqlalchemy import select, update

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


class GraphReleaseConflictError(ValueError):
    def __init__(self, identifier, stored_hash, incoming_hash):
        super().__init__(
            f"GRAPH_RELEASE_IMMUTABLE: {identifier}; stored_sha256={stored_hash}; "
            f"incoming_sha256={incoming_hash}. Bump the graph version before deployment."
        )


class TaskGraphRepository:
    def __init__(self, factory):
        self.factory = factory

    def seed_mvp(self):
        """Validate the entire bundle, then atomically deploy new approved versions.

        Existing releases are checked even when inactive. Their activation state
        is preserved so a restart cannot undo a reviewed promotion or rollback.
        """
        prepared = []
        tasks = set()
        for release, graph in releases():
            body, digest = canonical_graph(graph)
            # Revalidation must preserve the artifact's existing hash (e.g. 0 vs 0.0).
            graph, _, _ = self._prepare(body, version=release["version"],
                approved=release["approved"])
            task_id = graph.definition.task_id
            if task_id in tasks:
                raise ValueError(f"Duplicate bundled graph task: {task_id}")
            tasks.add(task_id)
            prepared.append((release, graph, body, digest))
        with self.factory() as db:
            # Lock existing task releases before checking or changing activation.
            list(db.scalars(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.task_id.in_(tasks)).order_by(ApprovedTaskGraph.id).with_for_update()))
            pending = []
            for release, graph, body, digest in prepared:
                task_id = graph.definition.task_id
                identifier = f"{task_id}:{release['version']}"
                row = db.get(ApprovedTaskGraph, identifier)
                if row is not None:
                    self._check_release(row, graph, digest, approved=release["approved"],
                        priority=release["priority"])
                    continue
                pending.append(ApprovedTaskGraph(id=identifier, graph_id=task_id,
                    task_id=task_id, goal_type=graph.definition.goal_type,
                    target_app=graph.definition.target_app, graph_version=release["version"],
                    graph_hash=digest, schema_version="runtime-task-graph.v1", body=body,
                    active=False, approved=release["approved"], priority=release["priority"]))
            # No activation changes occur until every artifact and DB identity passes.
            for row in pending:
                if row.approved:
                    db.execute(update(ApprovedTaskGraph).where(
                        ApprovedTaskGraph.task_id == row.task_id).values(active=False))
                    row.active = True
                db.add(row)
            db.commit()

    @staticmethod
    def _prepare(body, *, version, approved):
        from app.schemas.agent_turn import TypedGoalRequest
        graph = load_graph(body, executable=approved)
        definition = graph.definition
        TypedGoalRequest(catalog_version="2.4.1", goal_type=definition.goal_type,
            target_app=definition.target_app, slots=definition.required_slots)
        if not isinstance(version, str) or not version or len(version) > 64:
            raise ValueError("Invalid graph version")
        if len(definition.task_id) > 100:
            raise ValueError("Invalid task ID")
        canonical, digest = canonical_graph(graph)
        return graph, canonical, digest

    @staticmethod
    def _check_release(row, graph, digest, *, approved, priority):
        definition = graph.definition
        stored = sha256(json.dumps(row.body, sort_keys=True, ensure_ascii=False,
            separators=(",", ":")).encode("utf-8")).hexdigest()
        if (row.graph_hash != digest or stored != digest or row.approved != approved
                or row.priority != priority or row.schema_version != "runtime-task-graph.v1"
                or row.graph_id != definition.task_id or row.task_id != definition.task_id
                or row.id != f"{definition.task_id}:{row.graph_version}"
                or row.goal_type != definition.goal_type or row.target_app != definition.target_app):
            raise GraphReleaseConflictError(row.id, stored, digest)

    def register(self, body, *, version, approved=False, priority=100):
        """Register an immutable release; candidates stay inactive until reviewed."""
        graph, canonical, digest = self._prepare(body, version=version, approved=approved)
        definition = graph.definition
        identifier = f"{definition.task_id}:{version}"
        with self.factory() as db:
            row = db.get(ApprovedTaskGraph, identifier)
            if row is not None:
                self._check_release(row, graph, digest, approved=approved, priority=priority)
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
