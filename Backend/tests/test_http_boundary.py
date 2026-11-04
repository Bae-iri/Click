import asyncio
import logging

from fastapi.testclient import TestClient

from app.core.http import HttpBoundary
from app.main import create_app


def test_errors_have_safe_envelope_and_server_trace_id(caplog):
    app = create_app()

    @app.get("/test-failure")
    def fail():
        raise RuntimeError("private-password-and-db-url")

    with caplog.at_level(logging.INFO, logger="click.http"), TestClient(app) as client:
        validation = client.post("/api/v1/auth/register", json={"phone_number": "private-email", "password": "sensitive"})
        assert validation.status_code == 422
        response = client.get("/test-failure?secret=private-query", headers={"X-Request-ID": "untrusted-private-id"})
        assert response.status_code == 500
        assert response.json()["error"]["trace_id"] == response.headers["x-request-id"]
        assert len(response.headers["x-request-id"]) == 32
        combined = validation.text + response.text + caplog.text
        for value in ("private-email", "sensitive", "private-password", "private-query", "untrusted-private-id"):
            assert value not in combined


def test_body_limit_rejects_before_endpoint():
    with TestClient(create_app()) as client:
        response = client.post("/api/v1/auth/register", content=b"x" * 1_048_577)
        assert response.status_code == 413
        assert response.json()["error"]["code"] == "PAYLOAD_TOO_LARGE"


def test_chunked_body_cannot_bypass_limit():
    async def exercise():
        called = False
        async def endpoint(scope, receive, send):
            nonlocal called
            called = True
        chunks = iter([
            {"type": "http.request", "body": b"1234", "more_body": True},
            {"type": "http.request", "body": b"5678", "more_body": False},
        ])
        async def receive():
            return next(chunks)
        messages = []
        async def send(message):
            messages.append(message)
        await HttpBoundary(endpoint, max_body_bytes=6)({"type": "http", "headers": []}, receive, send)
        assert not called
        assert messages[0]["status"] == 413
    asyncio.run(exercise())


def test_tree_node_limit():
    from tests.helpers import register
    with TestClient(create_app()) as client:
        register(client)
        response = client.post("/api/v1/agent/turn", json={
            "request_id": "large-tree", "session_id": "large-tree", "raw_goal": "increase font size",
            "accessibility_tree": {"package_name": "com.android.settings", "nodes": [{"node_id": "x"}] * 2001},
        })
        assert response.status_code == 422
