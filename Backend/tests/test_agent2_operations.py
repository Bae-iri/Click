"""Offline integration on disposable SQLite; fixtures do not imply device validation."""
from copy import deepcopy
import json
import time
import pytest
from sqlalchemy import select, func

from app.main import create_app
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.task_graph import ApprovedTaskGraph
from app.models.graph_release import GraphReleaseAudit
from app.repositories.graph_releases import GraphReleaseRepository
from app.repositories.task_graphs import TASK_GRAPHS, canonical_graph
from app.schemas.agent_turn import TypedGoalRequest
from app.task_graphs.validation import load_graph
from scripts.agent2 import process_sessions, audit_catalog, CONSUMER, replay_evaluator
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload
from tests.test_runtime_recovery_events import follow
from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.schemas import GraphUpdateProposal
from ai_agent.graph_proposal.graphs import apply_proposal
from ai_agent.graph_proposal.releases.versioning import GraphVersionManager


def started_with_consent():
    app = create_app()
    client = authenticated_client(app)
    user = client.get("/api/v1/users/me").json()["id"]
    response = client.post(f"/api/v1/users/{user}/consents", json={
        "purpose": "model_training", "consented": True, "policy_version": "1"})
    assert response.status_code == 201, response.text
    app.state.turn_service.repository.clock = lambda: int(time.time()) + 2
    response = client.post("/api/v1/agent/commands", json=start_payload())
    assert response.status_code == 200, response.text
    return app, client, user, response.json()


def test_pipeline_uses_persisted_graph_references_and_is_idempotent():
    app, client, user, body = started_with_consent()
    assert process_sessions(app.state.session_factory)["processed"] == 1
    assert process_sessions(app.state.session_factory)["unchanged"] == 1
    with app.state.session_factory() as db:
        result = db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer == CONSUMER)).result
        assert result["episodes"][0]["task_id"] == "MVP-01"
        assert not result["missing_graph_events"]
        assert result["status"] == "NEEDS_DATA"
        serialized = json.dumps(result, ensure_ascii=False)
        assert "디스플레이" not in serialized and user not in serialized and "raw_goal" not in serialized
    assert client.post("/api/v1/agent/commands", json=follow(body)).status_code == 200
    assert process_sessions(app.state.session_factory)["processed"] == 1
    client.post(f"/api/v1/users/{user}/consents", json={"purpose": "model_training", "consented": False, "policy_version": "1"})
    assert process_sessions(app.state.session_factory)["ineligible"] == 1
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEventConsumption)) == 0


def test_preconsent_expired_and_corrupt_events_cannot_enter_analysis():
    app = create_app()
    client = authenticated_client(app)
    response = client.post("/api/v1/agent/commands", json=start_payload())
    assert response.status_code == 200
    assert process_sessions(app.state.session_factory)["ineligible"] == 1
    app, client, user, body = started_with_consent()
    with app.state.session_factory() as db:
        event = db.scalar(select(RuntimeEvent))
        event.created_at = 1
        db.commit()
    assert process_sessions(app.state.session_factory)["ineligible"] == 1
    with app.state.session_factory() as db:
        event = db.scalar(select(RuntimeEvent))
        event.created_at = int(time.time()) + 2
        event.payload = {**event.payload, "runtime": {**event.payload["runtime"], "raw_goal": "private"}}
        db.commit()
    assert process_sessions(app.state.session_factory)["errors"] == 1
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEventConsumption)) == 0


def test_summary_expires_with_earliest_evidence_and_does_not_accumulate_snapshots():
    from app.repositories.runtime_events import cleanup
    app, client, user, body = started_with_consent()
    assert process_sessions(app.state.session_factory)["processed"] == 1
    assert client.post("/api/v1/agent/commands", json=follow(body)).status_code == 200
    with app.state.session_factory() as db:
        events = list(db.scalars(select(RuntimeEvent).order_by(RuntimeEvent.sequence)))
        events[0].expires_at = int(time.time()) + 100
        db.commit()
        first_expiry = events[0].expires_at
    assert process_sessions(app.state.session_factory)["processed"] == 1
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEventConsumption)) == 1
    assert cleanup(app.state.session_factory, now=first_expiry) == 1
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEventConsumption)) == 0
        assert db.scalar(select(func.count()).select_from(RuntimeEvent)) == 1


def release_fixture(app):
    base_graph = TASK_GRAPHS["settings.font_size.increase"]
    base, base_hash = canonical_graph(base_graph)
    edges = [e for e in base["transitions"] if e["from_state"] == "S1_SETTINGS_HOME"]
    assert len(edges) >= 1
    priorities = {e["transition_id"]: i + 20 for i, e in enumerate(reversed(edges))}
    proposal = GraphUpdateProposal("test-proposal", "MVP-01", "1.0.1", "transition_priority",
        {"priorities": priorities}, {"base_graph_hash": base_hash}, 0.5, True)
    candidate = apply_proposal(base, proposal)
    # Mock a reviewed evaluator result; actual product releases re-run replay in CLI.
    evaluation = {"schema_version": "ai2-evaluation.v1", "decision": "PASS", "proposal_id": proposal.proposal_id,
        "base_graph_hash": base_hash, "candidate_hash": fingerprint(candidate),
        "product_case_ids": ["simulated-review-case"], "checks": 1, "candidate_failures": 0,
        "missing": [], "errors": [], "manifest_hash": fingerprint("simulated-manifest"),
        "regression": {"has_regression": False, "failed_cases": []}}
    manager = GraphVersionManager(GraphReleaseRepository(app.state.session_factory))
    release = manager.prepare(proposal, base, candidate_version="2.0.0", evaluation=evaluation,
                              reviewer="test-reviewer", reviewed_at=200)
    return manager, release, base_hash


def test_publish_and_rollback_are_atomic_audited_and_preserve_pinned_graphs():
    app = create_app()
    manager, release, base_hash = release_fixture(app)
    repo = app.state.turn_service.graphs
    request = start_payload()
    goal = TypedGoalRequest(**request["goal"])
    before = repo.select(goal)[1]
    published = manager.promote(release)
    assert not published["already_published"]
    assert repo.select(goal)[1]["version"] == "2.0.0"
    assert manager.promote(release)["already_published"]
    details = {"task_id": "MVP-01", "graph_version": "1.0.1", "graph_hash": base_hash,
        "goal_type": goal.goal_type, "goal": {"target_app": goal.target_app}}
    assert repo.select_pinned(details)[1]["sha256"] == before["sha256"]
    result = manager.rollback("MVP-01", "1.0.1", expected_current_hash=release["graph_hash"], reviewer="operator", reviewed_at=300)
    assert result["sha256"] == base_hash
    assert repo.select(goal)[1]["version"] == "1.0.1"
    with app.state.session_factory() as db:
        assert [r.action for r in db.scalars(select(GraphReleaseAudit).order_by(GraphReleaseAudit.reviewed_at))] == ["promote", "rollback"]
    with pytest.raises(ValueError, match="Current release changed"):
        manager.rollback("MVP-01", "1.0.1", expected_current_hash=release["graph_hash"], reviewer="operator", reviewed_at=301)


def test_release_rejects_forged_content_and_stale_base_without_side_effects():
    app = create_app()
    manager, release, _ = release_fixture(app)
    altered = deepcopy(release)
    altered["body"]["transitions"][0]["target_keywords"] = ["unsafe-menu"]
    altered["graph_hash"] = fingerprint(altered["body"])
    altered["evaluation"]["candidate_hash"] = altered["graph_hash"]
    altered["evaluation_hash"] = fingerprint(altered["evaluation"])
    with pytest.raises(ValueError, match="Unsupported release change"):
        manager.promote(altered)
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(GraphReleaseAudit)) == 0
    manager.promote(release)
    stale = deepcopy(release)
    stale["version"] = "3.0.0"
    with pytest.raises(ValueError, match="no longer active"):
        manager.promote(stale)


def test_catalog_audit_reports_each_registered_release_without_claiming_device_pass():
    app = create_app()
    report = audit_catalog(app.state.session_factory)
    assert report["total"] > 0
    assert all(row["device_validation"] == "NOT_ASSESSED" for row in report["graphs"])
    assert all(row["static_valid"] for row in report["graphs"] if row["approved"])
    assert report["structural_valid"] == report["total"] == 89
    assert report["execution_enabled"] == 2
    assert report["unsupported_predicates"]["target_checked_on"]["task_count"] == 26
    assert all(not row["execution_enabled"] for row in report["graphs"] if not row["approved"])
    draft = next(row for row in report["graphs"] if row["unsupported_predicates"])
    assert draft["structural_valid"] and not draft["static_valid"]
    assert any(b["owner"] == "ai_agent1" for b in draft["blockers"])


def test_catalog_audit_distinguishes_tampered_reference_from_missing_runtime_support():
    app = create_app()
    with app.state.session_factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        row.graph_hash = "0" * 64
        db.commit()
    report = audit_catalog(app.state.session_factory)
    row = next(item for item in report["graphs"] if item["task_id"] == "MVP-01")
    assert not row["structural_valid"] and not row["execution_enabled"]
    assert "graph_reference_mismatch" in row["structural_errors"]
    assert row["unsupported_predicates"] == []
    assert row["blockers"] == [{"owner": "backend", "reason": "graph_structure_or_reference"}]


def test_real_runtime_replay_adapter_scores_expectations_on_seed():
    body, graph_hash = canonical_graph(TASK_GRAPHS["settings.font_size.increase"])
    fixture_path = __import__("pathlib").Path(__file__).resolve().parents[2] / "AI-Agent/data/evaluation/runtime_recovery/navigation.json"
    cases = json.loads(fixture_path.read_text(encoding="utf-8"))
    case = next(case for case in cases if case["task_id"] == "MVP-01")
    original = deepcopy(case)
    result = replay_evaluator(body, {"replay": case})
    assert result["checks"] > 0
    assert result["failures"] == []
    assert case == original
    case = next(case for case in cases if any(step.get("action") for step in case["turns"]))
    action_step = next(step for step in case["turns"] if step.get("action"))
    action_step["action"]["target_node_id"] = "unobserved-target"
    from ai_agent.graph_proposal.contracts import EvidenceRequired
    with pytest.raises(EvidenceRequired):
        replay_evaluator(body, {"replay": case})
