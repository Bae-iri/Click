from dataclasses import replace

from ai_agent.runtime.engine import ClickAgentEngine
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from ai_agent.runtime.types import CommandType, VisionElement
from test_vision_fallback import request


def engine():
    return ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer())


def turn(number, elements=(), failure=None):
    value = request()
    return replace(value, request_id=f"cycle-{number}", observation_id=f"obs-{number}",
        captured_at_ms=number * 1000, raw_goal=value.raw_goal if number == 1 else None,
        vision_failure=failure,
        vision_snapshot=None if failure else replace(value.vision_snapshot,
            consent_request_id=f"cycle-{number}", elements=elements))


def test_three_failures_retry_with_different_strategies_then_return_and_do_not_loop():
    runtime = engine()
    for number, strategy in [(1, "upscale"), (2, "focus_or_tiles")]:
        result = runtime.handle_turn(turn(number))
        assert result.guide_command.command_type == CommandType.CONFIRMATION
        policy = result.guide_command.render_policy
        assert policy["vision_cycle"]["failed_cycles"] == number
        assert policy["vision_fallback"]["strategy"] == strategy
    final = runtime.handle_turn(turn(3))
    state = final.guide_command.render_policy["vision_cycle"]
    assert state["status"] == "failed" and state["failed_cycles"] == 3
    assert state["last_cycle_reason"] == "recognition_failed"
    assert "llm_fallback" not in state
    assert final.guide_command.render_policy["recovery"]["waiting_for"] == "user"
    again = runtime.handle_turn(replace(turn(4), vision_snapshot=None))
    assert again.guide_command.render_policy["vision_cycle"]["failed_cycles"] == 3
    assert "vision_fallback" not in again.guide_command.render_policy


def test_second_or_third_cycle_can_succeed_without_lowering_confidence():
    for successful_cycle in (2, 3):
        runtime = engine()
        for number in range(1, successful_cycle):
            runtime.handle_turn(turn(number))
        result = runtime.handle_turn(turn(successful_cycle, request().vision_snapshot.elements))
        assert result.guide_command.target_node_id == "vision:ocr_0"
        assert runtime.session_store.get("session").vision_state == {}


def test_readable_menus_without_target_have_separate_reason_and_only_llm_placeholder():
    runtime = engine()
    menus = (VisionElement("title", "설정", (20, 10, 500, 70), confidence=.95, actionable=False),
             VisionElement("other", "글자 크기와 스타일", (20, 300, 900, 400), confidence=.95, actionable=False))
    for number in (1, 2, 3):
        result = runtime.handle_turn(turn(number, menus))
    state = result.guide_command.render_policy["vision_cycle"]
    assert state["last_cycle_reason"] == "target_not_found"
    assert state["accessibility_tree_empty"] is True
    assert state["absence_confirmed"] is False
    assert state["llm_fallback"]["status"] == "not_configured"
    assert result.guide_command.target_bounds is None


def test_capture_ocr_errors_count_once_and_stale_or_safety_does_not_retry():
    runtime = engine()
    for number, failure in ((1, "capture_failed"), (2, "ocr_failed")):
        result = runtime.handle_turn(turn(number, failure=failure))
        assert result.guide_command.render_policy["vision_cycle"]["failed_cycles"] == number
    duplicate = runtime.handle_turn(turn(2, failure="ocr_failed"))
    assert duplicate.debug["recovery"]["reason_code"] == "OBSERVATION_STALE"
    result = runtime.handle_turn(turn(3, failure="screen_changed"))
    assert result.guide_command.render_policy["vision_cycle"]["failed_cycles"] == 2
    assert result.guide_command.render_policy["vision_cycle"]["status"] == "waiting_for_stable_screen"
    for failure, status in (("masking_failed", "blocked"), ("unsupported", "unsupported")):
        result = engine().handle_turn(turn(1, failure=failure))
        assert result.guide_command.render_policy["vision_cycle"]["status"] == status
        assert "vision_fallback" not in result.guide_command.render_policy
