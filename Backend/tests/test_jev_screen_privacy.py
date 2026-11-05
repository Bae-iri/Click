"""Inspect actual outbound Jev payloads and exercise privacy handoff through the runtime."""
from dataclasses import replace
import json
from unittest.mock import Mock

import pytest

from app.services.screen_jev import JevScreenResolver
from ai_agent.runtime import ClickAgentEngine, CommandType
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.screen_privacy import filter_nodes
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext, NormalizedNode, VerificationResult, VerificationStatus
from ai_agent.telemetry.trace_logger import diagnostic_payload
from tests.test_screen_jev import scene, sender_for


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")


def raw(node_id, text, **kwargs):
    return AccessibilityNode(node_id=node_id, text=text, package_name="com.android.settings",
        bounds_in_screen=(10, 100, 300, 150), **kwargs)


def test_only_approved_menu_identity_and_numeric_values_leave_backend():
    graph, transition, node, goal = scene("font_size")
    secrets = ("김민수", "owner@example.com", "설정 비밀 문구", "삼성 계정 설명", "private-result", "private-event")
    nodes = (
        raw("title", "설정"),
        raw("menu", "디스플레이", clickable=True, content_description="삼성 계정 설명"),
        *(raw(f"private{i}", text) for i, text in enumerate(secrets[:4])),
        raw("summary", "글자 크기", resource_id="android:id/summary"),
        raw(node.node_id, "글자 크기", role="slider", class_name="android.widget.SeekBar",
            extras={"value": 3, "min": 0, "max": 10}),
    )
    sent = []
    def send(payload, **kwargs):
        sent.append(payload)
        return sender_for(transition)(payload, **kwargs)
    resolver = JevScreenResolver(send)
    result = resolver.resolve(nodes=normalize_accessibility_tree(filter_nodes(nodes, "font_size")),
        raw_nodes=nodes, graph=graph, goal=replace(goal, source_text="private-source"),
        attempt={"transition_id": transition.transition_id, "baseline_value": 2, "event_key": "private-event"},
        verification=VerificationResult(VerificationStatus.UNKNOWN, transition.from_state, evidence="private-result"))
    assert result.target_node_id == node.node_id
    encoded = json.dumps(sent, ensure_ascii=False)
    for secret in (*secrets, "private-source", "guide_message", "confirmation_message", "resource_id", "package_name"):
        assert secret not in encoded
    assert sent[0]["state"]["previous_action"]["event_received"] is True
    assert sent[0]["state"]["result"] == {"status": "UNKNOWN"}
    assert "설정" in encoded and "디스플레이" in encoded and "글자 크기" in encoded
    safe = diagnostic_payload({"ui_selection": {**resolver.last_diagnostics, "text": "private-source"}})["ui_selection"]
    assert safe["payload_bytes"] > 0 and safe["filter_time_ms"] >= 0 and safe["provider_latency_ms"] >= 0
    assert "private-source" not in str(safe)


@pytest.mark.parametrize("node", [
    raw("input", "김민수", class_name="android.widget.EditText"),
    raw("pin", "1234", role="text_input"),
    raw("title", "삼성 계정"),
    raw("title", "알림 기록"),
    raw("unknown", "제조사의 새로운 메뉴"),
    replace(raw("foreign", "글자 크기"), package_name="com.bank.app"),
])
def test_sensitive_and_unsupported_screens_hand_off_without_provider_call(node):
    graph, _, _, goal = scene("font_size")
    sender = Mock(side_effect=AssertionError("privacy gate must run before network"))
    resolver = JevScreenResolver(sender)
    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=resolver)
    result = engine.handle_turn(AgentTurnRequest("r", "s", None, (node,), typed_goal=goal,
        device_context=DeviceContext("android")))
    assert result.guide_command.command_type == CommandType.BLOCKED
    assert result.guide_command.render_policy["manual_only"] is True
    assert result.guide_command.target_node_id is None
    assert "직접 진행" in result.guide_command.message
    sender.assert_not_called()


def test_unknown_slider_text_is_dropped_but_approved_page_can_supply_context():
    graph, transition, node, goal = scene("font_size")
    slider = replace(node, label="삼성 계정 설명 김민수", label_parts=("owner@example.com",))
    title = NormalizedNode("title", "글자 크기", "text", package_name=node.package_name,
        structure_role="screen_text")
    sent = []
    def send(payload, **kwargs):
        sent.append(payload)
        return sender_for(transition)(payload, **kwargs)
    assert JevScreenResolver(send).resolve(nodes=(slider, title), graph=graph, goal=goal).target_node_id == node.node_id
    assert all(n["label"] in {"", "글자 크기"} for n in sent[0]["state"]["nodes"])
    assert "owner@example.com" not in str(sent)
    forbidden = Mock()
    resolver = JevScreenResolver(forbidden)
    assert resolver.resolve(nodes=(slider,), graph=graph, goal=goal) is None
    assert resolver.last_diagnostics["status"] == "unsupported_screen"
    forbidden.assert_not_called()


def test_unknown_menu_has_no_passthrough_exception_and_other_task_labels_are_removed():
    graph, _, node, goal = scene("font_size")
    menus = [replace(node, node_id="unknown", role="menu_item", label="디스플레이 김민수"),
             replace(node, node_id="wifi", role="menu_item", label="wi fi")]
    seen = []
    def send(payload, **kwargs):
        seen.append(payload)
        return sender_for(graph.transitions[0], "insufficient_evidence")(payload, **kwargs)
    JevScreenResolver(send).resolve(nodes=(node, *menus), graph=graph, goal=goal)
    assert all(n["label"] != "wi fi" and "김민수" not in n["label"] for n in seen[0]["state"]["nodes"])


def test_account_and_preview_fields_are_removed_even_when_they_match_allowed_titles():
    nodes = (raw("title", "설정"), raw("account", "디스플레이", resource_id="com.android.settings:id/account_name"),
             raw("preview", "글자 크기", resource_id="com.android.settings:id/preview_text"))
    filtered = filter_nodes(nodes, "font_size")
    assert filtered[0].text == "설정"
    assert filtered[1].text is None and filtered[2].text is None


@pytest.mark.parametrize("sensitive", [False, True])
def test_http_response_checkpoint_and_turn_record_never_store_screen_names(monkeypatch, sensitive):
    from sqlalchemy import select
    from app.main import create_app
    from app.models.agent_session import AgentCheckpoint, AgentTurn
    from tests.helpers import authenticated_client
    from tests.test_typed_agent_contract import start_payload

    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    calls = []
    graph, _, _, _ = scene("font_size")
    entry = next(t for t in graph.transitions if t.from_state == "S1_SETTINGS_HOME")
    def post(self, payload, **kwargs):
        calls.append(payload)
        return sender_for(entry)(payload, **kwargs)
    monkeypatch.setattr(JevScreenResolver, "_post", post)
    app = create_app()
    with authenticated_client(app) as client:
        payload = start_payload()
        payload["accessibility_tree"]["nodes"].extend([
            raw("private-name", "김민수").to_dict(), raw("private-email", "owner@example.com").to_dict()])
        if sensitive:
            payload["accessibility_tree"]["nodes"].append(raw("password", "private-input",
                class_name="android.widget.EditText").to_dict())
        response = client.post("/api/v1/agent/commands", json=payload)
        assert response.status_code == 200, response.text
        assert len(calls) == (0 if sensitive else 1)
        if sensitive:
            assert response.json()["guide_command"]["render_policy"]["manual_only"] is True
        with app.state.session_factory() as db:
            records = [row.response for row in db.scalars(select(AgentTurn))]
            checkpoints = [row.state for row in db.scalars(select(AgentCheckpoint))]
        encoded = response.text + json.dumps([calls, records, checkpoints], ensure_ascii=False)
        for secret in ("김민수", "owner@example.com", "private-input"):
            assert secret not in encoded
