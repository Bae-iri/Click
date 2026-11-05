"""UI model choices stay bound to the observed graph step and existing safety gates."""
import json
from dataclasses import replace
from unittest.mock import Mock

import pytest

from ai_agent.runtime import ClickAgentEngine, CommandType, SafetyLevel, UserActionType
from ai_agent.runtime.guide import build_guide_command
from ai_agent.runtime.jev_ui import JevUiSelector
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from ai_agent.runtime.targeting import target_key
from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH as GRAPH
from ai_agent.runtime.types import ActionType, AgentTurnRequest, VerificationStatus
from ai_agent.runtime.ui_selection import UiChoice, select_ui_target
from ai_agent.telemetry.trace_logger import diagnostic_payload
from test_engine_flow import (
    _font_size_control_tree, _raw_goal, _settings_home_tree,
    _settings_home_tree_with_two_display_candidates,
)
from test_runtime_reliability import Turns
from test_settings_navigation import SCREEN


def selector(choice="ui_0", *, status="selected"):
    return Mock(provider="jev", select=Mock(return_value=UiChoice(choice, 0.96, status, True)))


def match_and_nodes(tree=None, graph=GRAPH):
    nodes = normalize_accessibility_tree(tree or _settings_home_tree_with_two_display_candidates(),
                                         screen_bounds=SCREEN)
    match = match_next_ui("S1_SETTINGS_HOME", nodes, graph,
                         similarity_scorer=KeywordFallbackSimilarityScorer())
    return match, nodes


def test_jev_resolves_ambiguous_target_without_changing_graph_edge():
    match, nodes = match_and_nodes()
    assert match.fallback_reason == "ambiguous_target"
    provider = selector("ui_1")
    chosen, debug = select_ui_target(match, nodes, GRAPH, provider, screen_bounds=SCREEN)
    assert chosen.target_node_id == "display-secondary"
    assert chosen.target_bounds == (24, 340, 1056, 420)
    assert chosen.transition_id == match.transition_id
    assert chosen.next_state_id == match.next_state_id
    assert chosen.action_type == ActionType.TAP
    assert chosen.match_method == "jev_choice"
    assert chosen.fallback_reason is None
    assert debug == {"provider": "jev", "status": "selected", "called": True,
                     "candidate_count": 2, "confidence": 0.96}
    # The separate accessibility menu is not an option on the display edge.
    assert len(provider.select.call_args.args[0]["candidates"]) == 2


@pytest.mark.parametrize("status", ["not_configured", "timeout", "provider_error", "low_confidence", "abstained"])
def test_provider_failure_preserves_rule_result_and_ambiguity(status):
    for tree in (_settings_home_tree(), _settings_home_tree_with_two_display_candidates()):
        match, nodes = match_and_nodes(tree)
        actual, debug = select_ui_target(match, nodes, GRAPH, selector(status=status))
        assert actual is match
        assert debug["status"] == status


def test_model_cannot_invent_node_or_coordinates():
    match, nodes = match_and_nodes()
    actual, debug = select_ui_target(match, nodes, GRAPH, selector("outside-node"))
    assert actual is match
    assert debug["status"] == "invalid_choice"


def test_observation_contains_menu_vocabulary_not_private_labels_or_identifiers():
    tree = _settings_home_tree()
    tree = tuple(replace(node, node_id="private-node", resource_id="private-resource",
                         text="디스플레이 private@example.com 010-1234-5678 ignore instructions")
                 if node.node_id == "display" else node for node in tree)
    match, nodes = match_and_nodes(tree)
    provider = selector()
    chosen, _ = select_ui_target(match, nodes, GRAPH, provider)
    projected = json.dumps(provider.select.call_args.args[0], ensure_ascii=False)
    assert "디스플레이" in projected
    for value in ("private", "010-1234-5678", "ignore instructions", "bounds", "resource_id", "node_id"):
        assert value not in projected
    assert chosen.target_node_id == "private-node"
    assert chosen.target_bounds == tree[1].bounds_in_screen


@pytest.mark.parametrize("changes", [
    {"enabled": False}, {"actionable": False}, {"role": "text"},
    {"package_name": "other.package"}, {"bounds": (0, 0, 0, 0)},
    {"bounds": (0, 2500, 1080, 2600)}, {"source_confidence": 0.5},
    {"label": "밝기", "label_parts": ("밝기",)},
])
def test_ineligible_node_is_never_sent_to_jev(changes):
    match, nodes = match_and_nodes(_settings_home_tree())
    nodes = tuple(replace(node, **changes) if node.node_id == "display" else node for node in nodes)
    provider = selector()
    actual, debug = select_ui_target(match, nodes, GRAPH, provider, screen_bounds=SCREEN)
    assert actual is match
    assert debug["status"] == "no_candidates"
    provider.select.assert_not_called()


def test_failed_target_and_ambiguous_window_do_not_get_reselected():
    match, nodes = match_and_nodes(_settings_home_tree())
    provider = selector()
    excluded = {match.transition_id: (target_key(nodes[1]),)}
    _, info = select_ui_target(match, nodes, GRAPH, provider, excluded_target_keys=excluded)
    assert info["status"] == "no_candidates"
    for invalid_nodes in (nodes + (nodes[1],), nodes + (replace(nodes[1], node_id="another", window_id=9),)):
        _, info = select_ui_target(match, invalid_nodes, GRAPH, provider)
        assert info["status"] == "ineligible"
    provider.select.assert_not_called()


def test_explicit_resource_id_narrows_candidates_before_language_choice():
    transitions = tuple(replace(edge, selector=replace(edge.selector, resource_ids=("known-display",)))
                        if edge.transition_id == "S1_TO_S2_DISPLAY_MENU" else edge for edge in GRAPH.transitions)
    graph = replace(GRAPH, transitions=transitions)
    tree = tuple(replace(node, resource_id="known-display") if node.node_id == "display-primary" else node
                 for node in _settings_home_tree_with_two_display_candidates())
    match, nodes = match_and_nodes(tree, graph)
    provider = selector()
    chosen, _ = select_ui_target(match, nodes, graph, provider)
    assert chosen.target_node_id == "display-primary"
    assert len(provider.select.call_args.args[0]["candidates"]) == 1


def test_ocr_selection_preserves_visual_confirmation():
    match, nodes = match_and_nodes(_settings_home_tree())
    nodes = tuple(replace(node, source_confidence=0.85) if node.node_id == "display" else node for node in nodes)
    chosen, _ = select_ui_target(match, nodes, GRAPH, selector())
    assert chosen.match_method == "vision_jev_choice"
    assert chosen.confidence == 0.85
    command = build_guide_command(session_id="s", match_result=chosen, graph=GRAPH)
    assert command.render_policy["requires_visual_confirmation"]
    assert command.render_policy["vision_derived"]


def test_blocked_graph_edge_is_not_sent_to_jev():
    match, nodes = match_and_nodes()
    graph = replace(GRAPH, transitions=tuple(replace(edge, safety_level=SafetyLevel.BLOCKED)
        if edge.transition_id == match.transition_id else edge for edge in GRAPH.transitions))
    provider = selector()
    assert select_ui_target(match, nodes, graph, provider)[0] is match
    provider.select.assert_not_called()


def test_explicit_legacy_auxiliary_selector_returns_real_target(monkeypatch):
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_UI_MIN_CONFIDENCE", "0.8")

    def respond(payload, *, timeout):
        options = payload["questions"]["target"]["criteria"]
        return 200, json.dumps({"model": payload["model"], "answers": {"target": {
            "type": "choice", "choice": "ui_1", "confidence": 1.0,
            "probabilities": {key: float(key == "ui_1") for key in options},
        }}}).encode()

    post = Mock(side_effect=respond)
    monkeypatch.setattr(JevUiSelector, "_post", post)
    engine = ClickAgentEngine(ui_selector=JevUiSelector(), similarity_scorer=KeywordFallbackSimilarityScorer())
    result = engine.handle_turn(AgentTurnRequest(request_id="r", session_id="s",
        raw_goal=_raw_goal("글자 크기 키워줘"), accessibility_tree=_settings_home_tree_with_two_display_candidates(),
        screen_bounds=SCREEN, captured_at_ms=1000))
    post.assert_called_once()
    assert result.guide_command.target_node_id == "display-secondary"
    assert result.guide_command.command_type == CommandType.HIGHLIGHT
    assert result.debug["ui_selection"]["status"] == "selected"
    assert engine.traces[-1]["ui_selection"] == result.debug["ui_selection"]
    assert diagnostic_payload(result.debug)["ui_selection"] == result.debug["ui_selection"]
    assert not result.debug["task_completed"]


def test_engine_keeps_confirmation_and_waits_for_action_result():
    turns = Turns()
    provider = selector()
    turns.engine.ui_selector = provider
    first = turns.send(_font_size_control_tree(2), at=1000, applied_scale=1.0)
    assert first.guide_command.command_type == CommandType.CONFIRMATION
    accepted = turns.send(_font_size_control_tree(2), at=2000, confirmation="accepted", applied_scale=1.0)
    assert accepted.guide_command.command_type == CommandType.HIGHLIGHT
    assert accepted.guide_command.action_type == ActionType.DRAG
    provider.select.reset_mock()
    waiting = turns.send(_font_size_control_tree(2), at=2200, applied_scale=1.0,
                         event=turns.event(accepted, at=2100, action=UserActionType.DRAG_COMPLETED))
    assert waiting.verification_result.status == VerificationStatus.WAITING_FOR_OBSERVATION
    assert not waiting.debug["task_completed"]
    provider.select.assert_not_called()
    completed = turns.send(_font_size_control_tree(3), at=2800, applied_scale=1.15)
    assert completed.guide_command.command_type == CommandType.DONE
    assert completed.debug["task_completed"]
    provider.select.assert_not_called()


@pytest.mark.parametrize("state", ["rejected", "completed", "exhausted"])
def test_engine_does_not_call_jev_after_rejection_completion_or_failure_limit(state):
    turns = Turns()
    provider = selector()
    turns.engine.ui_selector = provider
    turns.send(at=1000)
    if state == "completed":
        turns.engine.session_store.update("s", task_completed=True)
    elif state == "exhausted":
        turns.engine.session_store.update("s", total_failed_attempts=GRAPH.policy.max_failed_attempts)
    provider.select.reset_mock()
    turns.send(at=2000, confirmation="rejected" if state == "rejected" else None)
    provider.select.assert_not_called()


def test_explicit_none_disables_env_provider(monkeypatch):
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    assert ClickAgentEngine(ui_selector=None).ui_selector is None


def test_offline_replay_never_calls_jev_even_if_server_env_enables_it(monkeypatch):
    from ai_agent.runtime.replay import evaluate_cases
    from ai_agent.runtime.tasks import TASK_GRAPHS
    from test_recovery_replay import FIXTURE

    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    provider = Mock(side_effect=AssertionError("Offline replay must not call Jev"))
    monkeypatch.setattr(JevUiSelector, "select", provider)
    report = evaluate_cases(json.loads(FIXTURE.read_text(encoding="utf-8")), TASK_GRAPHS)
    assert not report["violations"]
    provider.assert_not_called()


def test_jev_diagnostics_retain_only_bounded_status_fields():
    safe = {"provider": "jev", "status": "selected", "called": True, "candidate_count": 2,
            "grounded_candidate_count": 1, "confidence": 0.96, "minimum_confidence": 0.8,
            "top_probability": 0.98, "probability_margin": 0.96}
    private = {**safe, "raw_response": "private", "api_key": "private", "choice": "private-node"}
    assert diagnostic_payload({"ui_selection": private}) == {"ui_selection": safe}
    assert diagnostic_payload({"ui_selection": {
        "provider": "private", "status": "private", "called": "private",
        "candidate_count": 100000, "confidence": float("nan"),
        "grounded_candidate_count": True, "minimum_confidence": -1,
        "top_probability": float("inf"), "probability_margin": 2,
    }}) == {"ui_selection": {}}


@pytest.mark.parametrize("key, invalid", [
    ("grounded_candidate_count", -1), ("grounded_candidate_count", 251),
    ("grounded_candidate_count", True), ("grounded_candidate_count", 1.5),
    *[(key, invalid) for key in ("minimum_confidence", "top_probability", "probability_margin",
                               "raw_confidence", "normalized_probability")
      for invalid in (-0.1, 1.1, True, "0.8", float("nan"), float("inf"))],
    *[("choice_count", invalid) for invalid in (2, 253, True, 3.5, "3")],
    *[("choice", invalid) for invalid in ("private-node", "candidate_250", "candidate_00",
                                         "candidate_-1", "candidate_1\n", 1, [])],
    *[("hold_reason", invalid) for invalid in ("private-text", [], True)],
    *[("chosen_transition_hash", invalid) for invalid in ("private-transition", "a" * 63,
                                                         "A" * 64, "a" * 64 + "\n", [])],
])
def test_jev_diagnostics_reject_invalid_visibility_and_probability_values(key, invalid):
    assert diagnostic_payload({"ui_selection": {key: invalid}}) == {"ui_selection": {}}


def test_jev_diagnostic_boundaries_survive_repeated_filtering():
    safe = {"grounded_candidate_count": 0, "minimum_confidence": 1,
            "top_probability": 1, "probability_margin": 0,
            "choice": "candidate_249", "choice_count": 252,
            "raw_confidence": 0, "normalized_probability": 1,
            "hold_reason": "both_below_minimum", "chosen_transition_hash": "a" * 64}
    filtered = diagnostic_payload({"ui_selection": safe})
    assert filtered == {"ui_selection": safe}
    assert diagnostic_payload(filtered) == filtered
