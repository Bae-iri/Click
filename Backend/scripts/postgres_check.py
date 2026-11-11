"""Use a uniquely named disposable PostgreSQL schema; never migrate public."""
import os
import json
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from pathlib import Path
import subprocess
import sys
from uuid import uuid4
from cryptography.fernet import Fernet

from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url

BACKEND = Path(__file__).resolve().parents[1]


def main():
    url = make_url(os.environ["TEST_POSTGRES_URL"])
    if url.get_backend_name() != "postgresql":
        raise ValueError("TEST_POSTGRES_URL must use PostgreSQL")
    schema = "click_verify_" + uuid4().hex
    engine = create_engine(url)
    try:
        with engine.begin() as connection:
            connection.execute(text(f'CREATE SCHEMA "{schema}"'))
        test_url = url.update_query_dict({"options": f"-csearch_path={schema}"})
        env = {**os.environ, "DATABASE_URL": test_url.render_as_string(hide_password=False), "GEMINI_API_KEY": "",
               "DATA_ENCRYPTION_KEYS": Fernet.generate_key().decode(), "DATA_LOOKUP_KEY": Fernet.generate_key().decode()}
        for args in (("upgrade", "20261104_17"), ("downgrade", "base"), ("upgrade", "head"), ("check",)):
            subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, check=True)
        subprocess.run([sys.executable, "scripts/postgres_check.py", "--http"], cwd=BACKEND, env=env, check=True)
        print("PASS: PostgreSQL migration, auth, agent restart/replay/cancellation fencing")
    finally:
        # Identifier is a fixed prefix plus a freshly generated UUID, never user input.
        with engine.begin() as connection:
            connection.execute(text(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE'))
        engine.dispose()


def check_http():
    sys.path.insert(0, str(BACKEND))
    from fastapi.testclient import TestClient
    from app.main import create_app
    from tests.helpers import register
    from app.clients.runtime_agent import RuntimeAgentAdapter
    request = json.loads((BACKEND / "examples/agent-turn.json").read_text(encoding="utf-8"))
    session_path = "/api/v1/agent/sessions/" + request["session_id"]
    with TestClient(create_app()) as client:
        assert client.get("/ready").status_code == 200
        first = register(client)
        second = register(client)
        assert client.get("/api/v1/users/" + first["user_id"]).status_code == 403
        first_turn = client.post("/api/v1/agent/turn", json=request)
        assert first_turn.status_code == 200
    restarted_app = create_app()
    with TestClient(restarted_app) as client:
        client.headers["Authorization"] = "Bearer " + second["access_token"]
        assert client.get("/api/v1/users/me").json()["id"] == second["user_id"]
        assert client.get(session_path).json()["version"] == 1
        assert client.post("/api/v1/agent/turn", json=request).json() == first_turn.json()
        entered, release = Event(), Event()
        class Slow(RuntimeAgentAdapter):
            def handle_turn(self, request):
                entered.set()
                assert release.wait(10)
                return super().handle_turn(request)
        restarted_app.state.turn_service.engine_factory = Slow
        follow = {**request, "request_id": "pg-follow", "expected_version": 1}
        follow.pop("raw_goal")
        with TestClient(create_app()) as other, ThreadPoolExecutor(max_workers=1) as pool:
            other.headers["Authorization"] = "Bearer " + second["access_token"]
            running = pool.submit(client.post, "/api/v1/agent/turn", json=follow)
            try:
                assert entered.wait(5)
                assert other.post("/api/v1/agent/turn", json=follow).status_code == 409
                assert other.post(session_path + "/cancel", json={"expected_version": 1}).status_code == 200
            finally:
                release.set()
            assert running.result(timeout=10).status_code == 409
        assert client.get(session_path).json()["status"] == "CANCELLED"
        assert client.put(session_path + "/feedback", json={"rating": 5, "reason_code": "helpful"}).status_code == 200
        assert client.get(session_path + "/feedback").json()["rating"] == 5
        assert client.get("/api/v1/agent/sessions").json()["items"]
        from tests.test_backend_completion import ContractAgent, Delivery
        restarted_app.state.turn_service.engine_factory = ContractAgent
        proposed = {**request, "session_id": "pg-approval", "request_id": "pg-approval-first"}
        confirmation = client.post("/api/v1/agent/turn", json=proposed)
        assert confirmation.status_code == 200
        accepted = {**proposed, "request_id": "pg-approval-accept", "expected_version": 1,
            "user_confirmation": "accepted", "confirmation_id": confirmation.json()["metadata"]["confirmation"]["id"]}
        accepted.pop("raw_goal")
        assert client.post("/api/v1/agent/turn", json=accepted).status_code == 200
        restarted_app.state.account_actions.delivery = Delivery()
        # Email actions remain available only for legacy accounts with an email.
        from app.models.auth import Account
        with restarted_app.state.session_factory() as db:
            db.get(Account, second["user_id"]).email = "legacy-check@example.com"
            db.commit()
        assert client.post("/api/v1/auth/email/request").status_code == 202
        token = restarted_app.state.account_actions.delivery.messages[-1][2]
        assert client.post("/api/v1/auth/email/verify", json={"token": token}).status_code == 204
        assert client.delete("/api/v1/users/" + second["user_id"]).status_code == 204
        assert client.get("/api/v1/users/me").status_code == 401


if __name__ == "__main__":
    check_http() if "--http" in sys.argv else main()
