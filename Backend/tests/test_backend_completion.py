from copy import deepcopy
from pathlib import Path
import sqlite3
import time
from fastapi.testclient import TestClient
import pytest
from sqlalchemy import select, func
from app.main import create_app
from app.models.lifecycle import SafetyConfirmation, TaskFeedback, AccountAction
from app.schemas.agent_turn import AgentTurnResponse
from app.schemas.guidance import GuideCommandResponse
from tests.helpers import register as register_phone
from app.models.auth import Account

def register(client, *args, **kwargs):
    tokens = register_phone(client, *args, **kwargs)
    with client.app.state.session_factory() as db:
        account = db.get(Account, tokens["user_id"])
        account.email = account.phone_number + "@example.com"
        db.commit()
    return tokens

from tests.test_agent_sessions import payload, post, follow, cancel


class ContractAgent:
    """No runtime, graph, model or network: exercises the backend contract."""
    mode = "normal"
    def __init__(self, session_store=None): pass
    def handle_turn(self, request):
        confirm = request.user_confirmation != "accepted"
        guide = GuideCommandResponse(guide_command_id="test-guide", command_type="confirmation" if confirm else "gesture_hint",
            action_type="observe_only" if confirm else "drag", proposed_action="drag" if confirm else None,
            message="Test guidance", safety_level="CONFIRM_REQUIRED", requires_user_action=True,
            target_node_id="test-target", target_bounds=(10, 10, 100, 100))
        if self.mode == "bad_bounds": guide.target_bounds = (-1, 0, 5000, 100)
        if self.mode == "changed_target" and not confirm: guide.target_node_id = "different"
        return AgentTurnResponse(request_id=request.request_id, session_id=request.session_id,
            task_id="contract-task", step=0, total_steps=1, state_id="contract-state", state_confidence=1,
            task_status="WAITING_CONFIRMATION" if confirm else "IN_PROGRESS", guide_command=guide)


class Delivery:
    def __init__(self): self.messages = []
    def configured(self): return True
    def send(self, email, purpose, token): self.messages.append((email, purpose, token))


def setup():
    app = create_app()
    app.state.turn_service.engine_factory = ContractAgent
    app.state.account_actions.delivery = Delivery()
    return app


def approve(first, **changes):
    return {**follow(1), "user_confirmation": "accepted", "confirmation_id": first["metadata"]["confirmation"]["id"], **changes}


def test_ai_independent_approval_replay_and_consumption():
    app = setup()
    with TestClient(app) as client:
        register(client)
        first = post(client, payload()).json()
        value = approve(first)
        result = post(client, value)
        assert result.status_code == 200
        assert result.json()["guide_command"]["action_type"] == "drag"
        assert post(client, value).json() == result.json()
        assert post(client, {**value, "request_id": "another", "expected_version": 2}).status_code == 409
        with app.state.session_factory() as db:
            assert db.get(SafetyConfirmation, "s1") is None


@pytest.mark.parametrize("case", ["missing", "wrong", "screen", "expired", "goal", "target", "cancelled"])
def test_approval_cannot_be_reused_for_other_context(case, monkeypatch):
    app = setup()
    with TestClient(app) as client:
        register(client)
        first = post(client, payload()).json()
        value = approve(first)
        if case == "missing": value.pop("confirmation_id")
        if case == "wrong": value["confirmation_id"] = "0" * 36
        if case == "screen": value["accessibility_tree"]["nodes"][0]["text"] = "changed"
        if case == "expired": app.state.turn_service.repository.clock = lambda: time.time() + 121
        if case == "goal": value["raw_goal"] = "another goal"
        if case == "target": monkeypatch.setattr(ContractAgent, "mode", "changed_target")
        if case == "cancelled": assert cancel(client, 1).status_code == 200
        assert post(client, value).status_code == 409


def test_invalid_ai_target_is_not_stored(monkeypatch):
    monkeypatch.setattr(ContractAgent, "mode", "bad_bounds")
    with TestClient(setup()) as client:
        register(client)
        assert post(client, payload()).status_code == 502
        assert client.get("/api/v1/agent/sessions/s1").json()["version"] == 0


def test_history_feedback_pagination_ownership_and_deletion():
    app = setup()
    with TestClient(app) as client:
        auth = register(client)
        for sid in ("a", "b", "c"):
            assert post(client, payload(sid, sid)).status_code == 200
            assert cancel(client, 1, sid).status_code == 200
        page = client.get("/api/v1/agent/sessions?limit=2").json()
        assert len(page["items"]) == 2
        assert len(client.get("/api/v1/agent/sessions", params={"after": page["next_cursor"]}).json()["items"]) == 1
        path = "/api/v1/agent/sessions/a/feedback"
        assert client.put(path, json={"rating": 5, "reason_code": "helpful"}).status_code == 200
        assert client.put(path, json={"rating": 3}).status_code == 200
        assert client.get(path).json()["rating"] == 3
        with app.state.session_factory() as db:
            assert db.scalar(select(func.count()).select_from(TaskFeedback)) == 1
        assert client.put(path, json={"rating": 6}).status_code == 422
        register(client, "01080447345")
        assert client.get(path).status_code == 403
        assert client.get("/api/v1/agent/sessions").json()["items"] == []
        client.headers["Authorization"] = "Bearer " + auth["access_token"]
        assert client.delete("/api/v1/agent/sessions/a").status_code == 204
        assert client.get(path).status_code == 404


def test_account_email_verification_and_reset_revoke_sessions():
    app = setup()
    with TestClient(app) as client:
        auth = register(client)
        assert client.post("/api/v1/auth/email/request").status_code == 202
        token = app.state.account_actions.delivery.messages[-1][2]
        assert client.get("/api/v1/auth/email/status").json() == {"verified": False}
        assert client.post("/api/v1/auth/email/verify", json={"token": token}).status_code == 204
        assert client.get("/api/v1/auth/email/status").json() == {"verified": True}
        assert client.post("/api/v1/auth/email/verify", json={"token": token}).status_code == 400
        email = app.state.account_actions.delivery.messages[0][0]
        response = client.post("/api/v1/auth/password/forgot", json={"email": email})
        token = app.state.account_actions.delivery.messages[-1][2]
        assert response.status_code == 202 and token not in response.text
        with app.state.session_factory() as db:
            row = db.scalar(select(AccountAction))
            assert row.token_hash != token
        assert client.post("/api/v1/auth/password/reset", json={"token": token, "new_password": "5678"}).status_code == 204
        assert client.get("/api/v1/users/me").status_code == 401
        assert client.post("/api/v1/auth/refresh", json={"refresh_token": auth["refresh_token"]}).status_code == 401
        assert client.post("/api/v1/auth/login", json={"phone_number": email.split("@")[0], "password": "5678"}).status_code == 200
        assert client.post("/api/v1/auth/password/reset", json={"token": token, "new_password": "5678"}).status_code == 400


def test_expired_and_wrong_purpose_tokens_and_unknown_email():
    app = setup()
    with TestClient(app) as client:
        register(client)
        assert client.post("/api/v1/auth/email/request").status_code == 202
        token = app.state.account_actions.delivery.messages[-1][2]
        assert client.post("/api/v1/auth/password/reset", json={"token": token, "new_password": "5678"}).status_code == 400
        app.state.account_actions.clock = lambda: time.time() + 86401
        assert client.post("/api/v1/auth/email/verify", json={"token": token}).status_code == 400
        assert client.post("/api/v1/auth/password/forgot", json={"email": "unknown@example.com"}).status_code == 202
        assert len(app.state.account_actions.delivery.messages) == 1




def test_backup_restore_requires_new_destination(tmp_path):
    from scripts.database_backup import sqlite_copy
    original, backup, restored = [tmp_path / name for name in ("original.db", "backup.db", "restored.db")]
    db = sqlite3.connect(original)
    db.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY)")
    db.execute("INSERT INTO sample VALUES (42)")
    db.commit()
    db.close()
    sqlite_copy(original, backup)
    sqlite_copy(backup, restored)
    db = sqlite3.connect(restored)
    assert db.execute("SELECT id FROM sample").fetchone() == (42,)
    db.close()
    with pytest.raises(ValueError): sqlite_copy(backup, original)


def test_metrics_require_separate_operator_token(monkeypatch):
    monkeypatch.setenv("OPS_TOKEN", "operator-secret-" + "x" * 32)
    with TestClient(setup()) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/ops/metrics").status_code == 401
        response = client.get("/ops/metrics", headers={"Authorization": "Bearer operator-secret-" + "x" * 32})
        assert response.status_code == 200
        assert response.json()["requests"] >= 2
        assert "secret" not in response.text


def test_default_server_has_no_legacy_approval_bypass():
    with TestClient(setup()) as client:
        register(client)
        assert client.post("/api/v1/sessions", json={"goal": "test"}).status_code == 404


def test_email_delivery_failure_invalidates_token_and_never_returns_it():
    app = setup()
    class Broken(Delivery):
        def send(self, email, purpose, token):
            self.messages.append((email, purpose, token))
            raise RuntimeError("secret provider diagnostics")
    app.state.account_actions.delivery = Broken()
    with TestClient(app) as client:
        register(client)
        response = client.post("/api/v1/auth/email/request")
        assert response.status_code == 503 and "secret" not in response.text
        token = app.state.account_actions.delivery.messages[-1][2]
        assert token not in response.text
        assert client.post("/api/v1/auth/email/verify", json={"token": token}).status_code == 400


def test_concurrent_reset_consumes_token_once(tmp_path):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    url = "sqlite+pysqlite:///" + (tmp_path / "reset.db").as_posix()
    app, other = create_app(url), create_app(url)
    app.state.account_actions.delivery = Delivery()
    with TestClient(app) as client, TestClient(other) as second:
        register(client)
        client.post("/api/v1/auth/email/request")
        email = app.state.account_actions.delivery.messages[-1][0]
        client.post("/api/v1/auth/password/forgot", json={"email": email})
        token = app.state.account_actions.delivery.messages[-1][2]
        barrier = Barrier(2)
        def reset(target):
            barrier.wait(timeout=5)
            return target.post("/api/v1/auth/password/reset", json={"token": token, "new_password": "5678"}).status_code
        with ThreadPoolExecutor(max_workers=2) as pool:
            assert sorted(pool.map(reset, (client, second))) == [204, 400]


def test_feedback_and_approval_are_removed_on_account_deletion():
    app = setup()
    with TestClient(app) as client:
        auth = register(client)
        post(client, payload())
        post(client, payload("r2", "s2"))
        cancel(client, 1, "s2")
        client.put("/api/v1/agent/sessions/s2/feedback", json={"rating": 5})
        assert client.delete("/api/v1/users/" + auth["user_id"]).status_code == 204
        with app.state.session_factory() as db:
            for model in (SafetyConfirmation, TaskFeedback):
                assert db.scalar(select(func.count()).select_from(model)) == 0
