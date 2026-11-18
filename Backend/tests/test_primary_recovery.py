"""End-to-end primary decisions through runtime state and the production Jev adapter."""
from tests.jev_answers import with_screen_judgments
from dataclasses import replace

import httpx
import pytest

from app.clients.checkpoint import capture, restore
from app.services.screen_jev import JevScreenResolver
from ai_agent.runtime import ClickAgentEngine, UserActionEvent, UserActionType
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, DeviceContext
from tests.test_screen_jev import scene


class Scenario:
    def __init__(self, outcomes):
        self.graph, _, _, self.goal = scene("font_size")
        self.edge = next(t for t in self.graph.transitions if t.from_state == "S1_SETTINGS_HOME")
        self.nodes = (
            AccessibilityNode("title", text="설정", package_name="com.android.settings", window_id=7,
                class_name="android.widget.TextView", bounds_in_screen=(0, 0, 1000, 80)),
            AccessibilityNode("display", text="디스플레이", clickable=True, window_id=7,
                package_name="com.android.settings", class_name="android.widget.TextView",
                bounds_in_screen=(10, 100, 1000, 200)),
        )
        self.outcomes, self.calls, self.turn = list(outcomes), [], 0
        self.resolver = JevScreenResolver(self.send)
        self.engine = ClickAgentEngine(task_graphs={self.goal.task_id: self.graph}, screen_resolver=self.resolver)

    def send(self, payload, **kwargs):
        self.calls.append(payload)
        outcome = self.outcomes[min(len(self.calls) - 1, len(self.outcomes) - 1)]
        if outcome == "offline":
            raise httpx.ConnectError("synthetic unavailable provider")
        criteria = payload["questions"]["screen"]["criteria"]
        candidate = next((k for k, v in criteria.items() if isinstance(v, dict)
                          and v["transition"] == self.edge.transition_id), "insufficient_evidence")
        if isinstance(outcome, tuple):
            raw, probability = outcome
            choice = candidate
        else:
            raw, probability = 1, 1
            choice = candidate if outcome == "selected" else outcome
        return httpx.Response(200, json=with_screen_judgments(payload, {"model": payload["model"], "answers": {"screen": {
            "type": "choice", "choice": choice, "confidence": raw,
            "probabilities": {k: probability if k == choice else (1 - probability) / (len(criteria) - 1)
                              for k in criteria}}}}))

    def observe(self, *, nodes=None, event=None, confirmation=None, at=None):
        self.turn += 1
        return self.engine.handle_turn(AgentTurnRequest(
            request_id=f"r{self.turn}", session_id="s", raw_goal=None,
            typed_goal=self.goal if self.engine.session_store.get("s") is None else None,
            accessibility_tree=self.nodes if nodes is None else nodes,
            device_context=DeviceContext("android"), screen_bounds=(0, 0, 1080, 2400),
            active_window_id=7, observation_id=f"o{self.turn}",
            captured_at_ms=at if at is not None else self.turn * 1000,
            user_action_event=event, user_confirmation=confirmation))

    def retry(self):
        return self.observe(event=UserActionEvent(UserActionType.MANUAL_OVERRIDE,
            occurred_at_ms=self.turn * 1000 + 500, session_id="s"))


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_MIN_CONFIDENCE", "0.8")


def test_accepted_display_guide_survives_the_reported_confidence_drop_and_checkpoint_restore():
    scenario = Scenario([(.88, .92), (.72, .81), (.72, .81), (.68, .79), (.73, .82)])
    first = scenario.observe()
    assert first.guide_command.target_node_id == "display"
    # Production reconstructs the runtime from a persisted checkpoint on each request.
    for _ in range(8):
        state = capture(scenario.engine.session_store, "s", scenario.graph)
        scenario.engine = ClickAgentEngine(session_store=restore(state),
            task_graphs={scenario.goal.task_id: scenario.graph}, screen_resolver=scenario.resolver)
        result = scenario.observe()
        assert result.guide_command.target_node_id == "display"
        assert result.guide_command.guide_command_id == first.guide_command.guide_command_id
        assert result.debug["ui_selection"]["retained"] is True
        assert result.debug["ui_selection"]["called"] is False
    assert len(scenario.calls) == 1
    assert scenario.engine.session_store.get("s").total_failed_attempts == 0


@pytest.mark.parametrize("outcome,reason", [
    ((.72, .81), "LOW_CONFIDENCE"),
    ("insufficient_evidence", "MODEL_ABSTAINED"),
    ("wait_for_result", "MODEL_WAIT_WITHOUT_ACTION"),
    ("offline", "MODEL_UNAVAILABLE"),
])
def test_provider_holds_require_a_change_before_retry(outcome, reason):
    scenario = Scenario([outcome])
    for turn in range(scenario.graph.policy.recovery_limit):
        result = scenario.observe()
        recovery = result.guide_command.render_policy["recovery"]
        assert result.guide_command.target_node_id is None
        assert recovery["reason_code"] == reason
        assert recovery["attempts"] == 1
        assert result.debug["recovery"] == recovery
    assert recovery["waiting_for"] == "user"
    assert "fallback_action" not in result.guide_command.render_policy
    for _ in range(4):
        result = scenario.observe()
        assert result.debug["ui_selection"]["called"] is False
        assert result.debug["ui_selection"]["paused"] is True
        assert "fallback_action" not in result.guide_command.render_policy
    assert len(scenario.calls) == 1
    assert scenario.engine.session_store.get("s").total_failed_attempts == 0
    scenario.outcomes.append("selected")
    if outcome == "offline":
        assert recovery["failure"]["required_change"] == "connection_restored"
        resumed = scenario.retry()
    else:
        # Pressing retry does not answer a selection ambiguity.
        unchanged = scenario.retry()
        assert unchanged.debug["ui_selection"]["paused"] is True
        assert len(scenario.calls) == 1
        moved = scenario.observe(nodes=(scenario.nodes[0], replace(scenario.nodes[1],
            bounds_in_screen=(10, 300, 1000, 400))))
        assert moved.debug["ui_selection"]["paused"] is True
        assert len(scenario.calls) == 1
        resumed = scenario.observe(nodes=(scenario.nodes[0], replace(scenario.nodes[1],
            resource_id="android:id/replaced_target", bounds_in_screen=(10, 300, 1000, 400))))
    assert resumed.guide_command.target_node_id == "display"
    assert resumed.debug["ui_selection"]["called"] is True
    assert resumed.debug["recovery"]["decision"] == "continue"


@pytest.mark.parametrize("outcome,hold_reason", [
    ((.72, .95), "raw_confidence_below_minimum"),
    ((.95, .81), "normalized_probability_below_minimum"),
    ((.72, .81), "both_below_minimum"),
])
def test_low_confidence_after_revalidation_stays_bounded_across_checkpoint_restore(outcome, hold_reason):
    scenario = Scenario(["selected", outcome])
    first = scenario.observe()
    assert first.guide_command.target_node_id == "display"
    # A moved target requires revalidation instead of retaining the first guide.
    nodes = (scenario.nodes[0], replace(scenario.nodes[1], bounds_in_screen=(10, 300, 1000, 400)))
    for turn in range(1, 11):
        state = capture(scenario.engine.session_store, "s", scenario.graph)
        scenario.engine = ClickAgentEngine(session_store=restore(state),
            task_graphs={scenario.goal.task_id: scenario.graph}, screen_resolver=scenario.resolver)
        result = scenario.observe(nodes=nodes)
        recovery = result.guide_command.render_policy["recovery"]
        assert result.guide_command.target_node_id is None
        assert recovery["reason_code"] == "LOW_CONFIDENCE"
        assert recovery["attempts"] == 1
        assert result.debug["ui_selection"]["hold_reason"] == hold_reason
        assert scenario.engine.session_store.get("s").total_failed_attempts == 0
        assert recovery["waiting_for"] == "user"
        assert recovery["failure"]["required_change"] == "user_clarification"
        assert "fallback_action" not in result.guide_command.render_policy
        if turn > 1:
            assert result.debug["ui_selection"]["called"] is False
    assert len(scenario.calls) == 2


def test_changed_evidence_releases_failed_action_without_resetting_spent_action_budget():
    scenario = Scenario(["selected"])
    first = scenario.observe()
    event = UserActionEvent(UserActionType.TAP_COMPLETED,
        first.guide_command.guide_command_id, "display", occurred_at_ms=1100,
        event_id="tap", session_id="s")
    scenario.observe(event=event, at=1200)
    failed = scenario.observe(at=2000)
    assert scenario.engine.session_store.get("s").total_failed_attempts == 1
    old_attempt = first.guide_command.render_policy["attempt_id"]
    for at in (3000, 4000, 5000):
        scenario.observe(at=at)
    unchanged = scenario.retry()
    assert unchanged.guide_command.target_node_id is None
    assert unchanged.debug["ui_selection"]["paused"] is True
    resumed = scenario.observe(nodes=(scenario.nodes[0], replace(scenario.nodes[1],
        resource_id="android:id/replaced_target", bounds_in_screen=(10, 300, 1000, 400))))
    assert resumed.guide_command.target_node_id == "display"
    assert resumed.guide_command.render_policy["attempt_id"] != old_attempt
    assert resumed.debug["ui_selection"]["called"] is True
    assert scenario.engine.session_store.get("s").total_failed_attempts == 1
    assert scenario.engine.session_store.get("s").transition_attempts


@pytest.mark.parametrize("change", ["moved", "disabled", "hidden", "window", "resource", "missing"])
def test_changed_target_cannot_reuse_a_previous_guide(change):
    scenario = Scenario(["selected", (.72, .81)])
    first = scenario.observe()
    title, node = scenario.nodes
    if change == "missing":
        nodes = (title,)
    else:
        changed = {
            "moved": {"bounds_in_screen": (10, 300, 1000, 400)},
            "disabled": {"enabled": False},
            "hidden": {"visible_to_user": False},
            "window": {"window_id": 8},
            "resource": {"resource_id": "android:id/new_target"},
        }[change]
        nodes = (title, replace(node, **changed))
    result = scenario.observe(nodes=nodes)
    assert result.guide_command.target_node_id is None
    assert result.guide_command.guide_command_id != first.guide_command.guide_command_id
    assert not result.debug["ui_selection"].get("retained")


def test_rejection_overrides_a_retained_target_and_stale_retries_do_not_unlock_recovery():
    scenario = Scenario(["selected"])
    scenario.observe()
    cancelled = scenario.observe(confirmation="rejected")
    assert cancelled.guide_command.command_type.value == "cancelled"
    assert cancelled.guide_command.render_policy["termination"]["code"] == "USER_DECLINED"
    assert cancelled.guide_command.target_node_id is None
    assert scenario.engine.session_store.get("s").pending_attempt is None
    assert len(scenario.calls) == 1

    scenario = Scenario([(.72, .81)])
    for _ in range(3):
        scenario.observe()
    for session, at in (("other", 3500), ("s", 1000)):
        result = scenario.observe(event=UserActionEvent(UserActionType.MANUAL_OVERRIDE,
            session_id=session, occurred_at_ms=at))
        assert result.debug["ui_selection"]["paused"] is True
    assert len(scenario.calls) == 1


def test_jev_payload_separates_a_displayed_plan_from_an_executed_action():
    scenario = Scenario(["selected"])
    first = scenario.observe()
    # Change layout to require a new model decision, while no tap has occurred.
    scenario.observe(nodes=(scenario.nodes[0], replace(scenario.nodes[1],
        bounds_in_screen=(10, 300, 1000, 400))))
    payload = scenario.calls[-1]["state"]
    assert payload["planned_action"] == {"transition_id": scenario.edge.transition_id}
    assert payload["previous_action"] is None
    assert payload["result"]["status"] == "WAITING_FOR_ACTION"

    event = UserActionEvent(UserActionType.TAP_COMPLETED,
        scenario.engine.session_store.get("s").last_guide_command_id, "display",
        occurred_at_ms=2100, event_id="tap", session_id="s")
    # A new observed layout lets the primary resolver inspect the executed action.
    scenario.observe(event=event, nodes=scenario.nodes)
    assert scenario.calls[-1]["state"]["previous_action"]["event_received"] is True


def test_navigation_and_progress_commit_session_state_once_per_turn(monkeypatch):
    scenario = Scenario(["selected"])
    scenario.observe()
    store = scenario.engine.session_store
    updates = []
    original = store.update
    def update(*args, **kwargs):
        updates.append(kwargs)
        return original(*args, **kwargs)
    monkeypatch.setattr(store, "update", update)
    scenario.observe(event=UserActionEvent(UserActionType.BACK_NAVIGATED,
        occurred_at_ms=1500, event_id="back", session_id="s"))
    assert len(updates) == 1
    assert "progress_steps" in updates[0]


def test_retained_slider_confirmation_never_grants_approval_or_executes_a_drag():
    from tests.test_jev_primary import setup_slider
    _, _, engine, resolver, request = setup_slider()
    first = engine.handle_turn(request)
    assert first.guide_command.command_type.value == "confirmation"
    resolver._sender = lambda *a, **kw: pytest.fail("unchanged pending confirmation must not call Jev")
    for turn in range(2, 5):
        result = engine.handle_turn(replace(request, request_id=f"r{turn}", observation_id=f"o{turn}",
            captured_at_ms=1000 * turn))
        assert result.guide_command.command_type.value == "confirmation"
        assert result.guide_command.action_type.value == "observe_only"
        assert result.guide_command.guide_command_id == first.guide_command.guide_command_id
        assert engine.session_store.get("s").pending_attempt is None
        assert engine.session_store.get("s").pending_selection is not None
    approved = engine.handle_turn(replace(request, request_id="approved", observation_id="approved",
        captured_at_ms=5000, user_confirmation="accepted"))
    assert approved.guide_command.action_type.value == "drag"
    assert engine.session_store.get("s").pending_attempt is not None


def test_rule_state_mismatch_cannot_turn_visible_low_confidence_into_an_ocr_loop(monkeypatch):
    import ai_agent.runtime.engine as runtime
    classify = runtime.classify_screen_state
    def wrong_state(*args, **kwargs):
        classify(*args, **kwargs)
        return "S2_DISPLAY_MENU"
    monkeypatch.setattr(runtime, "classify_screen_state", wrong_state)
    scenario = Scenario([(.72, .81)])
    for _ in range(scenario.graph.policy.recovery_limit):
        result = scenario.observe()
        recovery = result.guide_command.render_policy["recovery"]
        assert recovery["reason_code"] == "LOW_CONFIDENCE"
        assert "vision_fallback" not in result.guide_command.render_policy
    assert recovery["waiting_for"] == "user"
    scenario.observe()
    assert len(scenario.calls) == 1


@pytest.mark.parametrize("outcome,failed", [("selected", False), ((.72, .81), True)])
def test_graph_replay_uses_the_production_jev_decision_path(monkeypatch, outcome, failed):
    from scripts.agent2 import replay_evaluator
    from app.clients.runtime_agent import runtime_configuration
    from app.repositories.task_graphs import canonical_graph
    from ai_agent.runtime.replay import SCHEMA

    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    scenario = Scenario([outcome])
    monkeypatch.setattr(JevScreenResolver, "_post", lambda self, payload, **kw: scenario.send(payload, **kw))
    body, digest = canonical_graph(scenario.graph)
    case = {"schema_version": SCHEMA, "task_id": scenario.goal.task_id, "graph_hash": digest,
        "turns": [{"observation": {"nodes": [n.to_dict() for n in scenario.nodes],
            "screen_bounds": [0, 0, 1080, 2400], "active_window_id": 7,
            "observation_id": "screen", "captured_at_ms": 1000},
            "expected": {"target_node_id": "display"}}]}
    result = replay_evaluator(body, {"replay": case})
    assert len(scenario.calls) == 1
    assert bool(result["failures"]) is failed
    assert result["runtime_configuration"] == runtime_configuration()
    assert result["runtime_configuration"]["provider"] == "jev"
