import pytest

from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_agent_turn_api import slider_tree
from tests.test_typed_agent_contract import start_payload


@pytest.mark.parametrize("goal_direction,render_direction", [("increase", "right"), ("decrease", "left")])
def test_db_graph_direction_survives_approval_checkpoint_and_http_response(goal_direction, render_direction):
    client = authenticated_client(create_app())
    start = start_payload()
    start["goal"].update(catalog_version="1.1.0", goal_type=f"settings.font_size.{goal_direction}")
    start["goal"]["slots"]["direction"] = goal_direction
    start["accessibility_tree"] = slider_tree(3)
    response = client.post("/api/v1/agent/commands", json=start)
    assert response.status_code == 200, response.text
    first = response.json()
    assert first["guide_command"]["command_type"] == "confirmation"
    assert "direction" not in first["guide_command"]["render_policy"]

    approved = {"contract_version": start["contract_version"], "request_id": "direction-approve",
        "session_id": start["session_id"], "expected_version": first["session_version"],
        "episode_id": first["episode_id"], "command_type": "respond_confirmation",
        "user_confirmation": "accepted", "confirmation_id": first["metadata"]["confirmation"]["id"],
        "accessibility_tree": start["accessibility_tree"]}
    response = client.post("/api/v1/agent/commands", json=approved)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["graph_reference"] == first["graph_reference"]
    assert body["guide_command"]["action_type"] == "drag"
    assert body["guide_command"]["render_policy"]["direction"] == render_direction
    assert ("오른쪽" if render_direction == "right" else "왼쪽") in body["guide_command"]["message"]

    observed = {key: value for key, value in approved.items()
        if key not in {"user_confirmation", "confirmation_id"}}
    observed.update(request_id="direction-observe", command_type="observe", expected_version=body["session_version"])
    observed["accessibility_tree"] = {**start["accessibility_tree"],
        "observation_id": "after-approval", "captured_at": "2026-08-30T10:00:01Z"}
    response = client.post("/api/v1/agent/commands", json=observed)
    assert response.status_code == 200, response.text
    guide = response.json()["guide_command"]
    assert guide["render_policy"].get("direction") == render_direction, guide
