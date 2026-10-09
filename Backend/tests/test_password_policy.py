"""Verify the eight-character boundary across every password entry point."""
from fastapi.testclient import TestClient

from app.main import create_app
from tests.test_backend_completion import Delivery


def test_eight_character_password_registration_login_change_and_reset():
    app = create_app()
    app.state.account_actions.delivery = Delivery()
    email = "eight-character@example.com"
    with TestClient(app) as client:
        credentials = {"email": email, "password": "1234567"}
        assert client.post("/api/v1/auth/register", json=credentials).status_code == 422
        assert client.post("/api/v1/auth/login", json=credentials).status_code == 422
        credentials["password"] = "12345678"
        assert client.post("/api/v1/auth/register", json=credentials).status_code == 201
        login = client.post("/api/v1/auth/login", json=credentials)
        assert login.status_code == 200, login.text
        client.headers["Authorization"] = "Bearer " + login.json()["access_token"]
        change = {"current_password": "12345678", "new_password": "abcdefg"}
        assert client.post("/api/v1/auth/password", json=change).status_code == 422
        change["new_password"] = "abcdefgh"
        assert client.post("/api/v1/auth/password", json=change).status_code == 204
        assert client.get("/api/v1/users/me").status_code == 401
        credentials["password"] = change["new_password"]
        assert client.post("/api/v1/auth/login", json=credentials).status_code == 200
        assert client.post("/api/v1/auth/password/forgot", json={"email": email}).status_code == 202
        token = app.state.account_actions.delivery.messages[-1][2]
        reset = {"token": token, "new_password": "ABCDEFG"}
        assert client.post("/api/v1/auth/password/reset", json=reset).status_code == 422
        reset["new_password"] = "ABCDEFGH"
        assert client.post("/api/v1/auth/password/reset", json=reset).status_code == 204
        credentials["password"] = reset["new_password"]
        assert client.post("/api/v1/auth/login", json=credentials).status_code == 200
