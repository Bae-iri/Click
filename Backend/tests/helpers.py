from uuid import uuid4
from fastapi.testclient import TestClient


def register(client, email=None, password="1234"):
    response = client.post("/api/v1/auth/register", json={
        "name": "Test User",
        "phone_number": email or "010" + str(uuid4().int % 100000000).zfill(8), "password": password,
        "age_group": "60_plus", "occupation_category": "retired",
    })
    assert response.status_code == 201, response.text
    tokens = response.json()
    client.headers["Authorization"] = f"Bearer {tokens['access_token']}"
    return tokens


def authenticated_client(app):
    client = TestClient(app)
    register(client)
    return client
