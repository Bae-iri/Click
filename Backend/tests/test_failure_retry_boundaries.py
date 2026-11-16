"""Cosmetic redraws cannot reopen failures or replenish per-page selection attempts."""
from dataclasses import replace

import pytest

from ai_agent.runtime import ClickAgentEngine
from app.clients.checkpoint import capture, restore
from tests.test_primary_recovery import Scenario, config


def restart(scenario):
    saved = capture(scenario.engine.session_store, "s", scenario.graph)
    scenario.engine = ClickAgentEngine(session_store=restore(saved),
        task_graphs={scenario.goal.task_id: scenario.graph}, screen_resolver=scenario.resolver)


@pytest.mark.parametrize("outcome", ["insufficient_evidence", (.72, .81), "wait_for_result"])
def test_twenty_layout_redraws_and_generated_ids_do_not_repeat_model_calls(outcome):
    scenario = Scenario([outcome])
    scenario.observe()
    for index in range(20):
        restart(scenario)
        nodes = (scenario.nodes[0], replace(scenario.nodes[1], node_id=f"redraw-{index}",
            bounds_in_screen=(10, 300 + index, 1000, 400 + index)))
        result = scenario.observe(nodes=nodes)
        assert result.debug["ui_selection"]["paused"] is True
        assert result.guide_command.target_node_id is None
        state = scenario.engine.session_store.get("s")
        assert state.recovery_state["attempts"] == 1
        assert state.total_failed_attempts == 0
    assert len(scenario.calls) == 1


def test_new_native_evidence_is_bounded_across_checkpoint_restores():
    scenario = Scenario(["insufficient_evidence"])
    for index in range(10):
        if index:
            restart(scenario)
        result = scenario.observe(nodes=(scenario.nodes[0], replace(scenario.nodes[1],
            resource_id=f"android:id/native-control-{index}")))
        assert result.guide_command.target_node_id is None
    limit = scenario.graph.policy.recovery_limit
    assert len(scenario.calls) == limit
    assert scenario.engine.session_store.get("s").recovery_state["attempts"] == limit
    assert scenario.retry().debug["ui_selection"]["paused"] is True
    assert len(scenario.calls) == limit


def test_scrolling_without_candidates_does_not_spend_the_model_call_budget():
    scenario = Scenario(["selected"])
    for index in range(6):
        # Native text rows are observations, not eligible menu candidates.
        rows = (scenario.nodes[0], replace(scenario.nodes[1], text="설정", clickable=False,
            resource_id=f"android:id/visible-text-{index}"))
        result = scenario.observe(nodes=rows)
        assert result.guide_command.target_node_id is None
        assert len(scenario.calls) == 0
        restart(scenario)
    assert scenario.engine.session_store.get("s").recovery_state["provider_attempts"] == 0
    found = scenario.observe()
    assert found.guide_command.target_node_id == "display"
    assert len(scenario.calls) == 1


def test_provider_outage_requires_explicit_retry_even_after_candidate_changes():
    scenario = Scenario(["offline", "selected"])
    scenario.observe()
    for index in range(5):
        result = scenario.observe(nodes=(scenario.nodes[0], replace(scenario.nodes[1],
            resource_id=f"android:id/native-control-{index}")))
        assert result.debug["ui_selection"]["paused"] is True
        assert result.guide_command.render_policy["recovery"]["failure"]["cause"] == "network_error"
    assert len(scenario.calls) == 1
    assert scenario.retry().guide_command.target_node_id == "display"
    assert len(scenario.calls) == 2


def test_better_observation_confidence_can_supply_new_candidate_evidence(monkeypatch):
    import ai_agent.runtime.engine as runtime

    scenario = Scenario(["selected"])
    normalize = runtime.normalize_accessibility_tree
    quality = .5
    def observation(*args, **kwargs):
        return tuple(replace(node, source_confidence=quality) if node.node_id == "display" else node
                     for node in normalize(*args, **kwargs))
    monkeypatch.setattr(runtime, "normalize_accessibility_tree", observation)
    uncertain = scenario.observe()
    assert uncertain.guide_command.target_node_id is None
    assert len(scenario.calls) == 0
    quality = .99
    clearer = scenario.observe()
    assert clearer.guide_command.target_node_id == "display"
    assert len(scenario.calls) == 1


@pytest.mark.parametrize("previous_fingerprint", ["legacy-layout-hash", None, 42])
def test_legacy_paused_checkpoints_do_not_retry_without_a_semantic_baseline(previous_fingerprint):
    scenario = Scenario(["insufficient_evidence"])
    scenario.observe()
    state = scenario.engine.session_store.get("s")
    scenario.engine.session_store.update("s", recovery_state={**state.recovery_state,
        "scope": "selection:S1_SETTINGS_HOME", "attempts": 2,
        "selection_fingerprint": previous_fingerprint})
    restart(scenario)
    result = scenario.observe(nodes=(scenario.nodes[0], replace(scenario.nodes[1],
        resource_id="android:id/new-control")))
    assert result.debug["ui_selection"]["paused"] is True
    assert len(scenario.calls) == 1
    assert scenario.engine.session_store.get("s").recovery_state["attempts"] == 2


def test_rule_state_fluctuations_without_page_evidence_do_not_refund_retries(monkeypatch):
    import ai_agent.runtime.engine as runtime

    classify = runtime.classify_screen_state
    turns = 0
    def fluctuating(*args, **kwargs):
        nonlocal turns
        classify(*args, **kwargs)
        turns += 1
        return "S1_SETTINGS_HOME" if turns % 2 else "S2_DISPLAY_MENU"
    monkeypatch.setattr(runtime, "classify_screen_state", fluctuating)
    scenario = Scenario(["insufficient_evidence"])
    # A menu label supplies a candidate, not evidence of a different page title.
    scenario.nodes = (scenario.nodes[1],)
    for _ in range(10):
        result = scenario.observe()
        assert result.guide_command.target_node_id is None
        assert scenario.engine.session_store.get("s").last_snapshot.page_fingerprint is None
    assert len(scenario.calls) == scenario.graph.policy.recovery_limit
    assert scenario.engine.session_store.get("s").recovery_state["attempts"] == scenario.graph.policy.recovery_limit


def test_privacy_interruption_retires_approval_and_requires_confirmation_again():
    from ai_agent.runtime import CommandType
    from ai_agent.runtime.types import AccessibilityNode
    from tests.test_jev_primary import setup_slider

    graph, _, engine, _, request = setup_slider()
    engine.handle_turn(request)
    approved = engine.handle_turn(replace(request, request_id="approve", observation_id="approve",
        captured_at_ms=2000, user_confirmation="accepted"))
    assert approved.guide_command.action_type.value == "drag"
    engine.session_store.update("s", total_failed_attempts=1, transition_attempts={"spent": 1})
    secret = AccessibilityNode("secret", text="private-test-value", role="text_input",
        class_name="android.widget.EditText", package_name="com.android.settings",
        bounds_in_screen=(10, 300, 300, 400), extras={"password": True})
    blocked = engine.handle_turn(replace(request, request_id="private", observation_id="private",
        captured_at_ms=3000, accessibility_tree=(*request.accessibility_tree, secret)))
    assert blocked.guide_command.command_type == CommandType.BLOCKED
    saved = capture(engine.session_store, "s", graph)
    assert "private-test-value" not in str(saved)
    assert saved["pending_attempt"] is None and saved["pending_selection"] is None
    assert saved["total_failed_attempts"] == 1 and saved["transition_attempts"] == {"spent": 1}
    engine = ClickAgentEngine(session_store=restore(saved),
        task_graphs={graph.definition.task_id: graph}, screen_resolver=engine.screen_resolver)
    resumed = engine.handle_turn(replace(request, request_id="safe", observation_id="safe",
        captured_at_ms=4000))
    assert resumed.guide_command.command_type == CommandType.CONFIRMATION
    assert engine.session_store.get("s").pending_attempt is None
