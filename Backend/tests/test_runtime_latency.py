"""Keep full-catalog compatibility work off modern, pinned highlight turns."""
from sqlalchemy import update

from app.clients.checkpoint import graph_version
from app.main import create_app
from app.models.agent_session import AgentCheckpoint
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


def test_pinned_turn_replay_observe_and_pause_do_not_hash_the_whole_catalog(monkeypatch):
    import app.services.agent_turn as service

    def selected_graph_only(graph=None):
        assert graph is not None, "Pinned turns must not serialize the entire graph registry"
        return graph_version(graph)

    monkeypatch.setattr(service, "graph_version", selected_graph_only)
    app = create_app()
    with authenticated_client(app) as client:
        payload = start_payload()
        first = client.post("/api/v1/agent/commands", json=payload)
        assert first.status_code == 200, first.text
        assert client.post("/api/v1/agent/commands", json=payload).json() == first.json()
        observation = {key: value for key, value in payload.items() if key not in {"goal", "command_type"}}
        observation.update(command_type="observe", request_id="latency-observe",
            expected_version=first.json()["session_version"], episode_id=first.json()["episode_id"])
        second = client.post("/api/v1/agent/commands", json=observation)
        assert second.status_code == 200, second.text
        pause = {key: value for key, value in observation.items() if key != "accessibility_tree"}
        pause.update(command_type="pause_task", request_id="latency-pause",
            expected_version=second.json()["session_version"])
        assert client.post("/api/v1/agent/commands", json=pause).status_code == 200


def test_legacy_font_checkpoint_still_accepts_the_bundled_registry_hash():
    app = create_app()
    with authenticated_client(app) as client:
        payload = start_payload()
        first = client.post("/api/v1/agent/commands", json=payload)
        assert first.status_code == 200, first.text
        with app.state.session_factory() as db:
            db.execute(update(AgentCheckpoint).values(graph_version=graph_version()))
            db.commit()
        assert client.post("/api/v1/agent/commands", json=payload).json() == first.json()
        payload.pop("goal")
        payload.update(command_type="observe", request_id="legacy-observe",
            expected_version=first.json()["session_version"], episode_id=first.json()["episode_id"])
        response = client.post("/api/v1/agent/commands", json=payload)
        assert response.status_code == 200, response.text


def test_lifespan_warms_runtime_before_accepting_requests(monkeypatch):
    from fastapi.testclient import TestClient

    warmed = []
    monkeypatch.setattr("app.main.warm_runtime", lambda: warmed.append(True))
    with TestClient(create_app()) as client:
        assert warmed == [True]
        assert client.get("/health").status_code == 200
