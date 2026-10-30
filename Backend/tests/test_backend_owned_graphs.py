"""Exercise DB-only graphs through real typed runtime turns and version pinning."""
import json
from copy import deepcopy

import pytest
from sqlalchemy import select

from app.main import create_app
from app.models.task_graph import ApprovedTaskGraph
from app.repositories.task_graphs import NoCompatibleGraphError, canonical_graph
from app.task_graphs import approved_graphs, releases
from app.task_graphs.validation import load_graph
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


def font_body():
    return json.loads(json.dumps(approved_graphs()["MVP-01"].to_dict()))


def test_every_settings_goal_has_backend_graph_and_unreviewed_graphs_are_inactive():
    from pathlib import Path
    catalog = json.loads((Path(__file__).parents[2] / "AI-Agent/goal_catalog/mvp_goals.json").read_text(encoding="utf-8"))
    expected = {g["goal_type"] for g in catalog["goals"] if g["target_app"] == "android_settings"}
    definitions = list(releases())
    assert {g.definition.goal_type for _, g in definitions} == expected
    assert len(definitions) == len(expected) == 89
    app = create_app()
    repository = app.state.turn_service.graphs
    repository.seed_mvp()
    with repository.factory() as db:
        rows = list(db.scalars(select(ApprovedTaskGraph)))
        assert len(rows) == 89
        assert sum(row.approved for row in rows) == 6
        assert all(not row.active for row in rows if not row.approved)
    for release, graph in definitions:
        if release["approved"]:
            continue
        goal = type("Goal", (), {"goal_type": graph.definition.goal_type,
            "target_app": "android_settings"})()
        with pytest.raises(NoCompatibleGraphError):
            repository.select(goal)
        from app.task_graphs.validation import PREDICATES
        if any(t.verification_predicate not in PREDICATES for t in graph.transitions):
            with pytest.raises(ValueError):
                load_graph(json.loads(json.dumps(graph.to_dict())), executable=True)


def test_json_round_trip_preserves_existing_font_graphs():
    from ai_agent.runtime.tasks import TASK_GRAPHS
    for name in ("MVP-01", "MVP-02"):
        original = TASK_GRAPHS[name]
        decoded = load_graph(json.loads(json.dumps(approved_graphs()[name].to_dict())))
        assert canonical_graph(decoded) == canonical_graph(original)


def test_db_only_graph_execution_follow_up_replay_and_pinned_release():
    app = create_app()
    client = authenticated_client(app)
    repository = app.state.turn_service.graphs
    body = font_body()
    body["definition"]["task_id"] = "DB-ONLY-FONT"
    reference = repository.register(body, version="1.0.0", approved=True, priority=200)
    assert repository.register(body, version="1.0.0", approved=True, priority=200) == reference
    payload = start_payload()
    first = client.post("/api/v1/agent/commands", json=payload)
    assert first.status_code == 200, first.text
    assert first.json()["task_id"] == "DB-ONLY-FONT"
    assert first.json()["graph_reference"]["sha256"] == reference["sha256"]
    other_session = {**payload, "session_id": "different-session"}
    reused = client.post("/api/v1/agent/commands", json=other_session)
    assert reused.status_code == 409, reused.text
    assert reused.headers["x-conflict-code"] == "REQUEST_ID_REUSED"
    changed = deepcopy(body)
    changed["transitions"][1]["guide_message"] = "A different release"
    repository.register(changed, version="2.0.0", approved=True, priority=201)
    # New tasks select the new release; the existing task and exact retry retain v1.
    retry = client.post("/api/v1/agent/commands", json=payload)
    assert retry.status_code == 200, retry.text
    assert retry.json() == first.json()
    observe = {k: v for k, v in payload.items() if k != "goal"}
    observe.update(command_type="observe", request_id="db-observe",
        expected_version=first.json()["session_version"], episode_id=first.json()["episode_id"])
    followed = client.post("/api/v1/agent/commands", json=observe)
    assert followed.status_code == 200, followed.text
    assert followed.json()["graph_reference"] == first.json()["graph_reference"]
    pause = {"contract_version": "agent-fe-contract.v0.2", "request_id": "db-pause",
        "session_id": payload["session_id"], "command_type": "pause_task",
        "expected_version": followed.json()["session_version"],
        "episode_id": first.json()["episode_id"]}
    assert client.post("/api/v1/agent/commands", json=pause).status_code == 200
    with pytest.raises(ValueError, match="IMMUTABLE"):
        repository.register(changed, version="1.0.0", approved=True, priority=200)


@pytest.mark.parametrize("invalid", ["dangling", "duplicate", "unknown_field", "predicate", "confirmation", "terminal"])
def test_invalid_graph_definitions_are_rejected(invalid):
    body = font_body()
    if invalid == "dangling":
        body["transitions"][0]["to_state"] = "missing"
    elif invalid == "duplicate":
        body["transitions"].append(body["transitions"][0])
    elif invalid == "unknown_field":
        body["transitions"][0]["auto_tap"] = True
    elif invalid == "predicate":
        body["transitions"][0]["verification_predicate"] = "unsupported"
    elif invalid == "confirmation":
        body["transitions"][-2]["confirmation_message"] = None
    else:
        body["definition"]["done_state"] = "missing"
    with pytest.raises(ValueError):
        load_graph(body)


def test_db_tampering_and_metadata_mismatch_rejected():
    app = create_app()
    repository = app.state.turn_service.graphs
    goal = type("Goal", (), {"goal_type": "settings.font_size.increase",
        "target_app": "android_settings"})()
    with repository.factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        body = deepcopy(row.body)
        body["definition"]["task_id"] = "forged-task"
        row.body = body
        db.commit()
    with pytest.raises(ValueError, match="GRAPH_REFERENCE_MISMATCH"):
        repository.select(goal)
    # Even a consistent content hash cannot override DB task identity.
    with repository.factory() as db:
        row = db.get(ApprovedTaskGraph, "MVP-01:1.0.1")
        row.graph_hash = canonical_graph(load_graph(row.body))[1]
        db.commit()
    with pytest.raises(ValueError, match="GRAPH_REFERENCE_MISMATCH"):
        repository.select(goal)


def test_seed_adds_new_route_release_without_overwriting_a_pinned_old_graph():
    app = create_app()
    repository = app.state.turn_service.graphs
    goal = type("Goal", (), {"goal_type": "settings.font_size.increase", "target_app": "android_settings"})()
    current, _ = repository.select(goal)
    old, _ = canonical_graph(current)
    old["transitions"][0]["guide_message"] = "기존 설정 열기 안내"
    reference = repository.register(old, version="1.0.0", approved=True, priority=100)
    with repository.factory() as db:
        db.delete(db.get(ApprovedTaskGraph, "MVP-01:1.0.1")); db.commit()
    repository.seed_mvp()
    assert repository.select(goal)[1]["version"] == "1.0.1"
    pinned, preserved = repository.select_pinned({"task_id": "MVP-01", "graph_version": "1.0.0",
        "goal_type": goal.goal_type, "goal": {"target_app": goal.target_app}, "graph_hash": reference["sha256"]})
    assert preserved["sha256"] == reference["sha256"]
    assert pinned.transitions[0].guide_message == "기존 설정 열기 안내"
