"""Remaining settings contracts; synthetic graphs test dispatch, not device execution."""
import json
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock

import pytest
from sqlalchemy import select

from app.main import create_app
from app.schemas.agent_turn import AgentCommandRequest, TypedGoalRequest
from app.repositories import task_graphs
from app.models.task_graph import ApprovedTaskGraph
from app.clients.runtime_agent import RuntimeAgentAdapter
from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload

CASES = [('settings.magnification.enable', {'setting': 'magnification', 'state': 'on'}),
 ('settings.magnification.disable', {'setting': 'magnification', 'state': 'off'}),
 ('settings.language.change', {'setting': 'language', 'action': 'change'}),
 ('settings.date_time.automatic.enable', {'setting': 'automatic_date_time', 'state': 'on'}),
 ('settings.date_time.automatic.disable', {'setting': 'automatic_date_time', 'state': 'off'}),
 ('settings.keyboard.change', {'setting': 'keyboard', 'action': 'change'}),
 ('settings.default_apps.change', {'setting': 'default_apps', 'action': 'change'}),
 ('settings.wifi.share', {'setting': 'wifi', 'action': 'share'}),
 ('settings.wifi.saved_networks.view', {'setting': 'wifi_saved_networks', 'action': 'view'}),
 ('settings.nfc.enable', {'setting': 'nfc', 'state': 'on'}),
 ('settings.nfc.disable', {'setting': 'nfc', 'state': 'off'}),
 ('settings.do_not_disturb.enable', {'setting': 'do_not_disturb', 'state': 'on'}),
 ('settings.do_not_disturb.disable', {'setting': 'do_not_disturb', 'state': 'off'}),
 ('settings.auto_rotate.enable', {'setting': 'auto_rotate', 'state': 'on'}),
 ('settings.auto_rotate.disable', {'setting': 'auto_rotate', 'state': 'off'}),
 ('settings.eye_comfort.enable', {'setting': 'eye_comfort', 'state': 'on'}),
 ('settings.eye_comfort.disable', {'setting': 'eye_comfort', 'state': 'off'}),
 ('settings.adaptive_brightness.enable', {'setting': 'adaptive_brightness', 'state': 'on'}),
 ('settings.adaptive_brightness.disable', {'setting': 'adaptive_brightness', 'state': 'off'}),
 ('settings.data_saver.enable', {'setting': 'data_saver', 'state': 'on'}),
 ('settings.data_saver.disable', {'setting': 'data_saver', 'state': 'off'}),
 ('settings.data_roaming.enable', {'setting': 'data_roaming', 'state': 'on'}),
 ('settings.data_roaming.disable', {'setting': 'data_roaming', 'state': 'off'}),
 ('settings.vpn.connect', {'setting': 'vpn', 'action': 'connect'}),
 ('settings.vpn.disconnect', {'setting': 'vpn', 'action': 'disconnect'}),
 ('settings.private_dns.change', {'setting': 'private_dns', 'action': 'change'}),
 ('settings.sim.manage', {'setting': 'sim', 'action': 'manage'}),
 ('settings.hotspot.password.change', {'setting': 'hotspot_password', 'action': 'change'}),
 ('settings.wallpaper.change', {'setting': 'wallpaper', 'action': 'change'}),
 ('settings.notification_sound.change', {'setting': 'notification_sound', 'action': 'change'}),
 ('settings.battery_percentage.enable', {'setting': 'battery_percentage', 'state': 'on'}),
 ('settings.battery_percentage.disable', {'setting': 'battery_percentage', 'state': 'off'}),
 ('settings.battery_status.view', {'setting': 'battery_status', 'action': 'view'}),
 ('settings.app_cache.clear', {'setting': 'app_cache', 'action': 'clear'}),
 ('settings.app_data.clear', {'setting': 'app_data', 'action': 'clear'}),
 ('settings.notification_history.view', {'setting': 'notification_history', 'action': 'view'}),
 ('settings.lockscreen_notifications.manage',
  {'setting': 'lockscreen_notifications', 'action': 'manage'}),
 ('settings.color_inversion.enable', {'setting': 'color_inversion', 'state': 'on'}),
 ('settings.color_inversion.disable', {'setting': 'color_inversion', 'state': 'off'}),
 ('settings.captions.enable', {'setting': 'captions', 'state': 'on'}),
 ('settings.captions.disable', {'setting': 'captions', 'state': 'off'}),
 ('settings.mono_audio.enable', {'setting': 'mono_audio', 'state': 'on'}),
 ('settings.mono_audio.disable', {'setting': 'mono_audio', 'state': 'off'}),
 ('settings.backup.enable', {'setting': 'backup', 'state': 'on'}),
 ('settings.backup.disable', {'setting': 'backup', 'state': 'off'}),
 ('settings.software_update.check', {'setting': 'software_update', 'action': 'check'}),
 ('settings.factory_reset', {'setting': 'factory_reset', 'action': 'reset'}),
 ('settings.device_name.change', {'setting': 'device_name', 'action': 'change'})]


def payload_for(goal_type, slots, version="2.1.0"):
    payload = start_payload()
    payload["goal"].update(goal_type=goal_type, slots=slots, catalog_version=version)
    return payload


@pytest.fixture(scope="module")
def service_client():
    app = create_app()
    return app, authenticated_client(app)


@pytest.mark.parametrize("goal_type,slots", CASES)
def test_versions_slots_and_catalog_coverage(goal_type, slots):
    for version in ("2.0.0", "2.1.0"):
        goal = TypedGoalRequest.model_validate(payload_for(goal_type, slots, version)["goal"])
        assert goal.slots == slots
    for version in ("1.0.0", "1.1.0"):
        with pytest.raises(ValueError, match="2.0.0 or newer"):
            TypedGoalRequest.model_validate(payload_for(goal_type, slots, version)["goal"])
    invalid_slots = [{}, {**slots, "setting": "invalid"}]
    for key in slots:
        invalid_slots.extend([{k: v for k, v in slots.items() if k != key}, {**slots, key: "invalid"}])
    for extra in ("password", "pin", "hostname", "device_name", "package_name", "file_path", "reset_confirmed"):
        invalid_slots.append({**slots, extra: "not-allowed"})
    for invalid in invalid_slots:
        with pytest.raises(ValueError):
            TypedGoalRequest.model_validate(payload_for(goal_type, invalid)["goal"])


def test_exact_remaining_range_matches_shared_catalog():
    catalog = json.loads((Path(__file__).parents[2] / "AI-Agent/goal_catalog/mvp_goals.json").read_text(encoding="utf-8"))
    settings = [g for g in catalog["goals"] if g["goal_type"].startswith("settings.")
                and g.get("task_type") != "settings_navigation"]
    start = next(i for i, g in enumerate(settings) if g["goal_type"] == "settings.magnification.enable")
    assert [(g["goal_type"], g["slots"]) for g in settings[start:]] == CASES
    assert len(CASES) == 48
    assert CASES[-1][0] == "settings.device_name.change"
    for entry in settings[start:]:
        assert entry["target_app"] == "android_settings"
        assert entry["execution_status"] == "approved"


@pytest.mark.parametrize("goal_type,slots", CASES)
def test_http_validation_and_missing_graph_never_call_agent(service_client, monkeypatch, goal_type, slots):
    app, client = service_client
    # Explicitly simulate a missing/deactivated graph rather than relying on
    # the production catalog to remain unimplemented.
    with app.state.session_factory() as db:
        for row in db.scalars(select(ApprovedTaskGraph).where(ApprovedTaskGraph.goal_type == goal_type)):
            row.active = False
        db.commit()
    engine = Mock(side_effect=AssertionError("No graph must not call Agent 1"))
    monkeypatch.setattr(app.state.turn_service, "engine_factory", engine)
    payload = payload_for(goal_type, slots)
    response = client.post("/api/v1/agent/commands", json=payload)
    assert response.status_code == 422, response.text
    assert response.json()["error"]["code"] == "INVALID_REQUEST"
    engine.assert_not_called()
    handler = Mock(side_effect=AssertionError("Invalid input must not reach service"))
    monkeypatch.setattr(app.state.turn_service, "handle_command", handler)
    payload["goal"]["slots"] = {**slots, "unexpected": "value"}
    assert client.post("/api/v1/agent/commands", json=payload).status_code == 422
    handler.assert_not_called()


@pytest.mark.parametrize("goal_type,slots", CASES)
def test_registered_graph_dispatch_boundary(service_client, monkeypatch, goal_type, slots):
    app, _ = service_client
    graph = replace(MVP_01_TASK_GRAPH, definition=replace(MVP_01_TASK_GRAPH.definition,
        task_id="TEST-" + goal_type, goal_type=goal_type, required_slots=slots))
    monkeypatch.setattr(task_graphs, "releases", lambda: [
        ({"version": "1.0.0", "approved": True, "priority": 100}, graph)])
    repository = app.state.turn_service.graphs
    repository.seed_mvp()
    repository.seed_mvp()
    request = AgentCommandRequest.model_validate(payload_for(goal_type, slots))
    selected, reference = repository.select(request.goal)
    assert selected == graph
    with repository.factory() as db:
        rows = list(db.scalars(select(ApprovedTaskGraph).where(
            ApprovedTaskGraph.task_id == graph.definition.task_id)))
        assert len(rows) == 1
        assert rows[0].graph_hash == reference["sha256"]
    adapter = RuntimeAgentAdapter(task_graph=selected)
    adapter.runtime.handle_turn = Mock(side_effect=RuntimeError("Agent 1 boundary"))
    with pytest.raises(RuntimeError, match="Agent 1 boundary"):
        adapter.handle_turn(request)
    forwarded = adapter.runtime.handle_turn.call_args.args[0].typed_goal
    assert forwarded.goal_type == goal_type
    assert forwarded.slots == slots
    assert forwarded.task_id == graph.definition.task_id
