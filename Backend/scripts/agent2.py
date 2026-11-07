"""Offline Agent 2: consent-aware processing, catalog audit, replay, and reviewed releases."""
import argparse
from copy import deepcopy
from datetime import timezone
import json
import os
from pathlib import Path
import sys
import time

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / "AI-Agent" / "src"))

from sqlalchemy import select, delete
from sqlalchemy.exc import IntegrityError
from app import models  # noqa: F401
from app.database import build_engine, build_session_factory
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.task_graph import ApprovedTaskGraph
from app.repositories.users import UserRepository, UserNotFoundError
from app.repositories.task_graphs import TaskGraphRepository
from app.repositories.graph_releases import GraphReleaseRepository
from app.task_graphs.validation import load_graph
from ai_agent.graph_proposal.contracts import EVENT_SCHEMA, EvidenceRequired, fingerprint, validate_manifest
from ai_agent.graph_proposal.graphs import graph_errors
from ai_agent.graph_proposal.schemas import GraphUpdateProposal
from ai_agent.graph_proposal.workflows.pipeline import run_pipeline
from ai_agent.graph_proposal.reconstruction.episodes import RECONSTRUCTION_POLICY
from ai_agent.graph_proposal.evaluation.graph_validator import GraphValidator
from ai_agent.graph_proposal.releases.versioning import GraphVersionManager
from ai_agent.graph_proposal.models.model_trainer import ModelTrainer
from ai_agent.graph_proposal.models.model_registry import ModelRegistry
from ai_agent.runtime.replay import evaluate_cases
from scripts.render_start import database_url

CONSUMER = "ai2-pipeline.v1"
ROUTE_CONSUMER = "ai2-routes.v1"


def replay_evaluator(body, case):
    """Inject online replay through this script; Agent 2 never imports runtime."""
    from app.clients.runtime_agent import RuntimeAgentAdapter, runtime_configuration
    replay = deepcopy(case["replay"])
    graph = load_graph(body)
    # Provenance binds case to base above. Re-run identical observations with
    # candidate body instead of pretending the captured base hash still applies.
    replay["graph_hash"] = fingerprint(body)
    expected = sum(len(t.get("expected", {})) for t in replay.get("turns", [])) + int(bool(replay.get("expect_recovery")))
    configuration = runtime_configuration()
    report = evaluate_cases([replay], {graph.definition.task_id: graph},
        engine_factory=lambda selected: RuntimeAgentAdapter(task_graph=selected).runtime)
    if configuration != runtime_configuration():
        raise ValueError("Runtime configuration changed during replay")
    for index, step in enumerate(replay.get("turns", [])):
        action = step.get("action") or {}
        guide_turn = action.get("guide_turn")
        if not action or not action.get("binding_valid", True):
            continue
        if type(guide_turn) is not int or not 0 <= guide_turn < index:
            raise EvidenceRequired("Replay action lacks a linked captured guide")
        captured = replay["turns"][guide_turn]
        target = action.get("target_node_id") or captured.get("observed_result", {}).get("target_node_id")
        if target is None:
            raise EvidenceRequired("Replay action lacks its captured target; cannot infer a counterfactual outcome")
        decision = report["decisions"][guide_turn]
        if decision["target_node_id"] != target:
            raise EvidenceRequired("Candidate changed the captured action; new device evidence is required")
        expected_action = {"tap_completed": "tap", "drag_completed": "drag", "scroll_completed": "scroll"}.get(action.get("type"))
        if expected_action and decision["action_type"] != expected_action:
            raise EvidenceRequired("Candidate changed the captured action type")
    return {"checks": expected, "failures": report["violations"],
            "runtime_configuration": configuration}


def event_envelope(event, graph_row, *, session_key):
    reference = event.payload.get("graph_reference")
    if reference and (reference.get("task_id") != graph_row.task_id or reference.get("version") != graph_row.graph_version
                      or reference.get("sha256") != event.graph_hash):
        raise ValueError("Recorded graph reference mismatch")
    return {"schema_version": EVENT_SCHEMA, "event_id": event.id,
        "session_key": session_key, "episode_id": event.episode_id,
        "sequence": event.sequence, "created_at": event.created_at, "expires_at": event.expires_at,
        "task_id": graph_row.task_id, "graph_version": graph_row.graph_version, "graph_hash": event.graph_hash,
        "source_kind": "runtime", "device_cohort": event.payload.get("device_cohort", "unknown:android-unknown"),
        "runtime": event.payload.get("runtime") or {},
        "control": {key: event.payload[key] for key in ("command_type", "status", "user_result", "result_response", "adjustment_reason")
                    if key in event.payload}}


def process_sessions(factory, *, limit=100, now=None):
    """Store analysis in expiring DB receipts, never export user data to files.

    Each session is processed under the same user lock as consent withdrawal.
    Reprocessing an unchanged session is idempotent. No approval/learning runs
    without an explicit split/review manifest.
    """
    if type(limit) is not int or not 1 <= limit <= 1000:
        raise ValueError("limit must be between 1 and 1000")
    now = int(time.time()) if now is None else now
    with factory() as db:
        sessions = list(db.execute(select(RuntimeEvent.session_id, RuntimeEvent.user_id).where(
            RuntimeEvent.expires_at > now).distinct().order_by(RuntimeEvent.session_id)))
    summary = {"processed": 0, "unchanged": 0, "ineligible": 0, "errors": 0, "has_more": False}
    for session_id, user_id in sessions:
        if summary["processed"] + summary["errors"] >= limit:
            summary["has_more"] = True
            break
        try:
            with factory() as db:
                profiles = UserRepository(db)
                profiles.lock_user(user_id)
                consent = profiles.latest_consent(user_id, "model_training")
                if not consent or not consent.consented:
                    summary["ineligible"] += 1
                    continue
                granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
                events = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == session_id,
                    RuntimeEvent.expires_at > now, RuntimeEvent.created_at >= granted).order_by(RuntimeEvent.sequence).limit(10001)))
                if len(events) > 10000:
                    raise ValueError("Session exceeds offline event budget")
                if not events:
                    summary["ineligible"] += 1
                    continue
                last = events[-1]
                receipt = db.scalar(select(RuntimeEventConsumption).where(
                    RuntimeEventConsumption.consumer == CONSUMER, RuntimeEventConsumption.event_id == last.id))
                input_hash = fingerprint({"events": [e.id for e in events], "consent_id": consent.id,
                                          "reconstruction_policy": RECONSTRUCTION_POLICY})
                if receipt and receipt.result.get("input_hash") == input_hash:
                    summary["unchanged"] += 1
                    continue
                envelopes, graphs, missing = [], {}, []
                for event in events:
                    ref = event.payload.get("graph_reference") or {}
                    query = select(ApprovedTaskGraph).where(ApprovedTaskGraph.graph_hash == event.graph_hash,
                        ApprovedTaskGraph.approved.is_(True))
                    if ref.get("version"):
                        query = query.where(ApprovedTaskGraph.graph_version == ref["version"])
                    matches = list(db.scalars(query))
                    if len(matches) != 1 or not event.episode_id:
                        missing.append(event.id)
                        continue
                    row = matches[0]
                    TaskGraphRepository._decode(row)
                    graphs[event.graph_hash] = row.body
                    # Session identifier stays pseudonymous and stays in this DB.
                    envelopes.append(event_envelope(event, row, session_key="session-" + fingerprint(session_id)))
                result = run_pipeline(envelopes, graphs, now=now)
                result.update(input_hash=input_hash, missing_graph_events=missing,
                              expires_at=min(e.expires_at for e in events))
                if receipt:
                    receipt.result, receipt.consumed_at = result, now
                else:
                    db.add(RuntimeEventConsumption(event_id=last.id, consumer=CONSUMER, result=result, consumed_at=now))
                db.execute(delete(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer == CONSUMER,
                    RuntimeEventConsumption.event_id != last.id, RuntimeEventConsumption.event_id.in_(
                        select(RuntimeEvent.id).where(RuntimeEvent.session_id == session_id))))
                db.commit()
                summary["processed"] += 1
        except (IntegrityError, UserNotFoundError):
            summary["unchanged"] += 1
        except (ValueError, TypeError, KeyError):
            # Do not print event contents or turn a corrupt record into success.
            summary["errors"] += 1
    return summary


def propose_routes(factory, *, manifest, cases=None, now=None, limit=100, evaluator=None):
    """Compare consented sessions across users, retaining evidence only in expiring DB receipts.

    Explicit reviewed splits are mandatory. No personal profile or user identifier
    enters the pipeline. Nothing is promoted by this operation.
    """
    manifest = validate_manifest(manifest)
    if type(limit) is not int or not 1 <= limit <= 1000:
        raise ValueError("limit must be between 1 and 1000")
    now = int(time.time()) if now is None else now
    with factory() as db:
        sessions = list(db.execute(select(RuntimeEvent.session_id, RuntimeEvent.user_id).where(
            RuntimeEvent.expires_at > now).distinct().order_by(RuntimeEvent.user_id, RuntimeEvent.session_id).limit(limit + 1)))
        if len(sessions) > limit:
            raise ValueError("Route run exceeds session budget; narrow retained input first")
        events, envelopes, graphs = [], [], {}
        users = UserRepository(db)
        for session_id, user_id in sessions:
            # Same lock and transaction as withdrawal/deletion; eligibility is
            # rechecked on every run, including when old analysis receipts exist.
            users.lock_user(user_id)
            consent = users.latest_consent(user_id, "model_training")
            if not consent or not consent.consented:
                continue
            granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
            batch = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == session_id,
                RuntimeEvent.expires_at > now, RuntimeEvent.created_at >= granted).order_by(RuntimeEvent.sequence).limit(10001)))
            if len(events) + len(batch) > 10000:
                raise ValueError("Route run exceeds event budget")
            for event in batch:
                ref = event.payload.get("graph_reference") or {}
                query = select(ApprovedTaskGraph).where(ApprovedTaskGraph.graph_hash == event.graph_hash,
                    ApprovedTaskGraph.approved.is_(True))
                if ref.get("version"):
                    query = query.where(ApprovedTaskGraph.graph_version == ref["version"])
                matches = list(db.scalars(query))
                if len(matches) != 1 or not event.episode_id:
                    raise ValueError("Missing immutable graph/episode evidence")
                row = matches[0]
                TaskGraphRepository._decode(row)
                graphs[event.graph_hash] = row.body
                envelopes.append(event_envelope(event, row, session_key="session-" + fingerprint(session_id)))
                events.append(event)
        if not events:
            return {"status": "NEEDS_DATA", "run_id": None, "episodes": 0, "proposals": []}
        # Catch omitted, expired, withdrawn or pre-consent reviewed evidence.
        eligible_episodes = {e["episode_id"] for e in envelopes}
        if any(r["source_kind"] == "runtime" and r["episode_id"] not in eligible_episodes for r in manifest["records"]):
            raise ValueError("Manifest refers to ineligible or expired runtime evidence")
        result = run_pipeline(envelopes, graphs, now=now, manifest=manifest, cases=cases,
            evaluator=evaluator or replay_evaluator, graph_loader=load_graph)
        result["expires_at"] = min(e.expires_at for e in events)
        anchor = max(events, key=lambda e: (e.created_at, e.id))
        receipt = db.scalar(select(RuntimeEventConsumption).where(
            RuntimeEventConsumption.consumer == ROUTE_CONSUMER, RuntimeEventConsumption.event_id == anchor.id))
        if receipt is None:
            receipt = RuntimeEventConsumption(event_id=anchor.id, consumer=ROUTE_CONSUMER,
                                              result=result, consumed_at=now)
            db.add(receipt)
        else:
            receipt.result, receipt.consumed_at = result, now
        db.flush()
        summary = {"status": result["status"], "run_id": receipt.id,
            "episodes": len(result["episodes"]), "expires_at": result["expires_at"],
            "proposals": [{"proposal_id": p["proposal_id"], "task_id": p["task_id"]} for p in result["proposals"]]}
        db.commit()
        return summary


def stored_proposal(factory, run_id, proposal_id, *, now=None):
    now = int(time.time()) if now is None else now
    with factory() as db:
        receipt = db.get(RuntimeEventConsumption, run_id)
        if receipt is None or receipt.consumer != ROUTE_CONSUMER or receipt.result.get("expires_at", 0) <= now:
            raise ValueError("Runtime proposal evidence expired or was withdrawn; run again")
        proposals = [p for p in receipt.result.get("proposals", []) if p["proposal_id"] == proposal_id]
        if len(proposals) != 1:
            raise ValueError("Unknown runtime route proposal")
        return GraphUpdateProposal(**proposals[0])


def audit_catalog(factory):
    reports = []
    unsupported_tasks = {}
    with factory() as db:
        for row in db.scalars(select(ApprovedTaskGraph).order_by(ApprovedTaskGraph.id)):
            try:
                errors = graph_errors(row.body)
            except (TypeError, ValueError, KeyError):
                errors = ["invalid_graph_body"]
            structural_errors = [error for error in errors if error != "unsupported_verification_predicate"]
            predicates = []
            try:
                graph = load_graph(row.body, executable=False)
                if (row.schema_version != "runtime-task-graph.v1" or fingerprint(row.body) != row.graph_hash
                        or (graph.definition.task_id, graph.definition.goal_type, graph.definition.target_app)
                        != (row.task_id, row.goal_type, row.target_app)):
                    structural_errors.append("graph_reference_mismatch")
                from app.task_graphs.validation import PREDICATES
                predicates = sorted({t.verification_predicate for t in graph.transitions
                                     if t.verification_predicate not in PREDICATES})
            except (ValueError, TypeError, KeyError):
                structural_errors.append("graph_schema_invalid")
            try:
                TaskGraphRepository._decode(row)
            except (ValueError, TypeError, KeyError):
                errors.append("runtime_decode_failed")
            errors.extend(structural_errors)
            for predicate in predicates:
                unsupported_tasks.setdefault(predicate, set()).add(row.task_id)
            blockers = []
            if structural_errors:
                blockers.append({"owner": "backend", "reason": "graph_structure_or_reference"})
            if predicates:
                blockers.append({"owner": "ai_agent1", "reason": "unsupported_completion_predicate"})
            if not row.approved:
                blockers.append({"owner": "fe_and_ai_agent1", "reason": "device_evidence_and_release_review"})
            reports.append({"task_id": row.task_id, "version": row.graph_version,
                "approved": row.approved, "active": row.active,
                "static_valid": not errors, "errors": sorted(set(errors)),
                "structural_valid": not structural_errors, "structural_errors": sorted(set(structural_errors)),
                "unsupported_predicates": predicates, "blockers": blockers,
                "execution_enabled": row.approved and row.active and not errors,
                "device_validation": "NOT_ASSESSED"})
    return {"total": len(reports), "static_valid": sum(r["static_valid"] for r in reports),
        "structural_valid": sum(r["structural_valid"] for r in reports),
        "execution_enabled": sum(r["execution_enabled"] for r in reports),
        "unsupported_predicates": {key: {"task_ids": sorted(ids), "task_count": len(ids), "owner": "ai_agent1"}
                                   for key, ids in sorted(unsupported_tasks.items())}, "graphs": reports}


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    process = commands.add_parser("process", help="Process consented DB sessions; no user-data file export")
    process.add_argument("--limit", type=int, default=100)
    propose = commands.add_parser("propose-routes", help="Compare consented DB sessions with explicit reviewed splits; no auto release")
    propose.add_argument("--manifest", required=True)
    propose.add_argument("--cases", help="Independent device replay cases; omitted cases yield NEEDS_DATA")
    propose.add_argument("--limit", type=int, default=100)
    commands.add_parser("audit", help="Validate stored graph structures; no device-quality claims")
    fixture = commands.add_parser("fixture", help="Run curated/synthetic JSONL through the full offline pipeline")
    fixture.add_argument("--events", required=True)
    fixture.add_argument("--graphs", required=True, help="JSON list of graph bodies")
    fixture.add_argument("--manifest", required=True)
    fixture.add_argument("--cases", help="JSON list of independent replay cases")
    fixture.add_argument("--now", type=int, default=None)
    fixture.add_argument("--output", required=True)
    train = commands.add_parser("train-baseline", help="Fit an offline CPU frequency baseline from curated training split only")
    train.add_argument("--run", required=True, help="Output from the fixture command")
    train.add_argument("--registry", required=True, help="Local artifact directory; never a production model activation")
    train.add_argument("--allow-weak", action="store_true", help="Acknowledge that step outcomes are weak labels")
    approve = commands.add_parser("publish", help="Re-evaluate curated holdout then publish an explicitly reviewed candidate")
    source = approve.add_mutually_exclusive_group(required=True)
    source.add_argument("--proposal", help="Curated proposal JSON")
    source.add_argument("--run-id", help="Unexpired DB runtime proposal receipt")
    approve.add_argument("--proposal-id", help="Required with --run-id")
    for arg in ("base", "manifest", "cases", "version", "reviewer"):
        approve.add_argument("--" + arg, required=True)
    rollback = commands.add_parser("rollback")
    for arg in ("task", "version", "expected-current-hash", "reviewer"):
        rollback.add_argument("--" + arg, required=True)
    args = parser.parse_args()
    if args.command == "train-baseline":
        run = read_json(args.run)
        trained = ModelTrainer().train(run.get("dataset"), allow_weak=args.allow_weak)
        if trained["status"] == "CANDIDATE":
            artifact = trained["artifact"]
            ModelRegistry(args.registry).register(artifact["model_id"], artifact)
            print(json.dumps({"status": "CANDIDATE", "model_id": artifact["model_id"], "training_samples": artifact["training_samples"]}))
            return 0
        print(json.dumps(trained))
        return 1
    if args.command == "fixture":
        events = [json.loads(line) for line in Path(args.events).read_text(encoding="utf-8").splitlines() if line.strip()]
        graphs = {fingerprint(body): body for body in read_json(args.graphs)}
        result = run_pipeline(events, graphs, now=args.now if args.now is not None else int(time.time()),
            manifest=read_json(args.manifest), cases=read_json(args.cases) if args.cases else None,
            evaluator=replay_evaluator, graph_loader=load_graph)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as handle:
            json.dump(result, handle, ensure_ascii=False, indent=2)
        print(json.dumps({"status": result["status"], "episodes": len(result["episodes"]), "proposals": len(result["proposals"])}))
        return int(result["status"] in {"ERROR", "REJECTED"})
    raw = os.environ.get("DATABASE_URL")
    if not raw:
        parser.error("DATABASE_URL is required; no database is created implicitly")
    engine = build_engine(database_url(raw) if raw.startswith(("postgres://", "postgresql://")) else raw)
    factory = build_session_factory(engine)
    try:
        if args.command == "process":
            result = process_sessions(factory, limit=args.limit)
        elif args.command == "propose-routes":
            result = propose_routes(factory, manifest=read_json(args.manifest),
                cases=read_json(args.cases) if args.cases else None, limit=args.limit)
        elif args.command == "audit":
            result = audit_catalog(factory)
        elif args.command == "publish":
            if args.run_id and not args.proposal_id:
                parser.error("--proposal-id is required with --run-id")
            proposal = stored_proposal(factory, args.run_id, args.proposal_id) if args.run_id else GraphUpdateProposal(**read_json(args.proposal))
            base = read_json(args.base)
            evaluation = GraphValidator().validate(proposal, base_graph=base, manifest=read_json(args.manifest),
                cases=read_json(args.cases), evaluator=replay_evaluator, graph_loader=load_graph)
            manager = GraphVersionManager(GraphReleaseRepository(factory))
            release = manager.prepare(proposal, base, candidate_version=args.version, evaluation=evaluation,
                                      reviewer=args.reviewer, reviewed_at=int(time.time()))
            if args.run_id:
                release["runtime_run_id"] = args.run_id
            result = manager.promote(release)
        else:
            result = GraphVersionManager(GraphReleaseRepository(factory)).rollback(args.task, args.version,
                expected_current_hash=args.expected_current_hash, reviewer=args.reviewer, reviewed_at=int(time.time()))
        print(json.dumps(result, ensure_ascii=False))
        if args.command == "audit":
            return int(any(not row["static_valid"] for row in result["graphs"] if row["approved"]))
        return int(bool(result.get("errors"))) if args.command == "process" else 0
    finally:
        engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
