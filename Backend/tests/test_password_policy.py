"""Verify the four-digit boundary across every password entry point."""
from fastapi.testclient import TestClient

from app.main import create_app
from tests.test_backend_completion import Delivery
from app.models.auth import Account


def test_four_digit_password_registration_login_change_and_reset():
    app = create_app()
    app.state.account_actions.delivery = Delivery()
    email = "01088966910"
    with TestClient(app) as client:
        credentials = {"phone_number": email, "password": "123"}
        assert client.post("/api/v1/auth/register", json={**credentials, "name": "Test User"}).status_code == 422
        assert client.post("/api/v1/auth/login", json=credentials).status_code == 422
        credentials["password"] = "0123"
        assert client.post("/api/v1/auth/register", json={**credentials, "name": "Test User"}).status_code == 201
        login = client.post("/api/v1/auth/login", json=credentials)
        assert login.status_code == 200, login.text
        client.headers["Authorization"] = "Bearer " + login.json()["access_token"]
        change = {"current_password": "0123", "new_password": "abcdefg"}
        assert client.post("/api/v1/auth/password", json=change).status_code == 422
        change["new_password"] = "5678"
        assert client.post("/api/v1/auth/password", json=change).status_code == 204
        assert client.get("/api/v1/users/me").status_code == 401
        credentials["password"] = change["new_password"]
        assert client.post("/api/v1/auth/login", json=credentials).status_code == 200
        with app.state.session_factory() as db:
            from sqlalchemy import select
            account = db.scalar(select(Account))
            account.email = "reset@example.com"
            db.commit()
        assert client.post("/api/v1/auth/password/forgot", json={"email": "reset@example.com"}).status_code == 202
        token = app.state.account_actions.delivery.messages[-1][2]
        reset = {"token": token, "new_password": "ABCDEFG"}
        assert client.post("/api/v1/auth/password/reset", json=reset).status_code == 422
        reset["new_password"] = "9012"
        assert client.post("/api/v1/auth/password/reset", json=reset).status_code == 204
        credentials["password"] = reset["new_password"]
        assert client.post("/api/v1/auth/login", json=credentials).status_code == 200
