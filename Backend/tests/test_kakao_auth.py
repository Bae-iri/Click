import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.clients.kakao import KakaoTokenVerifier
from app.main import create_app
from app.models.auth import Account, AuthSession, KakaoIdentity
from app.models.user import User


@pytest.fixture
def kakao_app(monkeypatch):
    monkeypatch.setenv("KAKAO_APP_ID", "12345")
    app = create_app()
    app.state.auth_service.kakao = KakaoTokenVerifier(httpx.MockTransport(
        lambda request: httpx.Response(200, json={"id": 987654321, "app_id": 12345, "expires_in": 3600})))
    return app


def test_kakao_signup_login_refresh_and_delete(kakao_app):
    with TestClient(kakao_app) as client:
        first = client.post("/api/v1/auth/kakao", json={"access_token": "kakao-private-token"})
        assert first.status_code == 200
        assert first.headers["cache-control"] == "no-store"
        tokens = first.json()
        second = client.post("/api/v1/auth/kakao", json={"access_token": "another-kakao-token"}).json()
        assert second["user_id"] == tokens["user_id"]
        assert second["access_token"] != tokens["access_token"]
        with kakao_app.state.session_factory() as db:
            assert len(db.scalars(select(User)).all()) == 1
            assert len(db.scalars(select(KakaoIdentity)).all()) == 1
            assert db.scalars(select(Account)).all() == []
            assert len(db.scalars(select(AuthSession)).all()) == 2
        client.headers["Authorization"] = "Bearer " + tokens["access_token"]
        assert client.get("/api/v1/users/me").status_code == 200
        refreshed = client.post("/api/v1/auth/refresh", json={"refresh_token": tokens["refresh_token"]})
        assert refreshed.status_code == 200
        assert client.get("/api/v1/users/me").status_code == 401
        client.headers["Authorization"] = "Bearer " + refreshed.json()["access_token"]
        assert client.delete("/api/v1/users/" + tokens["user_id"]).status_code == 204
        client.headers["Authorization"] = "Bearer " + second["access_token"]
        assert client.get("/api/v1/users/me").status_code == 401
        with kakao_app.state.session_factory() as db:
            assert db.scalars(select(KakaoIdentity)).all() == []
            assert db.scalars(select(AuthSession)).all() == []


def test_email_accounts_keep_working_and_are_not_automatically_linked(kakao_app):
    with TestClient(kakao_app) as client:
        payload = {"email": "person@example.com", "password": "password123"}
        local = client.post("/api/v1/auth/register", json=payload).json()
        social = client.post("/api/v1/auth/kakao", json={"access_token": "valid-kakao-token"}).json()
        assert social["user_id"] != local["user_id"]
        assert client.post("/api/v1/auth/login", json=payload).json()["user_id"] == local["user_id"]
        client.headers["Authorization"] = "Bearer " + social["access_token"]
        assert client.post("/api/v1/auth/password",
                           json={"current_password": "anything", "new_password": "new-password"}).status_code == 401


@pytest.mark.parametrize("payload", [
    {}, {"access_token": ""}, {"access_token": "token\r\nInjected: header"},
    {"access_token": "token", "user_id": "forged"}, {"access_token": "token", "email": "victim@example.com"},
])
def test_kakao_rejects_invalid_requests_without_exposing_credentials(kakao_app, payload):
    with TestClient(kakao_app) as client:
        response = client.post("/api/v1/auth/kakao", json=payload)
        assert response.status_code == 422
        assert "victim@example.com" not in response.text
        assert "Injected" not in response.text


@pytest.mark.parametrize("data,status", [
    ({"id": 1, "app_id": 999, "expires_in": 60}, 401),
    ({"id": 1, "app_id": 12345, "expires_in": 0}, 401),
    ({"id": -1, "app_id": 12345, "expires_in": 60}, 401),
    ({"id": True, "app_id": 12345, "expires_in": 60}, 502),
    ({"id": 1, "app_id": "12345", "expires_in": 60}, 502),
    ({"id": 1}, 502), ([], 502),
])
def test_kakao_rejects_wrong_app_expired_and_malformed_tokens(kakao_app, data, status):
    kakao_app.state.auth_service.kakao = KakaoTokenVerifier(httpx.MockTransport(
        lambda request: httpx.Response(200, json=data)))
    with TestClient(kakao_app) as client:
        assert client.post("/api/v1/auth/kakao", json={"access_token": "private-token"}).status_code == status
        with kakao_app.state.session_factory() as db:
            assert db.scalars(select(User)).all() == []
            assert db.scalars(select(AuthSession)).all() == []


@pytest.mark.parametrize("provider_status,body,status", [
    (401, {"code": -401}, 401), (400, {"code": -1}, 503),
    (429, {}, 503), (500, {}, 503), (302, {}, 502),
])
def test_kakao_provider_failures_are_sanitized(kakao_app, provider_status, body, status):
    kakao_app.state.auth_service.kakao = KakaoTokenVerifier(httpx.MockTransport(
        lambda request: httpx.Response(provider_status, json={**body, "secret": "private-provider-detail"})))
    with TestClient(kakao_app) as client:
        response = client.post("/api/v1/auth/kakao", json={"access_token": "private-token"})
        assert response.status_code == status
        assert "private-" not in response.text


def test_kakao_configuration_and_timeout_fail_closed(monkeypatch):
    monkeypatch.delenv("KAKAO_APP_ID", raising=False)
    verifier = KakaoTokenVerifier(httpx.MockTransport(lambda _: pytest.fail("No unconfigured requests")))
    with pytest.raises(HTTPException) as error:
        verifier.verify("token")
    assert error.value.status_code == 503
    monkeypatch.setenv("KAKAO_APP_ID", "12345")
    def timeout(request):
        assert str(request.url) == "https://kapi.kakao.com/v1/user/access_token_info"
        assert request.headers["Authorization"] == "Bearer token"
        raise httpx.ReadTimeout("provider detail must not escape")
    with pytest.raises(HTTPException) as error:
        KakaoTokenVerifier(httpx.MockTransport(timeout)).verify("token")
    assert error.value.status_code == 504


def test_kakao_tokens_are_not_persisted_or_logged(kakao_app, caplog):
    with TestClient(kakao_app) as client:
        response = client.post("/api/v1/auth/kakao", json={"access_token": "private-kakao-secret"})
        assert response.status_code == 200
        assert "private-kakao-secret" not in response.text + caplog.text
        with kakao_app.state.session_factory() as db:
            session = db.scalar(select(AuthSession))
            assert session.access_hash != response.json()["access_token"]
            assert session.refresh_hash != response.json()["refresh_token"]
