from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import json
from pathlib import Path
from threading import Barrier, Event

from fastapi.testclient import TestClient
import pytest
from sqlalchemy import func, select, update

from app.main import create_app
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.models.agent_session import AgentCheckpoint, AgentSession, AgentTurn
from tests.helpers import register


def payload(request_id="r1", session_id="s1"):
    value = json.loads((Path(__file__).parents[1] / "examples/agent-turn.json").read_text(encoding="utf-8"))
    return {**value, "request_id": request_id, "session_id": session_id}


def follow(version, request_id="r2", session_id="s1"):
    value = payload(request_id, session_id)
    value.pop("raw_goal")
    value["expected_version"] = version
    return value


def post(client, value):
    return client.post("/api/v1/agent/turn", json=value)


def session(client, sid="s1"):
    return client.get("/api/v1/agent/sessions/" + sid)


def cancel(client, version, sid="s1"):
    return client.post(f"/api/v1/agent/sessions/{sid}/cancel", json={"expected_version": version})


def test_restart_restores_session_and_cached_response(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/restart.db"
    with TestClient(create_app(url)) as client:
        auth = register(client)
        first = post(client, payload())
        assert first.status_code == 200
        assert first.json()["session_version"] == 1
    with TestClient(create_app(url)) as client:
        client.headers["Authorization"] = "Bearer " + auth["access_token"]
        state = session(client).json()
        assert state["version"] == 1 and state["requires_fresh_observation"]
        assert not state["requires_goal"]
        assert "guide_command" not in state
        assert post(client, payload()).json() == first.json()
        response = post(client, follow(1))
        assert response.status_code == 200
        assert response.json()["session_version"] == 2
        assert session(client).json()["version"] == 2
        assert post(client, payload()).headers["x-conflict-code"] == "STALE_REPLAY"


def test_changed_payload_stale_version_missing_version_and_duplicate():
    app = create_app()
    with TestClient(app) as client:
        register(client)
        original = payload()
        first = post(client, original)
        assert first.status_code == 200
        changed = deepcopy(original)
        changed["accessibility_tree"]["nodes"][0]["text"] = "different"
        assert post(client, changed).headers["x-conflict-code"] == "REQUEST_ID_REUSED"
        assert post(client, follow(0)).status_code == 409
        missing = follow(1)
        missing.pop("expected_version")
        assert post(client, missing).status_code == 409
        assert post(client, original).json() == first.json()
        with app.state.session_factory() as db:
            assert db.scalar(select(func.count()).select_from(AgentTurn)) == 1


def test_owner_cancel_replay_and_expiry():
    app = create_app()
    now = [1000]
    app.state.turn_service.repository.clock = lambda: now[0]
    with TestClient(app) as client:
        first_user = register(client)
        assert post(client, payload()).status_code == 200
        register(client)
        assert session(client).status_code == 403
        assert cancel(client, 1).status_code == 403
        client.headers["Authorization"] = "Bearer " + first_user["access_token"]
        assert cancel(client, 0).status_code == 409
        stopped = cancel(client, 1)
        assert stopped.status_code == 200 and stopped.json()["status"] == "CANCELLED"
        assert cancel(client, 1).json() == stopped.json()
        assert post(client, payload()).status_code == 409
        assert post(client, follow(2)).status_code == 409
        assert post(client, payload("expire-1", "expiry")).status_code == 200
        now[0] += 1801
        assert session(client, "expiry").json()["status"] == "EXPIRED"
        assert post(client, payload("expire-1", "expiry")).status_code == 410
        assert cancel(client, 1, "expiry").status_code == 410


def test_terminal_completion_survives_restart_and_blocks_new_turns(tmp_path):
    from tests.test_task_outcomes import screen
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/completed.db"
    with TestClient(create_app(url)) as client:
        auth = register(client)
        first = {**payload(), "accessibility_tree": screen(3, 1000, 1.0)}
        initial = post(client, first).json()
        assert initial["task_status"] == "WAITING_CONFIRMATION"
        accepted = post(client, {**follow(1), "accessibility_tree": screen(3, 1100, 1.0),
            "user_confirmation": "accepted", "confirmation_id": initial["metadata"]["confirmation"]["id"]})
        assert accepted.status_code == 200
    with TestClient(create_app(url)) as client:
        client.headers["Authorization"] = "Bearer " + auth["access_token"]
        unchanged = post(client, {**follow(2, "r3"), "accessibility_tree": screen(3, 1150, 1.0)})
        assert unchanged.json()["task_status"] != "COMPLETED"
        complete = {**follow(3, "r4"), "accessibility_tree": screen(4, 1800, 1.15),
            "user_action_event": {"event_id": "completed-drag", "action_type": "drag_completed", "occurred_at_ms": 1200,
                "guide_command_id": accepted.json()["guide_command"]["guide_command_id"], "target_node_id": "font-slider"}}
        waiting = post(client, complete)
        assert waiting.json()["task_status"] == "WAITING_RESULT"
        assert post(client, complete).json() == waiting.json()
    with TestClient(create_app(url)) as client:
        client.headers["Authorization"] = "Bearer " + auth["access_token"]
        state = session(client).json()
        assert state["status"] == "WAITING_RESULT"
        done = client.post("/api/v1/agent/commands", json={"contract_version": "agent-fe-contract.v0.2",
            "request_id": "confirm-result", "session_id": "s1", "expected_version": 4,
            "episode_id": state["episode_id"], "command_type": "respond_result", "user_result": "satisfied",
            "result_prompt_id": state["task_outcome"]["prompt"]["id"]})
        assert done.status_code == 200, done.text
        assert done.json()["task_status"] == "COMPLETED"
        assert post(client, follow(5, "r5")).status_code == 409
        assert cancel(client, 5).status_code == 409


def test_checkpoint_has_no_raw_goal_screen_labels_or_plain_fingerprint():
    app = create_app()
    with TestClient(app) as client:
        register(client)
        request = payload()
        request["raw_goal"] = "increase font size private-goal-458"
        request["accessibility_tree"]["nodes"].append({"node_id": "private-node", "text": "private-screen-997"})
        assert post(client, request).status_code == 200
        with app.state.session_factory() as db:
            checkpoint = db.get(AgentCheckpoint, "s1")
            response = db.scalar(select(AgentTurn)).response
            data = json.dumps([checkpoint.state, response])
            assert "private-goal-458" not in data and "private-screen-997" not in data
            assert len(checkpoint.state["last_snapshot"]["fingerprint"]) == 64


def test_delete_user_cascades_session_checkpoint_and_turn():
    app = create_app()
    with TestClient(app) as client:
        auth = register(client)
        assert post(client, payload()).status_code == 200
        assert client.delete("/api/v1/users/" + auth["user_id"]).status_code == 204
        with app.state.session_factory() as db:
            for model in (AgentSession, AgentCheckpoint, AgentTurn):
                assert db.scalar(select(func.count()).select_from(model)) == 0


def test_failed_runtime_does_not_advance_and_identical_retry_can_succeed():
    app = create_app()
    class Failing(RuntimeAgentAdapter):
        def handle_turn(self, request):
            super().handle_turn(request)
            raise RuntimeError("failure after runtime mutation")
    with TestClient(app) as client:
        register(client)
        app.state.turn_service.engine_factory = Failing
        assert post(client, payload()).status_code == 500
        assert session(client).json()["version"] == 0
        assert not session(client).json()["processing"]
        with app.state.session_factory() as db:
            assert db.get(AgentCheckpoint, "s1") is None
        app.state.turn_service.engine_factory = RuntimeAgentAdapter
        assert post(client, payload()).json()["session_version"] == 1


@pytest.mark.parametrize("action", ["compete", "cancel", "lease_expire", "session_expire", "delete"])
def test_inflight_turn_fencing_across_two_apps(tmp_path, action):
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/concurrency.db"
    slow_app, other_app = create_app(url), create_app(url)
    entered, finish = Event(), Event()
    now = [1000]
    for app in (slow_app, other_app):
        app.state.turn_service.repository.clock = lambda: now[0]
    class Slow(RuntimeAgentAdapter):
        def handle_turn(self, request):
            entered.set()
            assert finish.wait(10)
            return super().handle_turn(request)
    with TestClient(slow_app) as client, TestClient(other_app) as other:
        auth = register(client)
        other.headers["Authorization"] = "Bearer " + auth["access_token"]
        assert post(client, payload()).status_code == 200
        slow_app.state.turn_service.engine_factory = Slow
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(post, client, follow(1))
            try:
                assert entered.wait(5)
                if action == "compete":
                    assert post(other, follow(1)).status_code == 409
                    assert post(other, follow(1, "competing")).status_code == 409
                elif action == "cancel":
                    assert cancel(other, 1).json()["status"] == "CANCELLED"
                elif action == "lease_expire":
                    now[0] += 61
                    assert post(other, follow(1)).status_code == 200
                elif action == "session_expire":
                    now[0] += 1801
                    assert session(other).json()["status"] == "EXPIRED"
                else:
                    assert other.delete("/api/v1/users/" + auth["user_id"]).status_code == 204
            finally:
                finish.set()
            result = pending.result(timeout=10)
        # Account deletion is rejected by the owner guard before session fencing.
        expected_status = 200 if action == "compete" else 401 if action == "delete" else 409
        assert result.status_code == expected_status
        if action == "delete":
            with other_app.state.session_factory() as db:
                assert db.get(AgentSession, "s1") is None
                assert db.get(AgentCheckpoint, "s1") is None
                assert db.scalar(select(func.count()).select_from(AgentTurn)) == 0
        if action in ("compete", "lease_expire"):
            assert session(other).json()["version"] == 2
            assert post(other, follow(1)).status_code == 200


def test_graph_change_rejects_resume_and_cleanup_removes_only_old_terminal():
    app = create_app()
    now = [1000]
    repository = app.state.turn_service.repository
    repository.clock = lambda: now[0]
    with TestClient(app) as client:
        register(client)
        assert post(client, payload()).status_code == 200
        with app.state.session_factory() as db:
            db.execute(update(AgentCheckpoint).values(graph_version="incompatible"))
            db.commit()
        assert post(client, follow(1)).headers["x-conflict-code"] == "CHECKPOINT_INCOMPATIBLE"
        assert post(client, payload()).headers["x-conflict-code"] == "CHECKPOINT_INCOMPATIBLE"
        assert cancel(client, 1).status_code == 200
        now[0] += 101
        assert post(client, payload("active-request", "active")).status_code == 200
        assert repository.cleanup(retention_seconds=100) == {"expired": 0, "removed": 1}
        assert session(client).status_code == 404
        assert session(client, "active").status_code == 200


def test_cached_response_rechecks_withdrawn_personalization_consent():
    app = create_app()
    with TestClient(app) as client:
        auth = register(client)
        uid = auth["user_id"]
        client.patch(f"/api/v1/users/{uid}/profile", json={"comprehension_level": "beginner"})
        consent = {"purpose": "personalization", "consented": True, "policy_version": "v1"}
        assert client.post(f"/api/v1/users/{uid}/consents", json=consent).status_code == 201
        first = post(client, payload())
        assert first.status_code == 200 and "ui_profile" in first.json()["metadata"]
        assert client.post(f"/api/v1/users/{uid}/consents", json={**consent, "consented": False}).status_code == 201
        retried = post(client, payload())
        assert retried.status_code == 200 and "ui_profile" not in retried.json()["metadata"]
        assert retried.json()["session_version"] == first.json()["session_version"]
        with app.state.session_factory() as db:
            assert "ui_profile" not in db.scalar(select(AgentTurn)).response["metadata"]


def test_abandoned_claim_is_recoverable_without_runtime_memory():
    from app.schemas.agent_turn import AgentTurnRequest
    from hashlib import sha256
    app = create_app()
    now = [1000]
    repo = app.state.turn_service.repository
    repo.clock = lambda: now[0]
    with TestClient(app) as client:
        auth = register(client)
        request = AgentTurnRequest(**payload(), user_id=auth["user_id"])
        digest = sha256(json.dumps(request.model_dump(mode="json"), sort_keys=True,
                                   separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        repo.claim(request, digest)  # Simulates process death immediately after DB claim.
        assert post(client, payload()).status_code == 409
        now[0] += 61
        assert post(client, payload()).json()["session_version"] == 1


def test_ambiguous_goal_can_be_clarified_without_storing_original_text():
    with TestClient(create_app()) as client:
        register(client)
        ambiguous = {**payload(), "raw_goal": "hello"}
        first = post(client, ambiguous)
        assert first.status_code == 200
        assert session(client).json()["requires_goal"]
        resolved = post(client, {**payload("r2"), "expected_version": 1})
        assert resolved.status_code == 200
        assert not session(client).json()["requires_goal"]


def test_simultaneous_first_requests_have_one_committed_result(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/first.db"
    app_a, app_b = create_app(url), create_app(url)
    barrier = Barrier(2)
    with TestClient(app_a) as a, TestClient(app_b) as b:
        auth = register(a)
        b.headers["Authorization"] = "Bearer " + auth["access_token"]
        def race(client):
            barrier.wait(timeout=5)
            return post(client, payload())
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(race, (a, b)))
        assert any(result.status_code == 200 for result in results)
        assert all(result.status_code in (200, 409) for result in results)
        assert session(a).json()["version"] == 1
        assert post(b, payload()).status_code == 200
        with app_a.state.session_factory() as db:
            assert db.scalar(select(func.count()).select_from(AgentTurn)) == 1
