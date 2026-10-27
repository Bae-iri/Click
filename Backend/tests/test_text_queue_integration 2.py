"""Text input shares the queue and must initialize a typed execution episode."""
from uuid import uuid4

from fastapi.testclient import TestClient

from app.main import create_app
from tests.helpers import register
from tests.test_typed_agent_contract import start_payload


def test_logged_in_text_queue_starts_font_guidance_and_accepts_followup():
    with TestClient(create_app()) as client:
        email = f"text-{uuid4().hex}@example.com"
        register(client, email=email)
        login = client.post("/api/v1/auth/login", json={
            "email": email, "password": "test-password-1234",
        })
        assert login.status_code == 200, login.text
        client.headers["Authorization"] = f"Bearer {login.json()['access_token']}"
        queued = client.post("/api/v1/voice-requests/enqueue", json={
            "id": str(uuid4()), "raw_goal": "글자크기 키우고싶어", "priority": "NORMAL",
        })
        assert queued.status_code == 200, queued.text
        claimed = client.post("/api/v1/voice-requests/next/claim")
        assert claimed.status_code == 200, claimed.text
        running = claimed.json()["running"]
        assert claimed.json()["claimed"]
        assert running["raw_goal"] == "글자크기 키우고싶어"

        # The current FE contract supplies the confirmed typed goal to start_task.
        payload = start_payload()
        payload.update(session_id=running["agent_session_id"], source_text=running["raw_goal"])
        started = client.post("/api/v1/agent/commands", json=payload)
        assert started.status_code == 200, started.text
        body = started.json()
        assert body["accepted_goal_type"] == "settings.font_size.increase"
        assert body["episode_id"]
        assert body["guide_command"]["message"]
        assert body["task_id"] == "MVP-01"
        assert client.post("/api/v1/agent/commands", json=payload).json() == body

        paused = client.post("/api/v1/agent/commands", json={
            "contract_version": payload["contract_version"], "request_id": "text-pause",
            "session_id": payload["session_id"], "expected_version": body["session_version"],
            "episode_id": body["episode_id"], "command_type": "pause_task",
        })
        assert paused.status_code == 200, paused.text
        assert paused.json()["task_status"] == "PAUSED"
