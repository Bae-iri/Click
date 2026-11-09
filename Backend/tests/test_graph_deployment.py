"""Deploy against existing releases; fresh-DB seeding cannot expose version drift."""
from copy import deepcopy
from dataclasses import replace

import pytest
from sqlalchemy import select

from app.main import create_app
from app.models.task_graph import ApprovedTaskGraph
from app.repositories import task_graphs
from app.repositories.task_graphs import GraphReleaseConflictError, canonical_graph
from app.task_graphs.validation import load_graph


@pytest.fixture
def repository():
    app = create_app()
    yield app.state.turn_service.graphs
    app.state.engine.dispose()


def font_release(repository):
    with repository.factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        return deepcopy(row.body)


def bundled(body, version="1.0.1", *, approved=True, priority=101):
    return ({"version": version, "approved": approved, "priority": priority},
        load_graph(body, executable=approved))


def goal():
    from app.schemas.agent_turn import TypedGoalRequest
    return TypedGoalRequest(catalog_version="2.3.0", goal_type="settings.font_size.increase",
        target_app="android_settings", slots={"setting": "font_size", "direction": "increase"})


def test_validation_preserves_exact_bundled_release_hashes(repository):
    expected = {graph.definition.task_id: canonical_graph(graph) for _, graph in task_graphs.releases()}
    repository.seed_mvp()
    with repository.factory() as db:
        for row in db.scalars(select(ApprovedTaskGraph)):
            body, digest = expected[row.task_id]
            assert row.body == body
            assert row.graph_hash == digest


def test_same_version_changed_content_blocks_whole_deployment(repository, monkeypatch):
    old = font_release(repository)
    changed = deepcopy(old)
    changed["transitions"][0]["guide_message"] = "Changed route instructions"
    added = deepcopy(old)
    added["definition"]["task_id"] = "NEW-RELEASE"
    monkeypatch.setattr(task_graphs, "releases", lambda: [bundled(added), bundled(changed)])
    with pytest.raises(GraphReleaseConflictError, match="GRAPH_RELEASE_IMMUTABLE: MVP-01:1.0.1") as error:
        repository.seed_mvp()
    assert "Bump the graph version" in str(error.value)
    with repository.factory() as db:
        assert db.get(ApprovedTaskGraph, "NEW-RELEASE:1.0.1") is None
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        assert row.body == old and row.active and row.approved


@pytest.mark.parametrize("priority", [1, 101])
def test_new_version_replaces_active_release_and_preserves_pinned_sessions(repository, monkeypatch, priority):
    body = font_release(repository)
    old_hash = canonical_graph(load_graph(body))[1]
    body["transitions"][0]["guide_message"] = "Reviewed new version"
    monkeypatch.setattr(task_graphs, "releases", lambda: [bundled(body, "1.0.2", priority=priority)])
    repository.seed_mvp()
    repository.seed_mvp()
    assert repository.select(goal())[1]["version"] == "1.0.2"
    _, reference = repository.select_pinned({"task_id": "MVP-01", "graph_version": "1.0.1",
        "graph_hash": old_hash, "goal_type": goal().goal_type, "goal": {"target_app": "android_settings"}})
    assert reference["sha256"] == old_hash
    with repository.factory() as db:
        rows = list(db.scalars(select(ApprovedTaskGraph).where(ApprovedTaskGraph.task_id == "MVP-01")))
        assert len(rows) == 2
        assert {row.graph_version for row in rows if row.active} == {"1.0.2"}
        assert all(row.approved for row in rows)
        # A later operator rollback must survive a restart with the same bundle.
        for row in rows:
            row.active = row.graph_version == "1.0.1"
        db.commit()
    repository.seed_mvp()
    assert repository.select(goal())[1]["version"] == "1.0.1"


def test_invalid_later_artifact_leaves_all_previous_releases_untouched(repository, monkeypatch):
    body = font_release(repository)
    first = bundled(body, "1.0.2")
    invalid = replace(first[1], transitions=(replace(first[1].transitions[0], to_state="missing"),))
    monkeypatch.setattr(task_graphs, "releases", lambda: [first, (first[0], invalid)])
    with pytest.raises(ValueError, match="Dangling transition"):
        repository.seed_mvp()
    with repository.factory() as db:
        assert db.get(ApprovedTaskGraph, "MVP-01:1.0.2") is None
        assert db.get(ApprovedTaskGraph, "MVP-01:1.0.1").active


def test_unreviewed_new_version_stays_inactive(repository, monkeypatch):
    body = font_release(repository)
    body["transitions"][0]["verification_predicate"] = "future_device_verification"
    monkeypatch.setattr(task_graphs, "releases", lambda: [bundled(body, "2.0.0", approved=False)])
    repository.seed_mvp()
    repository.seed_mvp()
    with repository.factory() as db:
        candidate = db.get(ApprovedTaskGraph, "MVP-01:2.0.0")
        assert not candidate.active and not candidate.approved
        assert db.get(ApprovedTaskGraph, "MVP-01:1.0.1").active


@pytest.mark.parametrize("field", ["body", "graph_hash", "schema_version", "goal_type", "graph_version"])
@pytest.mark.parametrize("operation", ["seed", "register"])
def test_existing_release_integrity_is_checked_before_reuse(repository, monkeypatch, field, operation):
    body = font_release(repository)
    with repository.factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        if field == "body":
            altered = deepcopy(row.body)
            altered["transitions"][0]["guide_message"] = "Stale DB body with unchanged hash"
            row.body = altered
        else:
            setattr(row, field, "invalid")
        db.commit()
    monkeypatch.setattr(task_graphs, "releases", lambda: [bundled(body)])
    with pytest.raises(GraphReleaseConflictError):
        if operation == "seed":
            repository.seed_mvp()
        else:
            repository.register(body, version="1.0.1", approved=True, priority=101)


def test_duplicate_task_versions_in_bundle_are_rejected(repository, monkeypatch):
    body = font_release(repository)
    monkeypatch.setattr(task_graphs, "releases", lambda: [bundled(body, "2.0.0"), bundled(body, "3.0.0")])
    with pytest.raises(ValueError, match="Duplicate bundled graph task"):
        repository.seed_mvp()
    assert repository.select(goal())[1]["version"] == "1.0.1"


def test_application_restart_rejects_existing_db_with_different_same_version_content(tmp_path):
    url = f"sqlite+pysqlite:///{(tmp_path / 'deployment.db').as_posix()}"
    app = create_app(url)
    factory = app.state.session_factory
    with factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        old = deepcopy(row.body)
        old["transitions"][0]["guide_message"] = "Instructions from an earlier deployment"
        row.body, row.graph_hash = canonical_graph(load_graph(old))
        db.commit()
    app.state.engine.dispose()
    with pytest.raises(GraphReleaseConflictError, match="MVP-01:1.0.1"):
        create_app(url)
    with factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        assert row.body == old and row.active
    app.state.engine.dispose()


@pytest.mark.parametrize("already_published", [False, True])
def test_reviewed_publication_rechecks_stored_body_before_activation_or_retry(already_published):
    from tests.test_agent2_operations import release_fixture

    app = create_app()
    repository = app.state.turn_service.graphs
    manager, release, _ = release_fixture(app)
    if already_published:
        manager.promote(release)
    else:
        repository.register(release["body"], version=release["version"])
    with repository.factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:2.0.0")
        altered = deepcopy(row.body)
        altered["transitions"][0]["guide_message"] = "Stale content with the expected hash"
        row.body = altered
        db.commit()
    with pytest.raises(GraphReleaseConflictError):
        manager.promote(release)
    if not already_published:
        assert repository.select(goal())[1]["version"] == "1.0.1"
    app.state.engine.dispose()
