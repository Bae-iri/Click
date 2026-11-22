"""Reproduce the production database that rejected the changed Bluetooth 1.0.0."""
import json
from hashlib import sha256
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient
from sqlalchemy import select

from app.main import create_app
from app.models.task_graph import ApprovedTaskGraph
from app.repositories import task_graphs
from app.repositories.task_graphs import canonical_graph
from app.task_graphs import releases
from app.task_graphs.validation import load_graph

BASELINE = json.loads((Path(__file__).parent / "fixtures/graph_releases/8351449.json").read_text())
TASK = "SET-bluetooth-connect"
OLD_HASH = "9e39ed1c21f8e8be0b4d0d884238c47c9933918fdd30bc87a24e8153ab1d3697"
NEW_HASH = "7df3d391e949d4871dd2a46421da00411c58ea1122f228eda3afff55c4b9f75c"


def test_every_existing_bundle_release_preserves_its_identity_or_bumps_version():
    for release, graph in releases():
        previous = BASELINE["releases"].get(graph.definition.task_id)
        if previous is None:
            continue
        digest = canonical_graph(graph)[1]
        if release["version"] == previous["version"]:
            assert digest == previous["sha256"], graph.definition.task_id
            assert release["approved"] == previous["approved"]
            assert release["priority"] == previous["priority"]
        else:
            assert tuple(map(int, release["version"].split("."))) > tuple(map(int, previous["version"].split(".")))


def test_startup_upgrades_actual_legacy_bluetooth_graph_and_preserves_pinned_release(tmp_path, monkeypatch):
    historical = BASELINE["bluetooth_connect"]
    old_graph = load_graph(historical["body"])
    assert sha256(json.dumps(historical["body"], sort_keys=True, ensure_ascii=False,
        separators=(",", ":")).encode()).hexdigest() == OLD_HASH
    url = "sqlite+pysqlite:///" + (tmp_path / "previous-production.db").as_posix()
    with monkeypatch.context() as patch:
        patch.setattr(task_graphs, "releases", lambda: [])
        previous_app = create_app(url)
        with previous_app.state.session_factory() as db:
            db.add(ApprovedTaskGraph(id=TASK + ":1.0.0", graph_id=TASK, task_id=TASK,
                goal_type="settings.bluetooth.connect", target_app="android_settings",
                graph_version="1.0.0", graph_hash=OLD_HASH, schema_version="runtime-task-graph.v1",
                body=historical["body"], active=True, approved=True, priority=historical["release"]["priority"]))
            db.commit()
        previous_app.state.engine.dispose()

    # Exercise real create_app/seed_mvp, including the second restart against the same DB.
    for _ in range(2):
        app = create_app(url)
        try:
            repository = app.state.turn_service.graphs
            current, reference = repository.select(SimpleNamespace(
                goal_type="settings.bluetooth.connect", target_app="android_settings"))
            assert reference["version"] == "1.0.2"
            assert reference["sha256"] == NEW_HASH
            pinned, old_reference = repository.select_pinned({
                "task_id": TASK, "graph_version": "1.0.0", "graph_hash": OLD_HASH,
                "goal_type": "settings.bluetooth.connect", "goal": {"target_app": "android_settings"}})
            assert old_reference["sha256"] == OLD_HASH
            assert pinned.to_dict() == old_graph.to_dict()
            with repository.factory() as db:
                rows = list(db.scalars(select(ApprovedTaskGraph).where(ApprovedTaskGraph.task_id == TASK)))
                assert len(rows) == 2
                assert {row.graph_version for row in rows if row.active} == {"1.0.2"}
                old = db.get(ApprovedTaskGraph, TASK + ":1.0.0")
                assert old.body == historical["body"] and old.graph_hash == OLD_HASH and old.approved
            with TestClient(app) as client:
                assert client.get("/ready").json() == {"status": "ready", "database": "ok"}
        finally:
            app.state.engine.dispose()
