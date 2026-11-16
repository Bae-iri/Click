"""Regressions from the Galaxy Note20 font-size screen observed on 2026-10-29."""
from dataclasses import replace

import pytest

from ai_agent.runtime import AccessibilityNode, ClickAgentEngine, UserActionEvent, UserActionType
from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH, MVP_02_TASK_GRAPH
from ai_agent.runtime.types import AgentTurnRequest, CommandType, DeviceContext, TypedGoal


PACKAGE = "com.android.settings"
SCREEN = (0, 0, 1080, 2400)


def samsung_font_screen(value=4, *, label=None):
    def node(identifier, bounds, *, text=None, description=None, resource=None,
             kind="android.widget.TextView", clickable=False, **kwargs):
        return AccessibilityNode(identifier, package_name=PACKAGE, parent_id="root",
            bounds_in_screen=bounds, text=text, content_description=description,
            resource_id=resource, class_name=kind, clickable=clickable, window_id=42, **kwargs)

    # SeekBar contentDescription is a one-based step; RangeInfo is zero-based.
    return (
        AccessibilityNode("root", package_name=PACKAGE, bounds_in_screen=SCREEN, window_id=42),
        node("title", (158, 179, 688, 274), text="글자 크기와 스타일"),
        node("preview", (56, 690, 1024, 797), text="글자가 이 크기로 표시됩니다."),
        node("font-family", (68, 1372, 1024, 1543), text="글꼴", clickable=True),
        node("font-label", (45, 1826, 359, 1995), text="글자 크기",
             resource=PACKAGE + ":id/fontSize"),
        node("smaller", (45, 1995, 135, 2085), description="글자 크기 작게",
             resource=PACKAGE + ":id/smaller", kind="android.widget.ImageView", clickable=True),
        node("font-slider", (135, 2009, 945, 2071), description=str(value + 1) if label is None else label,
             resource=PACKAGE + ":id/seekBarForFontSize", kind="android.widget.SeekBar",
             extras={"progress": value, "min": 0, "max": 7}),
        node("larger", (945, 1995, 1035, 2085), description="글자 크기 크게",
             resource=PACKAGE + ":id/larger", kind="android.widget.ImageView", clickable=True),
    )


@pytest.mark.parametrize("graph", [MVP_01_TASK_GRAPH, MVP_02_TASK_GRAPH])
@pytest.mark.parametrize("previous", [None, "S2_DISPLAY_MENU", "S3_FONT_MENU", "S4_FONT_SIZE_CONTROL"])
def test_combined_page_selects_slider_instead_of_ambiguous_plus_and_minus(graph, previous):
    nodes = normalize_accessibility_tree(samsung_font_screen(), screen_bounds=SCREEN)
    evidence = {}
    state = classify_screen_state(nodes, graph, previous_state_id=previous, evidence=evidence)
    assert state == "S4_FONT_SIZE_CONTROL"
    assert evidence["candidates"][0]["control_match"]
    match = match_next_ui(state, nodes, graph)
    assert match.target_node_id == "font-slider"
    assert match.action_type.value == "drag"
    assert match.fallback_reason is None


@pytest.mark.parametrize("graph", [MVP_01_TASK_GRAPH, MVP_02_TASK_GRAPH])
@pytest.mark.parametrize("delayed_event", [False, True])
@pytest.mark.parametrize("recreated_window", [False, True])
def test_numeric_description_and_layout_changes_keep_original_result_baseline(graph, delayed_event, recreated_window):
    engine = ClickAgentEngine(task_graphs={graph.definition.task_id: graph},
                              similarity_scorer=KeywordFallbackSimilarityScorer())
    goal = TypedGoal(goal_type=graph.definition.goal_type, task_id=graph.definition.task_id,
        target_app="android_settings", task_type="settings_change", slots=graph.definition.required_slots,
        source_text="", confidence=1, is_supported=True, required_slots_filled=True)

    def turn(index, nodes, scale, *, confirmation=None, event=None):
        return engine.handle_turn(AgentTurnRequest(request_id=f"r{index}", session_id="samsung-font",
            episode_id="episode", observation_id=f"o{index}", captured_at_ms=index * 1000,
            raw_goal=None, typed_goal=goal if index == 1 else None, accessibility_tree=nodes,
            device_context=DeviceContext(platform="android", app_package=PACKAGE),
            screen_bounds=SCREEN, active_window_id=nodes[0].window_id, user_confirmation=confirmation,
            user_action_event=event,
            applied_settings={"source": "android.provider.Settings.System.FONT_SCALE", "font_scale": scale}))

    assert turn(1, samsung_font_screen(), 1.3).guide_command.command_type == CommandType.CONFIRMATION
    guide = turn(2, samsung_font_screen(), 1.3, confirmation="accepted").guide_command
    assert guide.action_type.value == "drag"
    increase = graph.definition.required_slots["direction"] == "increase"
    value, scale = (5, 1.5) if increase else (3, 1.15)
    after = tuple(replace(n, node_id="font-slider-new-index", bounds_in_screen=(135, 2020, 945, 2082))
                  if n.node_id == "font-slider" else n for n in samsung_font_screen(value))
    if recreated_window:
        after = tuple(replace(n, window_id=44) for n in after)
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, guide.guide_command_id,
        guide.target_node_id, occurred_at_ms=2500, event_id="font-drag", episode_id="episode",
        target_bounds=guide.target_bounds)
    if delayed_event:
        waiting = turn(3, after, scale)
        assert waiting.guide_command.command_type != CommandType.DONE
        assert waiting.guide_command.render_policy["recovery"]["reason_code"] == "ACTION_EVENT_MISSING"
    done = turn(4, after, scale, event=event)
    assert done.guide_command.command_type == CommandType.DONE
    assert done.verification_result.task_completed
    outcome = engine.session_store.get("samsung-font").technical_outcome
    assert outcome["baseline_value"] == 4
    assert outcome["after_value"] == value
    assert outcome["baseline_font_scale"] == 1.3
    assert outcome["font_scale"] == scale


@pytest.mark.parametrize("problem", ["brightness", "two_numeric_sliders", "wrong_package"])
def test_numeric_control_requires_unique_font_context(problem):
    tree = samsung_font_screen(label="밝기" if problem == "brightness" else None)
    if problem == "two_numeric_sliders":
        tree = (*tree, replace(tree[-2], node_id="second-slider", resource_id=PACKAGE + ":id/other",
                               bounds_in_screen=(135, 2100, 945, 2162)))
    if problem == "wrong_package":
        tree = tuple(replace(n, package_name="com.example.other") for n in tree)
    nodes = normalize_accessibility_tree(tree, screen_bounds=SCREEN)
    match = match_next_ui("S4_FONT_SIZE_CONTROL", nodes, MVP_01_TASK_GRAPH)
    assert match.target_node_id is None


@pytest.mark.parametrize("problem", ["unchanged_scale", "missing_scale", "opposite_scale",
    "unchanged_slider", "wrong_resource", "duplicate_control", "wrong_package", "late_observation"])
def test_recreated_window_requires_unique_control_and_both_persisted_and_slider_changes(problem):
    graph = MVP_01_TASK_GRAPH
    engine = ClickAgentEngine(task_graphs={graph.definition.task_id: graph},
                              similarity_scorer=KeywordFallbackSimilarityScorer())
    goal = TypedGoal(goal_type=graph.definition.goal_type, task_id=graph.definition.task_id,
        target_app="android_settings", task_type="settings_change", slots=graph.definition.required_slots,
        source_text="", confidence=1, is_supported=True, required_slots_filled=True)

    def turn(index, nodes, scale, *, confirmation=None, event=None, at=None):
        return engine.handle_turn(AgentTurnRequest(request_id=f"r{index}", session_id="recreated-font",
            episode_id="episode", observation_id=f"o{index}", captured_at_ms=at or index * 1000,
            typed_goal=goal if index == 1 else None, raw_goal=None, accessibility_tree=nodes,
            device_context=DeviceContext(platform="android", app_package=PACKAGE),
            screen_bounds=SCREEN, active_window_id=nodes[0].window_id,
            user_confirmation=confirmation, user_action_event=event,
            applied_settings={"source": "android.provider.Settings.System.FONT_SCALE", "font_scale": scale}))

    turn(1, samsung_font_screen(), 1.3)
    guide = turn(2, samsung_font_screen(), 1.3, confirmation="accepted").guide_command
    after = tuple(replace(n, window_id=44) for n in samsung_font_screen(4 if problem == "unchanged_slider" else 5))
    scale = {"unchanged_scale": 1.3, "missing_scale": None, "opposite_scale": 1.1}.get(problem, 1.5)
    if problem == "wrong_resource":
        after = tuple(replace(n, resource_id=PACKAGE + ":id/other") if n.node_id == "font-slider" else n for n in after)
    if problem == "duplicate_control":
        after = (*after, replace(after[-2], node_id="duplicate", bounds_in_screen=(135, 2120, 945, 2182)))
    if problem == "wrong_package":
        after = tuple(replace(n, package_name="com.example.other") for n in after)
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, guide.guide_command_id,
        guide.target_node_id, occurred_at_ms=2500, event_id="font-drag", episode_id="episode",
        target_bounds=guide.target_bounds)
    result = turn(3, after, scale, event=event,
        at=2501 + graph.policy.observation_timeout_ms if problem == "late_observation" else 3000)
    if problem == "wrong_package":
        assert result.verification_result is None
        assert result.guide_command.target_node_id is None
        assert result.guide_command.render_policy["recovery"]["reason_code"] == "OTHER_APP"
    else:
        assert not result.verification_result.task_completed
    assert not engine.session_store.get("recreated-font").task_completed
    assert engine.session_store.get("recreated-font").technical_outcome.get("status") != "CHANGED"
