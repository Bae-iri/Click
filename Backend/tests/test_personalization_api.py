"""Accounts and runtime evidence remain usable after user modeling is removed."""
from fastapi.testclient import TestClient
from sqlalchemy import inspect
from app.main import create_app
from tests.helpers import register
from tests.test_agent_sessions import payload, post
import pytest


def test_profiles_are_absent_and_legacy_routes_do_not_collect():
    app = create_app()
    with TestClient(app) as client:
        auth = register(client)
        base = "/api/v1/users/" + auth["user_id"]
        assert "profile" not in client.get(base).json()
        assert client.patch(base + "/profile", json={"comprehension_level": "beginner"}).status_code == 410
        assert client.post(base + "/events", json={"event_type": "guide_repeated"}).status_code == 410
        assert client.post(base + "/consents", json={"purpose": "personalization", "consented": True,
            "policy_version": "v1"}).status_code == 422
        tables = inspect(app.state.engine).get_table_names()
        assert "user_ui_profiles" not in tables and "ui_events" not in tables
        assert "runtime_events" in tables and "approved_task_graphs" in tables


def test_shared_guidance_and_cached_response_have_no_profile():
    with TestClient(create_app()) as client:
        auth = register(client)
        first = post(client, payload())
        assert first.status_code == 200, first.text
        repeated = post(client, payload())
        assert repeated.status_code == 200
        assert repeated.json()["session_version"] == first.json()["session_version"]
        assert "ui_profile" not in repeated.json()["metadata"]
        assert client.post(f"/api/v1/users/{auth['user_id']}/consents", json={"purpose": "model_training",
            "consented": True, "policy_version": "graph-improvement.v1"}).status_code == 201


def test_old_cached_profile_metadata_is_stripped():
    from app.clients.checkpoint import safe_response
    from app.schemas.agent_turn import AgentTurnResponse
    with TestClient(create_app()) as client:
        register(client)
        response = AgentTurnResponse.model_validate(post(client, payload()).json())
        response.metadata["ui_profile"] = {"comprehension_level": "beginner"}
        assert "ui_profile" not in safe_response(response).metadata


@pytest.mark.parametrize('legacy_fields', [{}, {'age_group':'60_plus','occupation_category':'retired'}])
def test_registration_keeps_account_identity_and_discards_legacy_demographics(legacy_fields):
    app = create_app()
    with TestClient(app) as client:
        response = client.post('/api/v1/auth/register', json={'email':'account@example.com',
            'password':'test-password-1234','name':'Account display',**legacy_fields})
        assert response.status_code == 201, response.text
        client.headers['Authorization'] = 'Bearer ' + response.json()['access_token']
        user = client.get('/api/v1/users/me').json()
        assert set(user) == {'id','name','created_at'}
        assert user['name'] == 'Account display'
        properties = client.get('/openapi.json').json()['components']['schemas']['RegisterRequest']['properties']
        assert 'age_group' not in properties and 'occupation_category' not in properties
        from app.schemas.auth import RegisterRequest
        parsed = RegisterRequest(email='account@example.com',password='test-password-1234',**legacy_fields)
        assert 'age_group' not in parsed.model_dump() and 'occupation_category' not in parsed.model_dump()
        assert client.post('/api/v1/auth/login',json={'email':'account@example.com',
            'password':'test-password-1234'}).status_code == 200
