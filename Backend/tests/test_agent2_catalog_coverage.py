"""Read-only rollout checks against real catalog goals and persisted releases."""
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest
from sqlalchemy import func, select

from app.main import create_app
from app.models.task_graph import ApprovedTaskGraph
from ai_agent.graph_proposal.contracts import fingerprint
from scripts.agent2 import audit_catalog


@pytest.fixture
def app():
    application = create_app()
    yield application
    application.state.engine.dispose()


def test_catalog_coverage_counts_goals_separately_from_release_versions(app):
    with app.state.session_factory() as db:
        original = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        body = deepcopy(original.body)
        priority = original.priority + 1
    app.state.turn_service.graphs.register(body, version="99.0.0", approved=True, priority=priority)

    report = audit_catalog(app.state.session_factory)
    coverage = report["catalog_coverage"]
    assert report["status"] == "PASS"
    assert report["total"] == 183
    assert coverage["catalog_version"] == "2.5.0"
    assert coverage["total_goals"] == 131
    assert coverage["approved_goals"] == coverage["selectable_goals"] == 111
    assert coverage["catalog_only_goals"] == 13
    assert coverage["public_guide_goals"] == 7
    catalog=json.loads((Path(__file__).parents[1]/'app/task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
    public={g['goal_type'] for g in catalog['goals'] if g['execution_status']=='public_guide'}
    assert public=={'gov24.login','gov24.resident_copy.open','gov24.resident_extract.open',
        'gov24.building_register.open','gov24.land_register.open','gov24.vehicle_register.open','gov24.move_in.open'}
    assert coverage["legacy_graph_goals"] == 71
    assert {k:v for k,v in coverage["by_task_type"].items() if k.startswith("settings_")} == {
        "settings_change": {"approved": 56, "selectable": 56},
        "settings_navigation": {"approved": 21, "selectable": 21},
        "settings_assisted": {"approved": 34, "selectable": 34},
    }
    assert not any(k.startswith('commerce_') for k in coverage['by_task_type'])
    assert coverage["blocked_goals"] == coverage["unexpected_executable_goals"] == []
    goal = next(goal for goal in coverage["goals"] if goal["goal_type"] == "settings.font_size.increase")
    assert goal["graph_reference"]["version"] == "99.0.0"


@pytest.mark.parametrize("problem", ["deleted", "inactive", "unapproved"])
def test_missing_goal_blocks_audit_even_when_remaining_graphs_are_valid(app, problem):
    with app.state.session_factory() as db:
        row = db.scalar(select(ApprovedTaskGraph).where(ApprovedTaskGraph.goal_type == "settings.wifi.enable"))
        if problem == "deleted":
            db.delete(row)
        else:
            setattr(row, "active" if problem == "inactive" else "approved", False)
        db.commit()
        before = db.scalar(select(func.count()).select_from(ApprovedTaskGraph))

    report = audit_catalog(app.state.session_factory)
    assert report["status"] == "BLOCKED"
    assert report["static_valid"] == report["total"]
    coverage = report["catalog_coverage"]
    assert coverage["selectable_goals"] == 110
    assert [(goal["goal_type"], goal["status"]) for goal in coverage["blocked_goals"]] == [
        ("settings.wifi.enable", "MISSING_GRAPH")]
    # Auditing must preserve a missing/disabled release for the operator to inspect.
    with app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(ApprovedTaskGraph)) == before
        if problem != "deleted":
            row = db.get(ApprovedTaskGraph, row.id)
            assert not getattr(row, "active" if problem == "inactive" else "approved")


def test_equal_priority_releases_are_reported_as_ambiguous(app):
    with app.state.session_factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        body, priority = deepcopy(row.body), row.priority
    app.state.turn_service.graphs.register(body, version="99.0.0", approved=True, priority=priority)

    report = audit_catalog(app.state.session_factory)
    assert report["static_valid"] == report["total"] == 183
    assert report["status"] == "BLOCKED"
    assert report["catalog_coverage"]["blocked_goals"][0]["status"] == "AMBIGUOUS_GRAPH"


@pytest.mark.parametrize("problem", ["slots", "task_type"])
def test_structurally_valid_graph_with_different_catalog_contract_is_blocked(app, problem):
    with app.state.session_factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        body = deepcopy(row.body)
        if problem == "slots":
            body["definition"]["required_slots"]["unexpected"] = "slot"
        else:
            body["definition"]["task_type"] = "other_task_type"
        row.body, row.graph_hash = body, fingerprint(body)
        db.commit()

    report = audit_catalog(app.state.session_factory)
    assert report["static_valid"] == report["total"]
    assert report["status"] == "BLOCKED"
    assert report["catalog_coverage"]["blocked_goals"][0]["status"] == "GRAPH_CONTRACT_MISMATCH"


def test_tampered_graph_and_unexpected_executable_goal_are_reported(app):
    with app.state.session_factory() as db:
        db.get(ApprovedTaskGraph, "MVP-01:1.0.1").graph_hash = "0" * 64
        row = db.get(ApprovedTaskGraph, "MVP-02:1.0.1")
        row.goal_type = "coupang.product.search"
        db.commit()

    report = audit_catalog(app.state.session_factory)
    assert report["status"] == "BLOCKED"
    coverage = report["catalog_coverage"]
    assert {goal["status"] for goal in coverage["blocked_goals"]} == {"INVALID_GRAPH", "MISSING_GRAPH"}
    assert coverage["unexpected_executable_goals"] == [
        {"goal_type": "coupang.product.search", "target_app": "android_settings"}]


@pytest.mark.parametrize("missing", [False, True])
def test_audit_cli_exit_status_reflects_catalog_coverage(tmp_path, missing):
    url = "sqlite+pysqlite:///" + (tmp_path / "audit.db").as_posix()
    app = create_app(url)
    if missing:
        with app.state.session_factory() as db:
            db.delete(db.get(ApprovedTaskGraph, "MVP-01:1.0.1"))
            db.commit()
    app.state.engine.dispose()
    result = subprocess.run([sys.executable, "scripts/agent2.py", "audit"],
        cwd=Path(__file__).parents[1], env={**os.environ, "DATABASE_URL": url},
        capture_output=True, text=True, encoding="utf-8", timeout=30)
    assert result.returncode == int(missing), result.stderr
    report = json.loads(result.stdout)
    assert report["status"] == ("BLOCKED" if missing else "PASS")
    assert report["catalog_coverage"]["selectable_goals"] == 111 - int(missing)
