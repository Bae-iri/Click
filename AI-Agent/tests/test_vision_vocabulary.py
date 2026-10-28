from dataclasses import replace
import json
from pathlib import Path

from ai_agent.runtime.privacy import allowed_vision_label, sanitize_request
from ai_agent.runtime.vision_vocabulary import GROUPS
from ai_agent.runtime.engine import ClickAgentEngine
from ai_agent.runtime.types import VisionElement
from test_vision_fallback import request


def test_catalog_settings_have_reviewed_vocabulary_without_approving_graphs():
    catalog = json.loads((Path(__file__).resolve().parents[1] / "goal_catalog"
                          / "mvp_goals.json").read_text())
    settings = {goal["slots"]["setting"] for goal in catalog["goals"]
                if goal["target_app"] == "android_settings"}
    assert settings <= GROUPS.keys()
    wifi = next(g for g in catalog["goals"] if g["goal_type"] == "settings.wifi.enable")
    assert wifi["execution_status"] == "catalog_only"


def test_goal_scoped_vocabulary_drops_other_families_and_dynamic_labels():
    assert allowed_vision_label(" Wi-Fi ", "wifi") == "wi-fi"
    assert allowed_vision_label("Bluetooth", "bluetooth") == "bluetooth"
    assert allowed_vision_label("밝기", "brightness") == "밝기"
    assert allowed_vision_label("글자 크기", "wifi") is None
    assert allowed_vision_label("Wi-Fi", "font_size") is None
    assert allowed_vision_label("설정", "unknown") is None
    for key in GROUPS:
        for label in ("김철수", "Home Wi-Fi", "김철수의 이어폰", "디스플레이 김철수", "user@example.com"):
            assert allowed_vision_label(label, key) is None


def test_runtime_filters_to_active_graph_even_when_other_labels_are_globally_approved():
    payload = request()
    payload = replace(payload, vision_snapshot=replace(payload.vision_snapshot,
        elements=payload.vision_snapshot.elements + (
            VisionElement("wifi", "Wi-Fi", (20, 400, 900, 500), confidence=0.95),)))
    scoped = sanitize_request(payload, vision_vocabulary_key="font_size")
    assert [e.text for e in scoped.vision_snapshot.elements] == ["디스플레이", "설정"]
    response = ClickAgentEngine().handle_turn(payload)
    assert response.debug["observation"]["vision_element_count"] == 2
    assert response.guide_command.target_node_id == "vision:ocr_0"


def test_empty_tree_consent_includes_active_graph_vocabulary():
    response = ClickAgentEngine().handle_turn(replace(request(), vision_snapshot=None))
    assert response.guide_command.render_policy["vision_fallback"]["vocabulary_key"] == "font_size"
