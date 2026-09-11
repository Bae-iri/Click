from fastapi.testclient import TestClient

from app.main import create_app
from tests.helpers import register


def test_file_database_retains_login_and_revocation_across_restart(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/persistent.db"
    with TestClient(create_app(url)) as client:
        tokens = register(client)
    with TestClient(create_app(url)) as client:
        client.headers["Authorization"] = "Bearer " + tokens["access_token"]
        assert client.get("/api/v1/users/me").json()["id"] == tokens["user_id"]
        assert client.post("/api/v1/auth/logout").status_code == 204
    with TestClient(create_app(url)) as client:
        client.headers["Authorization"] = "Bearer " + tokens["access_token"]
        assert client.get("/api/v1/users/me").status_code == 401
