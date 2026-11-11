"""Replay JSON emitted by ClickOverlayServiceExecutionTest at the authenticated API.

Run after :app:testDebugUnitTest. Only server-issued session/resolution/confirmation
identifiers and versions are rebound; every other field comes from Android.
"""
from copy import deepcopy
import json
import os
from pathlib import Path

import pytest

from app.main import create_app
from app.schemas.agent_turn import AgentCommandRequest
from tests.helpers import authenticated_client
from tests.test_task_outcomes import Flow
from tests.test_vision_consent import challenge

PATH = "/api/v1/agent/commands"
DEFAULT_REQUESTS = Path(__file__).resolve().parents[2] / "Frontend/app/build/contract/testDebugUnitTest"


def frontend_request(name):
    directory = Path(os.environ.get("CLICK_FRONTEND_CONTRACT_DIR", DEFAULT_REQUESTS))
    path = directory / f"{name}.json"
    assert path.is_file(), f"Missing Android-generated request {path}; run :app:testDebugUnitTest first"
    request = json.loads(path.read_text(encoding="utf-8"))
    AgentCommandRequest.model_validate(request)
    return request


def rebind_execution(request):
    # These tests rebind only server-issued session/episode/version identifiers.
    context=request.get('execution_context')
    if context is not None:
        context.update(session_id=request['session_id'],episode_id=request.get('episode_id'),
                       session_version=request['expected_version'])
    return request


def test_confirmed_android_preparation_starts_and_observes_same_episode(monkeypatch):
    monkeypatch.delenv("SELF_HOSTED_LLM_MODEL", raising=False)
    start = frontend_request("prepared-start")
    assert start["command_type"] == "start_task"
    assert start["preparation_version"] == "goal-inputs.v1"
    assert start["workflow_version"] == "interactive.v1"
    assert start["goal_resolution_id"] == "prepared-resolution"
    assert start["expected_version"] == 0
    flow = Flow()
    try:
        prepared = flow.prepare(start["source_text"])
        assert start["goal"] == prepared["goal"]
        start.update(session_id=flow.session, goal_resolution_id=prepared["preparation"]["id"])
        response = flow.client.post(PATH, json=rebind_execution(start))
        assert response.status_code == 200, response.text
        started = response.json()
        assert started["episode_id"]
        assert started["task_status"] == "IN_PROGRESS"
        assert started["guide_command"]["target_node_id"] == "display"
        assert flow.client.post(PATH, json=rebind_execution(start)).json() == started

        observe = frontend_request("prepared-observe")
        assert observe["command_type"] == "observe"
        observe.update(session_id=flow.session, episode_id=started["episode_id"],
                       expected_version=started["session_version"])
        response = flow.client.post(PATH, json=rebind_execution(observe))
        assert response.status_code == 200, response.text
        assert response.json()["episode_id"] == started["episode_id"]
    finally:
        flow.client.close()
        flow.app.state.engine.dispose()


@pytest.mark.parametrize("failure", ["capture_failed", "masking_failed", "ocr_failed", "screen_changed", "unsupported"])
def test_android_vision_failure_reaches_backend_without_422(failure):
    request = frontend_request(f"vision-{failure}")
    assert request["vision_failure"] == failure
    assert request["user_confirmation"] == "rejected"
    assert "vision_snapshot" not in request
    app = create_app()
    with authenticated_client(app) as client:
        # Issue consent for the same screen that Android serialized.
        payload, pending = challenge(client, nodes=deepcopy(request["accessibility_tree"]["nodes"]))
        request.update(session_id=payload["session_id"], episode_id=pending["episode_id"],
                       expected_version=pending["session_version"],
                       confirmation_id=pending["metadata"]["confirmation"]["id"])
        response = client.post(PATH, json=rebind_execution(request))
        assert response.status_code == 200, response.text
        assert response.json()["metadata"]["vision_failure"] == failure
        assert response.json()["guide_command"]["target_node_id"] is None
    app.state.engine.dispose()
