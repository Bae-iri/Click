"""Settings navigation with nested Android rows, summaries and off-screen targets."""
from dataclasses import replace

import pytest

from ai_agent.runtime import AccessibilityNode, ClickAgentEngine, CommandType
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH, MVP_02_TASK_GRAPH
from ai_agent.runtime.types import AgentTurnRequest, TypedGoal, DeviceContext
from test_engine_flow import _raw_goal, _font_size_control_tree


SCREEN = (0, 0, 1080, 2400)
PACKAGE = "com.android.settings"


def settings_screen(title, rows):
    nodes = [
        AccessibilityNode("root", package_name=PACKAGE, bounds_in_screen=SCREEN),
        AccessibilityNode("title", package_name=PACKAGE, text=title,
                          class_name="android.widget.TextView", parent_id="root",
                          bounds_in_screen=(40, 80, 1000, 180)),
        AccessibilityNode("list", package_name=PACKAGE, parent_id="root", scrollable=True,
                          class_name="androidx.recyclerview.widget.RecyclerView",
                          bounds_in_screen=(0, 240, 1080, 2200)),
    ]
    for index, (key, label, summary) in enumerate(rows):
        top = 260 + index * 220
        nodes.extend((
            AccessibilityNode(key, package_name=PACKAGE, parent_id="list", clickable=True,
                              class_name="android.widget.LinearLayout",
                              bounds_in_screen=(0, top, 1080, top + 200)),
            AccessibilityNode(key + "-title", package_name=PACKAGE, parent_id=key,
                              text=label, class_name="android.widget.TextView",
                              bounds_in_screen=(40, top + 20, 1000, top + 80)),
            AccessibilityNode(key + "-summary", package_name=PACKAGE, parent_id=key,
                              text=summary, class_name="android.widget.TextView",
                              bounds_in_screen=(40, top + 100, 1000, top + 170)),
        ))
    return tuple(nodes)


HOME = settings_screen("설정", (
    ("display", "디스플레이", "밝기, 글자 크기, 다크 모드"),
    ("wallpaper", "배경화면 및 스타일", "배경화면, 색상 팔레트"),
    ("home-screen", "홈 화면", "홈 화면 구성"),
))


def engine_for(graph=MVP_01_TASK_GRAPH):
    return ClickAgentEngine(task_graphs={graph.definition.task_id: graph},
                            similarity_scorer=KeywordFallbackSimilarityScorer())


def send(engine, nodes, index, goal=None):
    return engine.handle_turn(AgentTurnRequest(
        request_id=f"r{index}", session_id="navigation", observation_id=f"o{index}",
        captured_at_ms=index * 1000, raw_goal=None,
        device_context=DeviceContext(platform="android"),
        typed_goal=TypedGoal(goal_type=(graph := next(iter(engine.task_graphs.values()))).definition.goal_type,
            task_id=graph.definition.task_id, target_app="android_settings", task_type="settings_change",
            slots=graph.definition.required_slots, source_text="", confidence=1.0,
            is_supported=True, required_slots_filled=True) if goal else None,
        accessibility_tree=nodes, screen_bounds=SCREEN))


@pytest.mark.parametrize("graph,goal", [
    (MVP_01_TASK_GRAPH, "글자 크기 키워줘"),
    (MVP_02_TASK_GRAPH, "글자 크기 줄여줘"),
])
@pytest.mark.parametrize("reverse", [False, True])
def test_launcher_to_settings_uses_page_title_not_menu_summaries(graph, goal, reverse):
    engine = engine_for(graph)
    launcher = (AccessibilityNode("settings-icon", text="설정", clickable=True,
        package_name="com.sec.android.app.launcher", bounds_in_screen=(40, 100, 200, 260)),)
    first = send(engine, launcher, 1, goal)
    assert first.guide_command.target_node_id == "settings-icon"

    home = send(engine, tuple(reversed(HOME)) if reverse else HOME, 2)
    assert home.current_state_id == "S1_SETTINGS_HOME"
    assert home.guide_command.target_node_id == "display"
    assert home.guide_command.target_bounds == (0, 260, 1080, 460)
    assert home.guide_command.message == "디스플레이를 눌러주세요."

    display = send(engine, settings_screen("디스플레이", (
        ("brightness", "밝기", "화면 밝기를 조절합니다"),
        ("font", "글자 크기와 스타일", "글꼴, 글자 크기"),
    )), 3)
    assert display.current_state_id == "S2_DISPLAY_MENU"
    assert display.guide_command.target_node_id == "font"

    control = send(engine, _font_size_control_tree(2), 4)
    assert control.current_state_id == "S4_FONT_SIZE_CONTROL"
    assert control.guide_command.command_type == CommandType.CONFIRMATION


def test_display_row_title_outranks_generic_screen_aliases_in_other_rows():
    result = match_next_ui("S1_SETTINGS_HOME", normalize_accessibility_tree(HOME, screen_bounds=SCREEN),
                           MVP_01_TASK_GRAPH, similarity_scorer=KeywordFallbackSimilarityScorer())
    assert result.target_node_id == "display"
    assert result.fallback_reason is None


class OverconfidentSimilarity:
    def score(self, source, candidate):
        return 1.0


@pytest.mark.parametrize("status", ["no_signal", "provider_error", "not_configured"])
def test_unavailable_vision_waits_for_user_without_ending_or_requesting_capture_again(status):
    from ai_agent.runtime.vision import VisionFallbackAnalysis
    from test_vision_fallback import request as vision_request

    class UnavailableVision:
        calls = 0

        def analyze(self, snapshot, *, reason):
            self.calls += 1
            return VisionFallbackAnalysis(status, reason, "test")

    engine = engine_for()
    provider = UnavailableVision()
    engine.vision_fallback = provider
    first = engine.handle_turn(vision_request())
    policy = first.guide_command.render_policy["recovery"]
    assert first.guide_command.command_type == CommandType.HIGHLIGHT
    assert policy["reason_code"] == "VISION_UNAVAILABLE"
    assert policy["waiting_for"] == "user"
    again = engine.handle_turn(replace(vision_request(), request_id="next", raw_goal=None,
                                      vision_snapshot=None))
    assert again.guide_command.command_type == CommandType.HIGHLIGHT
    assert again.guide_command.render_policy["recovery"]["waiting_for"] == "user"
    assert provider.calls == 1


@pytest.mark.parametrize("scorer", [KeywordFallbackSimilarityScorer(), OverconfidentSimilarity()])
def test_screen_keyword_does_not_select_wallpaper_even_with_similarity(scorer):
    tree = settings_screen("설정", (("wallpaper", "배경화면", ""),))
    graph = replace(MVP_01_TASK_GRAPH, transitions=tuple(t for t in MVP_01_TASK_GRAPH.transitions
        if t.transition_id != "S1_TO_S3_ACCESSIBILITY_FALLBACK"))
    result = match_next_ui("S1_SETTINGS_HOME", normalize_accessibility_tree(tree, screen_bounds=SCREEN),
                           graph, similarity_scorer=scorer)
    assert result.target_node_id is None


def test_offscreen_menu_waits_for_manual_navigation_then_resumes():
    engine = engine_for()
    tree = settings_screen("설정", (("connections", "연결", "Wi-Fi, 블루투스"),))
    for index in range(1, 7):
        result = send(engine, tree, index, "글자 크기 키워줘" if index == 1 else None)
        assert result.current_state_id == "S1_SETTINGS_HOME"
        assert result.guide_command.command_type == CommandType.HIGHLIGHT
        assert result.guide_command.target_node_id is None
        assert "디스플레이" in result.guide_command.message
        assert "밀어" in result.guide_command.message
        assert "vision_fallback" not in result.guide_command.render_policy
        assert engine.session_store.get("navigation").pending_transition_id is None
        assert engine.session_store.get("navigation").recovery_count == 0
    result = send(engine, HOME, 7)
    assert result.guide_command.target_node_id == "display"


def test_visible_but_unactionable_target_still_requests_observation_fallback():
    tree = tuple(replace(n, clickable=False) if n.node_id == "display" else n for n in HOME)
    result = send(engine_for(), tree, 1, "글자 크기 키워줘")
    assert result.guide_command.render_policy["recovery"]["next_action"] == "reobserve"
    assert result.guide_command.target_node_id is None


def test_popup_title_does_not_inherit_underlying_screen_state():
    tree = settings_screen("확인", (("display", "디스플레이", "밝기, 글자 크기, 다크 모드"),))
    result = send(engine_for(), tree, 1, "글자 크기 키워줘")
    assert result.current_state_id == "S0_UNKNOWN"
    assert result.guide_command.target_node_id is None
    assert result.guide_command.render_policy["recovery"]["next_action"] == "reobserve"


def test_transient_unknown_screen_preserves_observed_progress_and_candidates():
    engine = engine_for()
    send(engine, HOME, 1, "글자 크기 키워줘")
    send(engine, settings_screen("디스플레이", (("font", "글자 크기와 스타일", ""),)), 2)
    before = engine.session_store.get("navigation").observed_path
    popup = settings_screen("확인", (("display", "디스플레이", "밝기, 글자 크기, 다크 모드"),))
    response = send(engine, popup, 3)
    assert response.current_state_id == "S0_UNKNOWN"
    assert engine.session_store.get("navigation").observed_path == before
    assert engine.session_store.get("navigation").state_candidates


def test_ambiguous_targets_wait_after_bounded_fresh_reads_and_resume():
    engine = engine_for()
    ambiguous = settings_screen("설정", (("a", "디스플레이", ""), ("b", "디스플레이", "")))
    for index in range(1, 6):
        response = send(engine, ambiguous, index, "글자 크기 키워줘" if index == 1 else None)
        recovery = response.guide_command.render_policy["recovery"]
        assert recovery["reason_code"] == "TARGET_AMBIGUOUS"
        assert response.guide_command.command_type == CommandType.HIGHLIGHT
        assert engine.session_store.get("navigation").total_failed_attempts == 0
        if index >= 3:
            assert recovery["waiting_for"] == "user"
    response = send(engine, HOME, 6)
    assert response.guide_command.target_node_id == "display"
    assert not engine.session_store.get("navigation").recovery_state


def test_duplicate_observation_does_not_consume_recovery_or_action_budget():
    engine = engine_for()
    ambiguous = settings_screen("설정", (("a", "디스플레이", ""), ("b", "디스플레이", "")))
    send(engine, ambiguous, 1, "글자 크기 키워줘")
    before = engine.session_store.get("navigation")
    for _ in range(5):
        response = send(engine, ambiguous, 1)
        assert response.guide_command.render_policy["recovery"]["reason_code"] == "OBSERVATION_STALE"
    assert engine.session_store.get("navigation") == before


def test_explicit_retry_resets_observation_budget_but_not_failed_action_count():
    from ai_agent.runtime.types import UserActionEvent, UserActionType
    engine = engine_for()
    ambiguous = settings_screen("설정", (("a", "디스플레이", ""), ("b", "디스플레이", "")))
    for index in range(1, 4):
        send(engine, ambiguous, index, "글자 크기 키워줘" if index == 1 else None)
    engine.session_store.update("navigation", total_failed_attempts=2)
    response = engine.handle_turn(AgentTurnRequest(request_id="retry", session_id="navigation", raw_goal=None,
        accessibility_tree=ambiguous, screen_bounds=SCREEN, observation_id="retry", captured_at_ms=4000,
        user_action_event=UserActionEvent(UserActionType.MANUAL_OVERRIDE)))
    assert response.guide_command.render_policy["recovery"]["next_action"] == "reobserve"
    assert engine.session_store.get("navigation").recovery_count == 1
    assert engine.session_store.get("navigation").total_failed_attempts == 2


def test_elapsed_observation_budget_waits_even_before_retry_count_limit():
    graph = replace(MVP_01_TASK_GRAPH, policy=replace(MVP_01_TASK_GRAPH.policy, recovery_limit=99))
    engine = engine_for(graph)
    ambiguous = settings_screen("설정", (("a", "디스플레이", ""), ("b", "디스플레이", "")))
    send(engine, ambiguous, 1, "글자 크기 키워줘")
    result = send(engine, ambiguous, 6)
    assert result.guide_command.render_policy["recovery"]["waiting_for"] == "user"
    assert engine.session_store.get("navigation").recovery_count == 2
    assert engine.session_store.get("navigation").total_failed_attempts == 0
