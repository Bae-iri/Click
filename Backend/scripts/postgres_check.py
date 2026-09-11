"""Use a uniquely named disposable PostgreSQL schema; never migrate public."""
import os
from pathlib import Path
import subprocess
import sys
from uuid import uuid4

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
        env = {**os.environ, "DATABASE_URL": test_url.render_as_string(hide_password=False), "GEMINI_API_KEY": ""}
        for args in (("upgrade", "head"), ("check",), ("downgrade", "base"), ("upgrade", "head"), ("check",)):
            subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, check=True)
        subprocess.run([sys.executable, "scripts/postgres_check.py", "--http"], cwd=BACKEND, env=env, check=True)
        print("PASS: PostgreSQL migration, metadata, auth persistence and ownership")
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
    with TestClient(create_app()) as client:
        assert client.get("/ready").status_code == 200
        first = register(client)
        second = register(client)
        assert client.get("/api/v1/users/" + first["user_id"]).status_code == 403
    with TestClient(create_app()) as client:
        client.headers["Authorization"] = "Bearer " + second["access_token"]
        assert client.get("/api/v1/users/me").json()["id"] == second["user_id"]
        assert client.delete("/api/v1/users/" + second["user_id"]).status_code == 204
        assert client.get("/api/v1/users/me").status_code == 401


if __name__ == "__main__":
    check_http() if "--http" in sys.argv else main()
