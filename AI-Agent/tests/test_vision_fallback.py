from dataclasses import replace
from ai_agent.runtime import (AgentTurnRequest, ClickAgentEngine, CommandType, DeviceContext,
                             RawGoalInput, VisionElement, VisionSnapshot)
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer


def request():
    return AgentTurnRequest(request_id="one", session_id="session", accessibility_tree=(),
        raw_goal=RawGoalInput(source_text="글자 크기 키워줘", locale="ko-KR",
            device_context=DeviceContext(platform="android", app_package="com.android.settings")),
        screen_bounds=(0, 0, 1080, 2400), active_window_id=42,
        vision_snapshot=VisionSnapshot(user_consented=True, consent_request_id="one", redaction_applied=True,
            active_window_id=42, package_name="com.android.settings",
            elements=(VisionElement(element_id="display", text="디스플레이",
                bounds_in_screen=(20, 100, 900, 200)),
                VisionElement(element_id="title", text="설정", actionable=False,
                    bounds_in_screen=(20, 10, 500, 70)))))


def test_trusted_consent_is_bound_to_one_request_not_session():
    engine = ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer())
    first = engine.handle_turn(request())
    assert first.guide_command.target_node_id == "vision:ocr_0"
    replay = engine.handle_turn(replace(request(), request_id="two", raw_goal=None))
    assert replay.guide_command.command_type == CommandType.CONFIRMATION
    assert replay.debug["vision_fallback"]["status"] == "consent_required"


def test_boolean_consent_does_not_call_provider_with_screen_data():
    payload = request()
    engine = ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer())
    response = engine.handle_turn(replace(payload,
        vision_snapshot=replace(payload.vision_snapshot, consent_request_id=None)))
    assert response.guide_command.command_type == CommandType.CONFIRMATION
    assert response.guide_command.target_node_id is None
    assert response.debug["observation"]["vision_element_count"] == 0


def test_vision_from_other_window_is_not_a_target():
    payload = request()
    engine = ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer())
    response = engine.handle_turn(replace(payload,
        vision_snapshot=replace(payload.vision_snapshot, active_window_id=7)))
    assert response.guide_command.target_node_id is None
