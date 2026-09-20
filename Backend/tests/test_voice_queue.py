from concurrent.futures import ThreadPoolExecutor
from threading import Event, Barrier
from uuid import uuid4
import time
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import func, select
from app.main import create_app
from app.models.voice_queue import VoiceRequest, VoiceQueueState
from app.models.agent_session import AgentSession, AgentTurn
from app.schemas.agent_turn import AgentTurnResponse
from app.schemas.guidance import GuideCommandResponse
from tests.helpers import register
from tests.test_agent_sessions import payload

BASE = "/api/v1/voice-requests"


class DoneAgent:
    def __init__(self, session_store=None): pass
    def handle_turn(self, request):
        return AgentTurnResponse(request_id=request.request_id, session_id=request.session_id,
            task_id="test", state_id="done", step=1, total_steps=1, state_confidence=1,
            task_status="COMPLETED", guide_command=GuideCommandResponse(guide_command_id="done",
                command_type="done", action_type="observe_only", message="Complete",
                safety_level="LOW_RISK", requires_user_action=False))


def enqueue(client, goal="글자를 크게", priority="NORMAL", request_id=None):
    return client.post(BASE + "/enqueue", json={"id": request_id or str(uuid4()), "raw_goal": goal, "priority": priority})


def claim(client):
    return client.post(BASE + "/next/claim")


def interrupt(client, immediate=False, request_id=None):
    return client.post(BASE + "/interrupt-and-clear", json={"id": request_id or str(uuid4()), "clear_queued": immediate})


def done(client, running):
    value = payload(str(uuid4()), running["agent_session_id"])
    value["raw_goal"] = running["raw_goal"]
    assert client.post("/api/v1/agent/turn", json=value).status_code == 200


def test_fifo_idempotency_and_single_running():
    with TestClient(create_app()) as client:
        register(client)
        identifier = str(uuid4())
        first = enqueue(client, request_id=identifier).json()["request"]
        assert enqueue(client, request_id=identifier).json()["request"] == first
        assert enqueue(client, "different", request_id=identifier).status_code == 409
        second = enqueue(client).json()["request"]
        assert first["sequence"] < second["sequence"]
        result = claim(client).json()
        assert result["claimed"] and result["running"]["id"] == first["id"]
        repeated = claim(client).json()
        assert not repeated["claimed"] and repeated["running"]["id"] == first["id"]
        assert len(repeated["queued"]) == 1


def test_vip_priority_stops_session_then_waits_for_replacement():
    with TestClient(create_app()) as client:
        register(client)
        enqueue(client)
        running = claim(client).json()["running"]
        stale = enqueue(client).json()["request"]
        vip = enqueue(client, "중단", "VIP").json()
        assert vip["queued"][0]["priority"] == "VIP"
        stopped = claim(client).json()
        assert stopped["running"] is None and stopped["awaiting_replacement"]
        assert client.get("/api/v1/agent/sessions/" + running["agent_session_id"]).json()["status"] == "CANCELLED"
        assert claim(client).json()["running"] is None
        new = enqueue(client, "새로운 요청").json()
        assert not new["awaiting_replacement"] and len(new["queued"]) == 1
        old = client.get(BASE + "/" + stale["id"]).json()["request"]
        assert old["status"] == "CANCELLED" and old["raw_goal"] is None
        assert claim(client).json()["running"]["id"] == new["request"]["id"]


def test_immediate_interrupt_clears_paused_running_and_waiting_requests():
    app = create_app()
    with TestClient(app) as client:
        register(client)
        enqueue(client, "A")
        running = claim(client).json()["running"]
        enqueue(client, "B")
        enqueue(client, "C")
        with app.state.session_factory() as db:
            session = db.get(AgentSession, running["agent_session_id"])
            session.status = "PAUSED"
            db.commit()
        stopped = interrupt(client, immediate=True).json()
        assert stopped["running"] is None
        assert stopped["queued"] == []
        assert stopped["awaiting_replacement"]
        assert client.get("/api/v1/agent/sessions/" + running["agent_session_id"]).json()["status"] == "CANCELLED"
        replacement = enqueue(client, "D").json()
        assert not replacement["awaiting_replacement"]
        assert [item["raw_goal"] for item in replacement["queued"]] == ["D"]


@pytest.mark.parametrize("immediate", [False, True])
def test_interrupt_retry_never_cancels_a_new_request(immediate):
    with TestClient(create_app()) as client:
        register(client)
        enqueue(client)
        claim(client)
        enqueue(client)
        identifier = str(uuid4())
        result = interrupt(client, immediate, identifier).json()
        assert len(result["queued"]) == (0 if immediate else 1)
        assert result["request"]["status"] == "COMPLETED"
        enqueue(client, "replacement")
        running = claim(client).json()["running"]
        replay = interrupt(client, immediate, identifier).json()
        assert replay["running"]["id"] == running["id"] and not replay["awaiting_replacement"]


def test_complete_and_next_claim_are_atomic_and_retry_safe(monkeypatch):
    app = create_app()
    app.state.turn_service.engine_factory = DoneAgent
    with TestClient(app) as client:
        register(client)
        enqueue(client)
        second = enqueue(client).json()["request"]
        running = claim(client).json()["running"]
        path = BASE + "/" + running["id"] + "/complete"
        assert client.post(path).status_code == 409
        done(client, running)
        original = app.state.voice_queue.advance
        def broken(*args): raise HTTPException(503)
        monkeypatch.setattr(app.state.voice_queue, "advance", broken)
        assert client.post(path).status_code == 503
        assert client.get(BASE).json()["running"]["id"] == running["id"]
        monkeypatch.setattr(app.state.voice_queue, "advance", original)
        result = client.post(path).json()
        assert result["request"]["status"] == "COMPLETED" and result["request"]["raw_goal"] is None
        assert result["running"]["id"] == second["id"] and result["claimed"]
        replay = client.post(path).json()
        assert not replay["claimed"] and replay["running"]["id"] == second["id"]


def test_fail_cancels_session_and_claims_next():
    with TestClient(create_app()) as client:
        register(client)
        enqueue(client)
        next_request = enqueue(client).json()["request"]
        running = claim(client).json()["running"]
        path = BASE + "/" + running["id"] + "/fail"
        result = client.post(path, json={"failure_code": "NETWORK_ERROR"}).json()
        assert result["running"]["id"] == next_request["id"]
        assert result["request"]["status"] == "FAILED"
        assert client.get("/api/v1/agent/sessions/" + running["agent_session_id"]).json()["status"] == "CANCELLED"
        assert client.post(path, json={"failure_code": "NETWORK_ERROR"}).status_code == 200
        assert client.post(path, json={"failure_code": "AGENT_ERROR"}).status_code == 409
        assert client.post(BASE + "/" + running["id"] + "/complete").status_code == 409


def test_restart_preserves_running_and_replacement_state(tmp_path):
    url = "sqlite+pysqlite:///" + (tmp_path / "queue.db").as_posix()
    with TestClient(create_app(url)) as client:
        auth = register(client)
        enqueue(client)
        initial = claim(client).json()["running"]
        enqueue(client)
    with TestClient(create_app(url)) as client:
        client.headers["Authorization"] = "Bearer " + auth["access_token"]
        assert claim(client).json()["running"]["agent_session_id"] == initial["agent_session_id"]
        interrupt(client)
    with TestClient(create_app(url)) as client:
        client.headers["Authorization"] = "Bearer " + auth["access_token"]
        assert client.get(BASE).json()["awaiting_replacement"]
        assert claim(client).json()["running"] is None
        assert len(enqueue(client, "replacement").json()["queued"]) == 1


def test_parallel_enqueues_and_claims_are_serialized(tmp_path):
    url = "sqlite+pysqlite:///" + (tmp_path / "parallel.db").as_posix()
    app = create_app(url)
    with TestClient(app) as client, TestClient(create_app(url)) as other:
        auth = register(client)
        other.headers["Authorization"] = "Bearer " + auth["access_token"]
        with ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda i: enqueue(client if i % 2 else other, str(i)), range(6)))
        assert all(response.status_code == 200 for response in results)
        assert sorted(response.json()["request"]["sequence"] for response in results) == list(range(1, 7))
        barrier = Barrier(2)
        def get(target):
            barrier.wait(timeout=5)
            return claim(target).json()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(get, (client, other)))
        assert sum(result["claimed"] for result in results) == 1
        assert results[0]["running"]["id"] == results[1]["running"]["id"]
        assert results[0]["running"]["sequence"] == 1
        with app.state.session_factory() as db:
            assert db.scalar(select(func.count()).select_from(VoiceRequest).where(VoiceRequest.status == "RUNNING")) == 1


def test_interrupt_fences_late_ai_result(tmp_path):
    entered, release = Event(), Event()
    class Slow(DoneAgent):
        def handle_turn(self, request):
            entered.set()
            assert release.wait(5)
            return super().handle_turn(request)
    url = "sqlite+pysqlite:///" + (tmp_path / "interrupt.db").as_posix()
    app = create_app(url)
    app.state.turn_service.engine_factory = Slow
    with TestClient(app) as client, TestClient(create_app(url)) as other:
        auth = register(client)
        other.headers["Authorization"] = "Bearer " + auth["access_token"]
        enqueue(client)
        running = claim(client).json()["running"]
        value = payload(str(uuid4()), running["agent_session_id"])
        value["raw_goal"] = running["raw_goal"]
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(client.post, "/api/v1/agent/turn", json=value)
            try:
                assert entered.wait(5)
                assert interrupt(other).status_code == 200
            finally:
                release.set()
            assert future.result(timeout=5).status_code == 409
        with app.state.session_factory() as db:
            assert db.scalar(select(AgentTurn)).response is None
        assert client.post(BASE + "/" + running["id"] + "/complete").status_code == 409


def test_owner_isolation_and_delete_cascade():
    app = create_app()
    with TestClient(app) as client:
        auth = register(client)
        row = enqueue(client).json()["request"]
        register(client, "other@example.com")
        assert client.get(BASE).json()["queued"] == []
        assert client.get(BASE + "/" + row["id"]).status_code == 404
        assert client.post(BASE + "/" + row["id"] + "/fail", json={"failure_code": "CLIENT_ERROR"}).status_code == 404
        client.headers["Authorization"] = "Bearer " + auth["access_token"]
        assert client.delete("/api/v1/users/" + auth["user_id"]).status_code == 204
        with app.state.session_factory() as db:
            assert db.scalar(select(func.count()).select_from(VoiceRequest).where(VoiceRequest.user_id == auth["user_id"])) == 0
            assert db.get(VoiceQueueState, auth["user_id"]) is None


def test_expiry_erases_raw_goal_and_cleanup_removes_records():
    app = create_app()
    now = [int(time.time())]
    app.state.voice_queue.clock = lambda: now[0]
    with TestClient(app) as client:
        register(client)
        row = enqueue(client, "sensitive original").json()["request"]
        running = claim(client).json()["running"]
        now[0] += 86401
        expired = client.get(BASE + "/" + row["id"]).json()
        assert expired["request"]["status"] == "FAILED" and expired["request"]["raw_goal"] is None
        assert expired["running"] is None
        assert client.get("/api/v1/agent/sessions/" + running["agent_session_id"]).json()["status"] == "CANCELLED"
        now[0] += 10
        assert app.state.voice_queue.cleanup(5) == 1
        assert client.get(BASE + "/" + row["id"]).status_code == 404


def test_cannot_override_the_queued_goal():
    with TestClient(create_app()) as client:
        register(client)
        enqueue(client)
        running = claim(client).json()["running"]
        value = payload(str(uuid4()), running["agent_session_id"])
        value["raw_goal"] = "different goal"
        response = client.post("/api/v1/agent/turn", json=value)
        assert response.status_code == 409 and response.headers["X-Conflict-Code"] == "VOICE_GOAL_MISMATCH"
