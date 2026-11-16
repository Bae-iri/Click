from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import text
from sqlalchemy.exc import OperationalError

from app.main import create_app


def test_health_readiness_and_openapi():
    with TestClient(create_app("sqlite+pysqlite:///:memory:")) as client:
        assert client.get("/health").json() == {"status": "ok"}
        assert client.get("/ready").json() == {"status": "ready", "database": "ok"}
        schema = client.get("/openapi.json").json()
        assert "/api/v1/agent/turn" in schema["paths"]
        assert "/api/v1/speech/transcriptions" not in schema["paths"]
        assert client.post("/api/v1/speech/transcriptions", json={}).status_code == 404
        assert "/ready" in schema["paths"]


def test_readiness_rejects_missing_schema():
    app = create_app("sqlite+pysqlite:///:memory:")
    with TestClient(app) as client:
        with app.state.engine.begin() as connection:
            connection.execute(text("DROP TABLE consents"))
        response = client.get("/ready")
        assert response.status_code == 503
        assert response.json()["database"] == "schema_missing"
        assert client.get("/health").status_code == 200


def test_db_failure_does_not_leak_credentials_or_fail_liveness():
    app = create_app("sqlite+pysqlite:///:memory:")
    with TestClient(app) as client:
        with patch.object(app.state.engine, "connect", side_effect=OperationalError(
            "SELECT 1", {}, Exception("secret-db-password")
        )):
            response = client.get("/ready")
            assert response.status_code == 503
            assert response.json()["database"] == "unavailable"
            assert "secret" not in response.text
            assert client.get("/health").status_code == 200
