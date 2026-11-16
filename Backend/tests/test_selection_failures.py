"""Real resolver preflight failures must not become model abstentions."""
from dataclasses import replace
from types import SimpleNamespace

import httpx
import pytest

from ai_agent.runtime.primary import decide_primary
from ai_agent.runtime.state_manager import build_screen_snapshot
from ai_agent.telemetry.trace_logger import diagnostic_payload
from app.clients.checkpoint import capture, restore
from app.services.screen_jev import JevScreenResolver
from tests.test_primary_recovery import Scenario
from tests.test_screen_jev import scene


@pytest.fixture(autouse=True)
def configure(monkeypatch):
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_MIN_CONFIDENCE", "0.8")


def decide(resolver, graph, goal, nodes, *, raw_nodes=()):
    snapshot = build_screen_snapshot("S0_UNKNOWN", nodes, observation_id="o", captured_at_ms=1000)
    return decide_primary(resolver=resolver, graph=graph, goal=goal, nodes=nodes, raw_nodes=raw_nodes,
        request=SimpleNamespace(user_confirmation=None, user_action_event=None, captured_at_ms=1000),
        session=None, snapshot=snapshot, verification=None, attempt=None, exclusions={}, screen_bounds=None)


@pytest.mark.parametrize("case,reason,stage", [
    ("candidate_limit", "CANDIDATE_LIMIT", "candidate_generation"),
    ("observation_limit", "CANDIDATE_LIMIT", "candidate_generation"),
    ("no_candidates", "NO_CANDIDATES", "candidate_generation"),
    ("invalid_candidates", "INVALID_CANDIDATES", "candidate_generation"),
    ("ambiguous_candidates", "TARGET_AMBIGUOUS", "candidate_generation"),
    ("input_too_large", "OBSERVATION_TOO_LARGE", "candidate_generation"),
    ("unsupported_screen", "UNSUPPORTED_SCREEN", "observation"),
    ("not_configured", "MODEL_NOT_CONFIGURED", "configuration"),
    ("deadline_exceeded", "DEADLINE_EXCEEDED", "request_preparation"),
])
def test_preflight_preserves_failure_without_calling_model(monkeypatch, case, reason, stage):
    graph, transition, node, goal = scene("font_size")
    nodes = (node,)
    if case == "candidate_limit":
        nodes = tuple(replace(node, node_id=f"slider-{i}", resource_id=f"native:id/slider_{i}")
                      for i in range(251))
    elif case == "observation_limit":
        nodes += tuple(replace(node, node_id=f"title-{i}", role="text", actionable=False,
            structure_role="screen_text") for i in range(160))
    elif case == "no_candidates":
        nodes = (replace(node, enabled=False),)
    elif case == "invalid_candidates":
        nodes = (node, node)
    elif case == "ambiguous_candidates":
        nodes = (node, replace(node, node_id="duplicate"))
    elif case == "input_too_large":
        graph = replace(graph, states={**graph.states, transition.from_state:
            replace(graph.states[transition.from_state], name="page" * 20000)})
    elif case == "unsupported_screen":
        nodes = (replace(node, label="unrecognized screen"),)
    elif case == "not_configured":
        monkeypatch.delenv("TYPESAFE_API_KEY")
    elif case == "deadline_exceeded":
        def expired(*args):
            raise TimeoutError("selection used the remaining turn budget")
        monkeypatch.setattr("app.services.screen_jev.remaining", expired)
    resolver = JevScreenResolver(lambda *a, **kw: pytest.fail("preflight must not call the provider"))
    result = decide(resolver, graph, goal, nodes)
    assert result.match is None
    assert result.diagnostics["called"] is False
    assert result.recovery_reason == reason
    failure = result.failure.to_dict()
    assert failure["stage"] == stage
    assert failure["retryable"] is True
    assert failure["outcome_uncertain"] is False
    assert failure["reason_code"] != "MODEL_ABSTAINED"
    sanitized = diagnostic_payload({"ui_selection": result.diagnostics})["ui_selection"]
    assert sanitized["failure"] == failure
    if case == "candidate_limit":
        assert sanitized["candidate_count"] == 251
        assert sanitized["limit"] == 250
    elif case == "observation_limit":
        assert sanitized["exported_node_count"] == 161
        assert sanitized["limit"] == 160
    elif case == "input_too_large":
        assert sanitized["payload_bytes"] > 65536


def test_network_failure_requires_connection_repair_and_survives_checkpoint():
    scenario = Scenario(["offline", "selected"])
    result = scenario.observe()
    failure = result.debug["recovery"]["failure"]
    assert failure["stage"] == "provider"
    assert failure["cause"] == "network_error"
    assert failure["required_change"] == "connection_restored"
    assert result.debug["recovery"]["next_action"] == "restore_connection"
    assert "인터넷 연결을 복구" in result.guide_command.message
    checkpoint = capture(scenario.engine.session_store, "s", scenario.graph)
    scenario.engine.session_store = restore(checkpoint)
    for _ in range(5):
        paused = scenario.observe()
        assert paused.debug["ui_selection"]["called"] is False
        assert paused.debug["recovery"]["failure"] == failure
        assert "fallback_action" not in paused.guide_command.render_policy
    assert len(scenario.calls) == 1
    assert scenario.retry().guide_command.target_node_id == "display"
    assert len(scenario.calls) == 2


def test_candidate_overflow_waits_for_a_smaller_observation():
    scenario = Scenario(["selected"])
    # Exercise the runtime with an actual 251-choice production resolver input.
    title, control = scenario.nodes
    many = (title,) + tuple(replace(control, node_id=f"display-{i}",
        resource_id=f"native:id/display_{i}") for i in range(251))
    result = scenario.observe(nodes=many)
    failure = result.debug["recovery"]["failure"]
    assert failure["cause"] == "candidate_limit"
    assert result.debug["recovery"]["next_action"] == "narrow_observation"
    assert result.debug["ui_selection"]["called"] is False
    assert not scenario.calls
    checkpoint = capture(scenario.engine.session_store, "s", scenario.graph)
    scenario.engine.session_store = restore(checkpoint)
    for _ in range(3):
        repeated = scenario.observe(nodes=many)
        assert repeated.debug["ui_selection"]["paused"] is True
        assert repeated.debug["recovery"]["attempts"] == 1
        assert "fallback_action" not in repeated.guide_command.render_policy
    # The retry button alone must not release the unchanged preflight failure.
    scenario.nodes = many
    assert scenario.retry().debug["ui_selection"]["paused"] is True
    assert not scenario.calls
    narrowed = scenario.observe(nodes=(title, control))
    assert narrowed.guide_command.target_node_id == "display"
    assert len(scenario.calls) == 1


def test_pending_action_uncertainty_is_not_model_abstention():
    from ai_agent.runtime import UserActionEvent, UserActionType
    scenario = Scenario(["selected"])
    guide = scenario.observe().guide_command
    result = scenario.observe(event=UserActionEvent(UserActionType.TAP_COMPLETED,
        guide.guide_command_id, "display", occurred_at_ms=1100,
        event_id="tap", session_id="s"), at=1200)
    failure = result.debug["ui_selection"]["failure"]
    assert failure["stage"] == "verification"
    assert failure["outcome_uncertain"] is True
    assert failure["reason_code"] == "WAITING_FOR_SETTLE"
    assert len(scenario.calls) == 1


def test_unknown_or_uncalled_status_cannot_claim_model_abstention():
    graph, _, node, goal = scene("font_size")
    for status, called in (("abstained", False), ("future_status", True)):
        resolver = SimpleNamespace(resolve=lambda **kw: None,
            last_diagnostics={"status": status, "called": called})
        result = decide(resolver, graph, goal, (node,))
        assert result.recovery_reason == "SELECTION_FAILED"
        assert result.failure.retryable is False


def test_malformed_reply_is_a_response_failure_and_not_a_model_decision():
    graph, _, node, goal = scene("font_size")
    resolver = JevScreenResolver(lambda *a, **kw: httpx.Response(200, json={"private": "never log this"}))
    result = decide(resolver, graph, goal, (node,))
    assert result.diagnostics["called"] is True
    assert result.failure.stage == "model_response"
    assert result.failure.cause == "invalid_response"
    assert result.failure.required_change == "valid_model_response"
    safe = diagnostic_payload({"ui_selection": {**result.diagnostics,
        "failure": {**result.failure.to_dict(), "private": "never log this"}}})
    assert safe["ui_selection"]["failure"] == result.failure.to_dict()
    assert "never log this" not in str(safe)


def test_engine_observation_guard_preserves_the_stage_without_calling_a_model():
    scenario = Scenario(["selected"])
    unsupported = replace(scenario.nodes[0], text="unrecognized screen")
    result = scenario.observe(nodes=(unsupported,))
    failure = result.debug["recovery"]["failure"]
    assert failure["stage"] == "observation"
    assert failure["cause"] == "unsupported_screen"
    assert result.debug["ui_selection"]["called"] is False
    assert result.guide_command.render_policy["recovery"]["failure"] == failure
    assert not scenario.calls
