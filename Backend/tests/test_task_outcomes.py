"""End-to-end goal decisions, device evidence, and task-specific result answers."""
from copy import deepcopy
from uuid import uuid4
import pytest
from sqlalchemy import select
from app.main import create_app
from app.models.agent_session import AgentSession, AgentCheckpoint
from app.models.task_graph import ApprovedTaskGraph
from tests.helpers import authenticated_client
from tests.test_agent_turn_api import slider_tree

PATH = "/api/v1/agent/commands"


def screen(value=2, at=1000, scale=1.0):
    tree = slider_tree(value)
    tree.update(observation_id=f"o-{at}", captured_at_ms=at)
    tree["nodes"][0]["extras"].update(min=0, max=7)
    if scale is not None:
        tree["applied_settings"] = {"source": "android.provider.Settings.System.FONT_SCALE", "font_scale": scale}
    return tree


class Flow:
    def __init__(self):
        self.app = create_app()
        self.client = authenticated_client(self.app)
        self.session = str(uuid4())
        self.body = {"session_version": 0, "episode_id": None}

    def payload(self, command, **kwargs):
        body = {"contract_version": "agent-fe-contract.v0.3", "request_id": str(uuid4()),
            "session_id": self.session, "expected_version": self.body["session_version"], "command_type": command}
        if self.body.get("episode_id"):
            body["episode_id"] = self.body["episode_id"]
        body.update(kwargs)
        return body

    def send(self, command, **kwargs):
        response = self.client.post(PATH, json=self.payload(command, **kwargs))
        assert response.status_code == 200, response.text
        self.body = response.json()
        return self.body

    def resolve(self, text="글자가 잘 안 보여", **kwargs):
        if self.client.get(f"/api/v1/agent/sessions/{self.session}").status_code == 404:
            queued = self.client.post("/api/v1/voice-requests/enqueue", json={"id": str(uuid4()), "raw_goal": text})
            assert queued.status_code == 200, queued.text
            self.session = self.client.post("/api/v1/voice-requests/next/claim").json()["running"]["agent_session_id"]
        self.source_text = text
        return self.send("resolve_goal", source_text=text, preparation_version="goal-inputs.v1",
            device_context={"platform": "android", "locale": "ko-KR"}, **kwargs)

    def answer_input(self, value):
        prepared = self.body["preparation"]
        return self.resolve(self.source_text, goal_resolution_id=prepared["id"],
            clarification_answer={"question_id": prepared["question"]["id"], "value": value})

    def prepare(self, text="글자가 잘 안 보여", direction="increase"):
        body = self.resolve(text)
        while body["preparation"]["question"]:
            field = body["preparation"]["question"]["field"]
            assert field in {"scope", "direction"}
            body = self.answer_input({"scope": "system", "direction": direction}[field])
        assert body["preparation"]["status"] == "ready"
        return body

    def start(self, value=2, scale=1.0):
        ready = self.prepare()
        return self.send("start_task", preparation_version="goal-inputs.v1",
            goal_resolution_id=ready["preparation"]["id"], goal=ready["goal"], source_text=self.source_text,
            device_context={"platform": "android", "locale": "ko-KR"}, accessibility_tree=screen(value, scale=scale))

    def accept(self, value=2, scale=1.0):
        return self.send("respond_confirmation", user_confirmation="accepted",
            confirmation_id=self.body["metadata"]["confirmation"]["id"], accessibility_tree=screen(value, 1100, scale))

    def event(self, **kwargs):
        guide = self.body["guide_command"]
        result = {"event_id": str(uuid4()), "action_type": "drag_completed", "occurred_at_ms": 1200,
            "guide_command_id": guide["guide_command_id"], "target_node_id": guide["target_node_id"],
            "target_bounds": guide["target_bounds"], "session_id": self.session, "episode_id": self.body["episode_id"]}
        result.update(kwargs)
        return result

    def change(self):
        self.start(); self.accept()
        return self.send("observe", accessibility_tree=screen(3, 1800, 1.15), user_action_event=self.event())

    def answer(self, answer):
        return self.send("respond_result", result_prompt_id=self.body["metadata"]["result_prompt"]["id"], user_result=answer)


def test_ambiguous_goal_asks_scope_and_direction_before_bound_graph_selection():
    f = Flow(); body = f.resolve()
    assert body["goal"] is None
    assert body["preparation"]["question"]["field"] == "scope"
    assert f.answer_input("system")["preparation"]["question"]["field"] == "direction"
    ready = f.answer_input("increase")
    assert ready["goal"]["slots"]["direction"] == "increase"
    with f.app.state.session_factory() as db:
        reserved = db.get(AgentSession, f.session)
        assert reserved.version == 0 and reserved.episode_id is None
    assert f.resolve("휴대폰 전체 글자 크기 줄여줘")["goal"]["slots"]["direction"] == "decrease"


def test_goal_candidates_require_current_approved_active_graph_and_bind_session():
    f = Flow()
    with f.app.state.session_factory() as db:
        graph = db.scalar(select(ApprovedTaskGraph).where(ApprovedTaskGraph.goal_type == "settings.font_size.decrease"))
        graph.active = False; db.commit()
    unsupported = f.resolve("휴대폰 전체 글자 크기 줄여줘")
    assert unsupported["preparation"]["status"] == "unsupported" and unsupported["goal"] is None
    ready = f.resolve("휴대폰 전체 글자 크기 키워줘")
    payload = f.payload("start_task", preparation_version="goal-inputs.v1", goal=ready["goal"],
        goal_resolution_id=ready["preparation"]["id"], device_context={"platform": "android", "locale": "ko-KR"},
        accessibility_tree=screen())
    payload["session_id"] = "unrelated"
    assert f.client.post(PATH, json=payload).status_code == 404


def test_guidance_does_not_tick_steps_and_verified_change_waits_for_user():
    f = Flow(); first = f.start()
    assert not any(s["status"] == "completed" for s in first["metadata"]["checklist"])
    f.accept()
    result = f.send("observe", accessibility_tree=screen(3, 1800, 1.15), user_action_event=f.event())
    assert result["task_status"] == "WAITING_RESULT"
    assert "키웠어요" in result["metadata"]["result_prompt"]["message"]
    assert result["metadata"]["task_outcome"]["technical"]["status"] == "CHANGED"
    assert result["metadata"]["task_outcome"]["user"]["status"] is None
    done = f.answer("satisfied")
    assert done["task_status"] == "COMPLETED"
    assert done["metadata"]["task_outcome"]["final"] == "ACHIEVED"
    stored = f.client.get(f"/api/v1/agent/sessions/{f.session}").json()["task_outcome"]
    assert stored["technical"]["verified_at_ms"] == 1800
    assert stored["user"]["confirmed_at"] is not None


@pytest.mark.parametrize("problem", ["wrong_target", "wrong_episode", "stale", "unchanged", "missing_setting", "preview_only", "no_action"])
def test_insufficient_or_unrelated_evidence_never_completes(problem):
    f = Flow(); f.start(); f.accept()
    event, tree = f.event(), screen(3, 1800, 1.15)
    if problem == "wrong_target": event["target_node_id"] = "brightness"
    if problem == "wrong_episode": event["episode_id"] = "old-episode"
    if problem == "stale": tree.update(observation_id="o-1100", captured_at_ms=1100)
    if problem == "unchanged": tree = screen(2, 1800, 1.0)
    if problem == "missing_setting": tree.pop("applied_settings")
    if problem == "preview_only": tree["applied_settings"]["font_scale"] = 1.0
    kwargs = {"accessibility_tree": tree}
    if problem != "no_action": kwargs["user_action_event"] = event
    body = f.send("observe", **kwargs)
    assert body["metadata"]["task_outcome"]["technical"]["status"] != "CHANGED"
    assert body["task_status"] != "COMPLETED"


def test_unverifiable_positive_report_is_not_technical_success():
    f = Flow(); f.start()
    waiting = f.send("request_result")
    assert "변경 결과를 확인하지 못했어요" in waiting["metadata"]["result_prompt"]["message"]
    done = f.answer("satisfied")
    assert done["metadata"]["task_outcome"]["technical"]["status"] == "UNVERIFIABLE"
    assert done["metadata"]["task_outcome"]["final"] == "USER_REPORTED_RESOLVED"


def test_needs_help_checks_cause_then_new_episode_and_keeps_previous_outcome():
    f = Flow(); f.change(); old_episode = f.body["episode_id"]
    help_needed = f.answer("needs_help")
    assert help_needed["task_status"] == "WAITING_ADJUSTMENT"
    assert help_needed["metadata"]["task_outcome"]["technical"]["status"] == "CHANGED"
    assert f.send("adjust_task", adjustment_reason="other")["task_status"] == "WAITING_ADJUSTMENT"
    adjusted = f.send("adjust_task", adjustment_reason="too_small")
    assert adjusted["task_status"] == "IN_PROGRESS" and adjusted["episode_id"] != old_episode
    assert f.send("observe", accessibility_tree=screen(3, 2500, 1.15))["task_status"] == "WAITING_CONFIRMATION"
    session = f.client.get(f"/api/v1/agent/sessions/{f.session}").json()
    assert session["outcome_history"][0]["outcome"]["user"]["status"] == "needs_help"


def test_maximum_does_not_issue_drag_or_offer_same_direction():
    f = Flow(); result = f.start(value=7, scale=2.0)
    assert result["task_status"] == "WAITING_RESULT"
    assert result["guide_command"]["action_type"] == "observe_only"
    assert result["metadata"]["task_outcome"]["technical"]["reason"] == "limit_reached"
    f.answer("needs_help")
    assert "too_small" not in {c["id"] for c in f.body["metadata"]["follow_up"]["choices"]}
    assert f.client.post(PATH, json=f.payload("adjust_task", adjustment_reason="too_small")).status_code == 409


def test_no_response_expiry_and_cancel_preserve_technical_axis():
    f = Flow(); f.change()
    deadline = f.body["metadata"]["result_prompt"]["expires_at"]
    assert f.client.post(PATH, json=f.payload("respond_result", user_result="no_response",
        result_prompt_id=f.body["metadata"]["result_prompt"]["id"])).status_code == 409
    f.app.state.turn_service.repository.clock = lambda: deadline + 1
    body = f.answer("no_response")
    assert body["task_status"] == "EXPIRED"
    assert body["metadata"]["task_outcome"]["user"]["status"] == "no_response"
    assert body["metadata"]["task_outcome"]["technical"]["status"] == "CHANGED"
    other = Flow(); other.change(); late = other.payload("observe", accessibility_tree=screen(3, 2000, 1.15))
    cancelled = other.send("cancel_task")
    assert cancelled["metadata"]["task_outcome"]["user"]["status"] == "stopped"
    assert cancelled["metadata"]["task_outcome"]["technical"]["status"] == "CHANGED"
    assert other.client.post(PATH, json=late).status_code == 409


def test_pause_result_and_resume_keep_result_prompt_and_no_false_done():
    f = Flow(); f.change(); f.send("pause_task")
    assert f.body["task_status"] == "PAUSED"
    resumed = f.send("resume_task", accessibility_tree=screen(3, 2000, 1.15))
    assert resumed["task_status"] == "WAITING_RESULT"
    assert resumed["metadata"]["result_prompt"]
    assert f.answer("satisfied")["task_status"] == "COMPLETED"


def test_real_voice_queue_reservation_can_resolve_and_wait_for_result():
    f = Flow()
    queued = f.client.post("/api/v1/voice-requests/enqueue", json={"id": str(uuid4()), "raw_goal": "글자가 잘 안 보여"})
    assert queued.status_code == 200
    claimed = f.client.post("/api/v1/voice-requests/next/claim").json()["running"]
    f.session = claimed["agent_session_id"]
    f.change()
    queue = f.client.get("/api/v1/voice-requests").json()
    assert queue["running"]["agent_session_id"] == f.session
    assert f.client.post(f"/api/v1/voice-requests/{claimed['id']}/complete").status_code == 409
    f.answer("satisfied")
    assert f.client.post(f"/api/v1/voice-requests/{claimed['id']}/complete").status_code == 200


def test_cleanup_records_no_response_and_retains_verified_evidence():
    f = Flow(); f.change()
    deadline = f.body["metadata"]["result_prompt"]["expires_at"]
    f.app.state.turn_service.repository.clock = lambda: deadline + 1
    f.app.state.turn_service.repository.cleanup()
    outcome = f.client.get(f"/api/v1/agent/sessions/{f.session}").json()["task_outcome"]
    assert outcome["technical"]["status"] == "CHANGED"
    assert outcome["user"]["status"] == "no_response"


def test_duplicate_result_is_idempotent_and_cannot_overwrite_a_later_episode():
    f = Flow(); f.change()
    payload = f.payload("respond_result", user_result="needs_help", result_prompt_id=f.body["metadata"]["result_prompt"]["id"])
    first = f.client.post(PATH, json=payload)
    assert first.status_code == 200
    assert f.client.post(PATH, json=payload).json() == first.json()
    f.body = first.json()
    f.send("adjust_task", adjustment_reason="too_large")
    assert f.client.post(PATH, json=payload).status_code == 409


def test_decrease_and_minimum_use_same_directional_contract():
    f = Flow(); selection = f.prepare("글자 크기를 줄여줘", direction="decrease")
    f.send("start_task", preparation_version="goal-inputs.v1", goal_resolution_id=selection["preparation"]["id"], goal=selection["goal"],
        device_context={"platform": "android", "locale": "ko-KR"}, accessibility_tree=screen(2, 1000, 1.2))
    f.accept(scale=1.2)
    changed = f.send("observe", accessibility_tree=screen(1, 1800, 1.0), user_action_event=f.event())
    assert changed["metadata"]["task_outcome"]["technical"]["status"] == "CHANGED"
    assert "줄였어요" in changed["metadata"]["result_prompt"]["message"]
    minimum = Flow(); selection = minimum.prepare("글자 크기 줄여줘", direction="decrease")
    result = minimum.send("start_task", preparation_version="goal-inputs.v1", goal_resolution_id=selection["preparation"]["id"], goal=selection["goal"],
        device_context={"platform": "android", "locale": "ko-KR"}, accessibility_tree=screen(0, 1000, .85))
    assert result["task_status"] == "WAITING_RESULT"
    assert result["metadata"]["task_outcome"]["technical"]["limit_direction"] == "decrease"


def test_verified_change_to_maximum_does_not_offer_another_increase():
    f = Flow(); f.start(value=6); f.accept(value=6)
    result = f.send("observe", accessibility_tree=screen(7, 1800, 1.3), user_action_event=f.event())
    assert result["metadata"]["task_outcome"]["technical"]["status"] == "CHANGED"
    assert result["metadata"]["task_outcome"]["technical"]["limit_direction"] == "increase"
    answer = f.answer("needs_help")
    assert all(c["id"] != "too_small" for c in answer["metadata"]["follow_up"]["choices"])


def test_uncorrelated_change_to_maximum_stays_unverifiable():
    f = Flow(); f.start(value=6); f.accept(value=6)
    result = f.send("observe", accessibility_tree=screen(7, 1800, 1.3))
    assert result["task_status"] == "WAITING_RESULT"
    assert result["metadata"]["task_outcome"]["technical"]["status"] == "UNVERIFIABLE"
    assert "키웠어요" not in result["metadata"]["result_prompt"]["message"]
