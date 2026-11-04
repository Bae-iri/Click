import time
from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient
from sqlalchemy import select, update

from app.main import create_app
from app.models.auth import Account, AuthSession
from app.models.user import User
from tests.helpers import register


def test_registration_login_rotation_logout_and_hash_storage():
    app = create_app(enable_legacy=True)
    with TestClient(app) as client:
        tokens = register(client, "Person@Example.com")
        assert client.get("/api/v1/users/me").json()["id"] == tokens["user_id"]
        with app.state.session_factory() as db:
            account = db.scalar(select(Account))
            session = db.scalar(select(AuthSession))
            assert account.email == "person@example.com"
            assert "test-password" not in account.password_hash
            assert tokens["access_token"] != session.access_hash
            assert tokens["refresh_token"] != session.refresh_hash
        invalid = client.post("/api/v1/auth/login", json={"email": "person@example.com", "password": "wrong-password-123"})
        missing = client.post("/api/v1/auth/login", json={"email": "absent@example.com", "password": "wrong-password-123"})
        assert invalid.status_code == missing.status_code == 401
        assert invalid.json()["error"]["code"] == missing.json()["error"]["code"]
        login = client.post("/api/v1/auth/login", json={"email": "person@example.com", "password": "test-password-1234"})
        assert login.status_code == 200 and login.headers["cache-control"] == "no-store"
        refreshed = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
        assert refreshed.status_code == 200
        assert client.get("/api/v1/users/me").status_code == 401
        assert client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401
        client.headers["Authorization"] = "Bearer " + refreshed.json()["access_token"]
        assert client.post("/api/v1/auth/logout").status_code == 204
        assert client.get("/api/v1/users/me").status_code == 401
        assert client.post("/api/v1/auth/refresh", json={"refresh_token": refreshed.json()["refresh_token"]}).status_code == 401


def test_missing_tampered_expired_tokens_and_duplicate_registration():
    app = create_app(enable_legacy=True)
    with TestClient(app) as client:
        assert client.get("/api/v1/users/me").status_code == 401
        tokens = register(client, "same@example.com")
        assert client.post("/api/v1/auth/register", json={"email": "SAME@example.com", "password": "test-password-1234"}).status_code == 409
        with app.state.session_factory() as db:
            assert len(db.scalars(select(User)).all()) == 1
            db.execute(update(AuthSession).values(access_expires_at=int(time.time()) - 1,
                                                   refresh_expires_at=int(time.time()) - 1))
            db.commit()
        assert client.get("/api/v1/users/me").status_code == 401
        assert client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401
        client.headers["Authorization"] = "Bearer " + "x" * 43
        assert client.get("/api/v1/users/me").status_code == 401


def test_every_user_route_rejects_another_owner_and_delete_revokes_tokens():
    app = create_app(enable_legacy=True)
    with TestClient(app) as client:
        first = register(client)
        second = register(client)
        path = "/api/v1/users/" + first["user_id"]
        for method, suffix, body in [
            ("GET", "", None), ("PATCH", "/profile", {"explanation_depth": "concise"}),
            ("POST", "/consents", {"purpose": "model_training", "consented": True, "policy_version": "v1"}),
            ("POST", "/events", {"event_type": "help_opened"}), ("DELETE", "", None),
        ]:
            assert client.request(method, path + suffix, json=body).status_code == 403
        assert client.delete("/api/v1/users/" + second["user_id"]).status_code == 204
        assert client.get("/api/v1/users/me").status_code == 401
        assert client.post("/api/v1/auth/refresh", json={"refresh_token": second["refresh_token"]}).status_code == 401


def test_password_change_revokes_all_logins():
    with TestClient(create_app(enable_legacy=True)) as client:
        tokens = register(client, "password@example.com")
        other = client.post("/api/v1/auth/login", json={"email": "password@example.com", "password": "test-password-1234"}).json()
        assert client.post("/api/v1/auth/password", json={"current_password": "wrong", "new_password": "new-password-1234"}).status_code == 401
        assert client.post("/api/v1/auth/password", json={"current_password": "test-password-1234", "new_password": "new-password-1234"}).status_code == 204
        for token in (tokens, other):
            client.headers["Authorization"] = "Bearer " + token["access_token"]
            assert client.get("/api/v1/users/me").status_code == 401
        assert client.post("/api/v1/auth/login", json={"email": "password@example.com", "password": "new-password-1234"}).status_code == 200


def test_concurrent_refresh_only_succeeds_once(tmp_path):
    app = create_app(f"sqlite+pysqlite:///{tmp_path.as_posix()}/auth.db")
    with TestClient(app) as client:
        tokens = register(client)
        def refresh(_):
            return client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code
        with ThreadPoolExecutor(max_workers=2) as pool:
            assert sorted(pool.map(refresh, range(2))) == [200, 401]


def test_agent_and_legacy_sessions_require_and_enforce_ownership():
    from pathlib import Path
    import json
    payload = json.loads((Path(__file__).parents[1] / "examples/agent-turn.json").read_text(encoding="utf-8"))
    with TestClient(create_app(enable_legacy=True)) as client:
        assert client.post("/api/v1/agent/turn", json=payload).status_code == 401
        assert client.post("/api/v1/sessions", json={"goal": "increase font size"}).status_code == 401
        first = register(client)
        assert client.post("/api/v1/agent/turn", json=payload).status_code == 200
        legacy = client.post("/api/v1/sessions", json={"goal": "increase font size"}).json()["session_id"]
        register(client)
        assert client.post("/api/v1/agent/turn", json=payload).status_code == 403
        assert client.post("/api/v1/agent/turn", json={**payload, "user_id": first["user_id"]}).status_code == 403
        assert client.get("/api/v1/sessions/" + legacy).status_code == 404


def test_rate_limit():
    with TestClient(create_app(enable_legacy=True)) as client:
        for _ in range(20):
            assert client.post("/api/v1/auth/login", json={"email": "nobody@example.com", "password": "wrong-password-123"}).status_code == 401
        response = client.post("/api/v1/auth/login", json={"email": "nobody@example.com", "password": "wrong-password-123"})
        assert response.status_code == 429
        assert response.headers["retry-after"] == "60"


def test_eight_character_password_registration_login_change_and_duplicate_email():
    with TestClient(create_app()) as client:
        payload = {"email": "eight@example.com", "password": "eight888"}
        assert client.post("/api/v1/auth/register", json={**payload, "password": "short77"}).status_code == 422
        response = client.post("/api/v1/auth/register", json=payload)
        assert response.status_code == 201, response.text
        assert client.post("/api/v1/auth/register", json={**payload, "email": "EIGHT@example.com"}).status_code == 409
        assert client.post("/api/v1/auth/login", json=payload).status_code == 200
        client.headers["Authorization"] = "Bearer " + response.json()["access_token"]
        assert client.post("/api/v1/auth/password", json={"current_password": "eight888", "new_password": "next8888"}).status_code == 204
        assert client.post("/api/v1/auth/login", json={**payload, "password": "next8888"}).status_code == 200


def test_registration_saves_trimmed_name_in_user_profile():
    app = create_app()
    with TestClient(app) as client:
        response = client.post("/api/v1/auth/register", json={
            "name": "  김클릭  ", "email": "named@example.com", "password": "eight888"
        })
        assert response.status_code == 201, response.text
        client.headers["Authorization"] = "Bearer " + response.json()["access_token"]
        assert client.get("/api/v1/users/me").json()["name"] == "김클릭"
