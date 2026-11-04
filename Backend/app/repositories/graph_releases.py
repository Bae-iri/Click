"""Transactional release adapter. Candidates never become active on registration."""
from copy import deepcopy
from hashlib import sha256
import json
import time
from sqlalchemy import select, update
from app.models.task_graph import ApprovedTaskGraph
from app.models.graph_release import GraphReleaseAudit
from app.repositories.task_graphs import TaskGraphRepository, canonical_graph
from app.task_graphs.validation import load_graph


def digest(value):
    return sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


class GraphReleaseRepository:
    def __init__(self, factory):
        self.factory = factory

    def publish(self, release):
        body, graph_hash = canonical_graph(load_graph(release["body"]))
        evaluation = release["evaluation"]
        if (release.get("schema_version") != "ai2-release.v1" or graph_hash != release["graph_hash"]
                or digest(evaluation) != release["evaluation_hash"] or evaluation.get("decision") != "PASS"
                or evaluation.get("candidate_hash") != graph_hash or evaluation.get("base_graph_hash") != release["base_hash"]
                or evaluation.get("proposal_id") != release["proposal_id"] or not evaluation.get("product_case_ids")
                or evaluation.get("candidate_failures") != 0 or not evaluation.get("checks")
                or evaluation.get("missing") or evaluation.get("errors")
                or evaluation.get("regression", {}).get("has_regression") is not False
                or not release.get("reviewer") or type(release.get("reviewed_at")) is not int):
            raise ValueError("Invalid reviewed release")
        task = body["definition"]["task_id"]
        version = release["version"]
        if task != release["task_id"] or not isinstance(version, str) or not 1 <= len(version) <= 64:
            raise ValueError("Invalid release identity")
        identifier = f"{task}:{version}"
        with self.factory() as db:
            if release.get("runtime_run_id"):
                from app.models.runtime_event import RuntimeEventConsumption
                receipt = db.scalar(select(RuntimeEventConsumption).where(
                    RuntimeEventConsumption.id == release["runtime_run_id"],
                    RuntimeEventConsumption.consumer == "ai2-routes.v1").with_for_update())
                if receipt is None or receipt.result.get("expires_at", 0) <= int(time.time()):
                    raise ValueError("Runtime route evidence expired or was withdrawn")
                stored = next((p for p in receipt.result.get("proposals", [])
                               if p["proposal_id"] == release["proposal_id"]), None)
                if stored is None:
                    raise ValueError("Runtime proposal does not belong to the run")
            base = db.scalar(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.id == f"{task}:{release['base_version']}").with_for_update())
            if base is None or base.graph_hash != release["base_hash"] or not base.approved:
                raise ValueError("Stale or unapproved base")
            TaskGraphRepository._decode(base)
            if release.get("runtime_run_id"):
                if (stored.get("task_id") != task or stored.get("proposal_type") != "transition_priority"
                        or stored.get("evidence", {}).get("base_graph_hash") != base.graph_hash
                        or set(stored.get("change", {})) != {"priorities"}):
                    raise ValueError("Runtime proposal content mismatch")
                priorities = stored["change"]["priorities"]
                recorded = deepcopy(base.body)
                edges = {t["transition_id"]: t for t in recorded["transitions"]}
                if (not isinstance(priorities, dict) or not priorities or priorities.keys() - edges.keys()
                        or any(type(p) is not int or abs(p) > 10000 for p in priorities.values())
                        or len({edges[tid]["from_state"] for tid in priorities}) != 1):
                    raise ValueError("Invalid stored runtime priorities")
                for tid, priority in priorities.items():
                    edges[tid]["priority"] = priority
                if digest(recorded) != graph_hash:
                    raise ValueError("Runtime proposal content mismatch")
            # A priority proposal cannot change tasks, selectors, predicates or safety.
            normalized = deepcopy(body)
            original = {t["transition_id"]: t for t in base.body["transitions"]}
            for edge in normalized["transitions"]:
                if edge["transition_id"] not in original:
                    raise ValueError("Unsupported release change")
                edge["priority"] = original[edge["transition_id"]].get("priority", 0)
            if digest(normalized) != base.graph_hash:
                raise ValueError("Unsupported release change")
            existing = db.get(ApprovedTaskGraph, identifier)
            audit = db.scalar(select(GraphReleaseAudit).where(GraphReleaseAudit.release_id == identifier,
                GraphReleaseAudit.action == "promote"))
            if audit:
                if audit.details.get("release_hash") != digest(release):
                    raise ValueError("GRAPH_RELEASE_IMMUTABLE")
                return {"task_id": task, "version": version, "sha256": graph_hash, "already_published": True}
            if not base.active:
                raise ValueError("Release base is no longer active")
            if existing and (existing.graph_hash != graph_hash or existing.approved):
                raise ValueError("GRAPH_RELEASE_IMMUTABLE")
            if not existing:
                existing = ApprovedTaskGraph(id=identifier, graph_id=task, task_id=task,
                    goal_type=body["definition"]["goal_type"], target_app=body["definition"]["target_app"],
                    graph_version=version, graph_hash=graph_hash, schema_version="runtime-task-graph.v1",
                    body=body, active=False, approved=False, priority=base.priority)
                db.add(existing)
            # Conditional update also fences concurrent promotions on SQLite.
            changed = db.execute(update(ApprovedTaskGraph).where(ApprovedTaskGraph.id == base.id,
                ApprovedTaskGraph.active.is_(True)).values(active=False))
            if changed.rowcount != 1:
                raise ValueError("Release base changed")
            db.execute(update(ApprovedTaskGraph).where(ApprovedTaskGraph.task_id == task).values(active=False))
            existing.active, existing.approved, existing.priority = True, True, base.priority
            db.flush()
            db.add(GraphReleaseAudit(release_id=identifier, previous_hash=base.graph_hash, graph_hash=graph_hash,
                action="promote", reviewer=release["reviewer"], reviewed_at=release["reviewed_at"],
                details={"release_hash": digest(release), "proposal_id": release["proposal_id"],
                    "evaluation_hash": release["evaluation_hash"], "manifest_hash": evaluation["manifest_hash"],
                    "checks": evaluation["checks"], "product_case_count": len(evaluation["product_case_ids"])}))
            db.commit()
        return {"task_id": task, "version": version, "sha256": graph_hash, "already_published": False}

    def rollback(self, task_id, target_version, *, expected_current_hash, reviewer, reviewed_at):
        if not reviewer or type(reviewed_at) is not int or reviewed_at <= 0:
            raise ValueError("Rollback review required")
        with self.factory() as db:
            target = db.scalar(select(ApprovedTaskGraph).where(
                ApprovedTaskGraph.id == f"{task_id}:{target_version}").with_for_update())
            if target is None or not target.approved:
                raise ValueError("Rollback target must be an approved immutable release")
            TaskGraphRepository._decode(target)
            current = db.scalar(select(ApprovedTaskGraph).where(ApprovedTaskGraph.task_id == task_id,
                ApprovedTaskGraph.graph_hash == expected_current_hash, ApprovedTaskGraph.active.is_(True)))
            if current is None:
                raise ValueError("Current release changed")
            if current.id == target.id:
                raise ValueError("Target is already active")
            changed = db.execute(update(ApprovedTaskGraph).where(ApprovedTaskGraph.id == current.id,
                ApprovedTaskGraph.active.is_(True)).values(active=False))
            if changed.rowcount != 1:
                raise ValueError("Current release changed")
            db.execute(update(ApprovedTaskGraph).where(ApprovedTaskGraph.task_id == task_id).values(active=False))
            target.active = True
            db.add(GraphReleaseAudit(release_id=target.id, previous_hash=expected_current_hash,
                graph_hash=target.graph_hash, action="rollback", reviewer=reviewer, reviewed_at=reviewed_at,
                details={"expected_current_hash": expected_current_hash}))
            db.commit()
            return {"task_id": task_id, "version": target_version, "sha256": target.graph_hash}
