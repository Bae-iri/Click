from dataclasses import replace
import json
from pathlib import Path

import pytest

from ai_agent.runtime.replay import ReplayRecorder, evaluate_cases
from ai_agent.runtime.tasks import TASK_GRAPHS, MVP_01_TASK_GRAPH as GRAPH
from ai_agent.runtime.types import AgentTurnRequest
from ai_agent.telemetry.trace_logger import diagnostic_payload
from test_settings_navigation import HOME, SCREEN, engine_for
from test_engine_flow import _raw_goal


FIXTURE = Path(__file__).parents[1] / "data/evaluation/runtime_recovery/navigation.json"


def test_reviewed_synthetic_recovery_cases():
    report = evaluate_cases(json.loads(FIXTURE.read_text(encoding="utf-8")), TASK_GRAPHS)
    assert not report["violations"], report
    assert report["metrics"]["false_target_count"] == 0
    assert report["metrics"]["false_termination_count"] == 0
    assert report["metrics"]["false_completion_count"] == 0
    assert report["metrics"]["recovered_cases"] == report["metrics"]["recovery_cases"] == 4


def test_metrics_detect_wrong_target_termination_completion_and_missing_recovery():
    cases = json.loads(FIXTURE.read_text(encoding="utf-8"))[:1]
    cases[0]["turns"][0]["expected"]["target_node_id"] = "wrong-target"
    cases[0]["expect_recovery"] = True
    report = evaluate_cases(cases, TASK_GRAPHS)
    assert report["metrics"]["false_target_count"] == 1
    assert report["metrics"]["recovered_cases"] == 0
    assert len(report["violations"]) == 2
    completion = json.loads(FIXTURE.read_text(encoding="utf-8"))[-1:]
    completion[0]["turns"][-1]["expected"].update(done=False, terminal=False)
    report = evaluate_cases(completion, TASK_GRAPHS)
    assert report["metrics"]["false_termination_count"] == 1
    assert report["metrics"]["false_completion_count"] == 1


def test_replay_rejects_a_different_graph_version():
    cases = json.loads(FIXTURE.read_text(encoding="utf-8"))[:1]
    cases[0]["graph_hash"] = "invalid"
    with pytest.raises(ValueError, match="hash mismatch"):
        evaluate_cases(cases, TASK_GRAPHS)


def test_capture_redacts_free_text_ids_and_input_values_preserving_structure():
    recorder = ReplayRecorder(GRAPH)
    engine = engine_for(); engine.replay_recorder = recorder
    private = replace(HOME[1], node_id="person@example.com", text="홍길동 person@example.com 010-1234-5678",
                      content_description="서울시 비밀주소", resource_id="private:id/person@example.com")
    input_node = replace(private, node_id="password", text="디스플레이", class_name="android.widget.EditText",
                         clickable=True, extras={"password": True, "value": 1234})
    request = AgentTurnRequest(request_id="private-request", session_id="private-session", raw_goal=_raw_goal("글자 크기 키워줘"),
        accessibility_tree=(*HOME, private, input_node), screen_bounds=SCREEN)
    engine.handle_turn(request)
    export = recorder.export()
    serialized = json.dumps(export, ensure_ascii=False)
    for secret in ("홍길동", "person@example.com", "010-1234-5678", "서울시", "비밀주소", "private-request", "private-session"):
        assert secret not in serialized
    assert "expected" not in export["turns"][0]
    nodes = export["turns"][0]["observation"]["nodes"]
    assert nodes[0]["node_id"] == nodes[1]["parent_id"]
    assert nodes[-1]["text"] is None and nodes[-1]["extras"] == {}
    assert not evaluate_cases([export], TASK_GRAPHS)["violations"]


def test_optional_capture_cannot_interrupt_runtime_and_is_bounded():
    recorder = ReplayRecorder(GRAPH, max_turns=1)
    engine = engine_for(); engine.replay_recorder = recorder
    for i in range(2):
        engine.handle_turn(AgentTurnRequest(request_id=str(i), session_id="s", raw_goal=_raw_goal("글자 크기 키워줘") if i == 0 else None,
            accessibility_tree=HOME, screen_bounds=SCREEN))
    assert len(recorder.export()["turns"]) == 1
    class BrokenRecorder:
        def record(self, request, response):
            raise ValueError("diagnostic unavailable")
    engine.replay_recorder = BrokenRecorder()
    result = engine.handle_turn(AgentTurnRequest(request_id="next", session_id="s", raw_goal=None,
        accessibility_tree=HOME, screen_bounds=SCREEN))
    assert result.guide_command.target_node_id == "display"


def test_recorder_rejects_mixed_episodes_and_preserves_invalid_action_binding():
    from ai_agent.runtime.types import UserActionEvent, UserActionType
    recorder = ReplayRecorder(GRAPH)
    engine = engine_for()
    request = AgentTurnRequest(request_id="first", session_id="s", episode_id="episode-one",
        raw_goal=_raw_goal("글자 크기 키워줘"), accessibility_tree=HOME, screen_bounds=SCREEN)
    response = engine.handle_turn(request)
    recorder.record(request, response)
    event = UserActionEvent(UserActionType.TAP_COMPLETED, guide_command_id=response.guide_command.guide_command_id,
                           session_id="other-session", occurred_at_ms=1000)
    follow = replace(request, request_id="second", raw_goal=None, user_action_event=event)
    recorder.record(follow, engine.handle_turn(follow))
    assert recorder.export()["turns"][-1]["action"]["binding_valid"] is False
    with pytest.raises(ValueError, match="one episode"):
        recorder.record(replace(follow, episode_id="episode-two"), response)


def test_diagnostic_reason_and_candidates_are_allowlisted_and_idempotent():
    raw = {"recovery": {"reason_code": "TARGET_NOT_VISIBLE", "next_action": "manual_navigation",
                       "waiting_for": "user", "message": "private name"},
           "state_classification": {"candidates": [{"state_id": "S1_SETTINGS_HOME", "score": 0.9,
                                                     "title_match": True, "label": "private name"}]}}
    safe = diagnostic_payload(raw)
    assert safe == diagnostic_payload(safe)
    assert "private name" not in json.dumps(safe)
    assert safe["recovery"]["reason_code"] == "TARGET_NOT_VISIBLE"
    assert safe["state_classification"]["candidates"][0]["state_hash"]
    assert diagnostic_payload({"recovery": {"reason_code": "private name"}}) == {"recovery": {}}
