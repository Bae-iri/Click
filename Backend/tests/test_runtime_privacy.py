from datetime import datetime, timedelta, timezone
import json
from threading import Event
from time import monotonic
from urllib.request import Request

from fastapi.testclient import TestClient
import pytest
from pydantic import ValidationError
from sqlalchemy import select

from app.main import create_app
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.clients.turn_executor import execute
from app.models.agent_session import AgentTurn
from app.models.personalization import UIEvent
from app.schemas.personalization import CreateUIEventRequest
from tests.helpers import register
from tests.test_agent_sessions import payload, post, session
from ai_agent.runtime.privacy import sanitize_request
from ai_agent.runtime.types import (AccessibilityNode, AgentTurnRequest, RawGoalInput,
    DeviceContext, VisionSnapshot, VisionElement)
from ai_agent.telemetry.trace_logger import TraceLogger
from ai_agent.fallback.llm_vlm_fallback import LLMVLMFallback
from ai_agent.runtime.similarity import GeminiSimilarityScorer
from ai_agent.runtime.budget import deadline
from ai_agent.runtime.progress import graph_progress
from ai_agent.runtime.tasks import TASK_GRAPHS


@pytest.mark.parametrize("values", [[], ["private"], [float("nan")], {"private": 1}])
def test_malformed_embeddings_use_fallback(monkeypatch, values):
    class Response:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def read(self, size): return json.dumps({"embedding": {"values": values}}).encode()
    monkeypatch.setattr("ai_agent.runtime.similarity.urlopen", lambda *a, **k: Response())
    assert 0 <= GeminiSimilarityScorer("fake").score("글자 크기", "글꼴 크기") <= 1


def test_capacity_rejects_new_work_until_timed_out_worker_exits(monkeypatch):
    from threading import BoundedSemaphore
    from fastapi import HTTPException
    release = Event()
    semaphore = BoundedSemaphore(1)
    monkeypatch.setattr("app.clients.turn_executor._slots", semaphore)
    try:
        with pytest.raises(HTTPException) as timeout:
            execute(lambda: release.wait(5), 0.01)
        assert timeout.value.status_code == 504
        with pytest.raises(HTTPException) as busy:
            execute(lambda: None, 1)
        assert busy.value.status_code == 503
    finally:
        release.set()
        assert semaphore.acquire(timeout=5)
        semaphore.release()


def test_event_screen_name_and_safe_metrics():
    with pytest.raises(ValidationError):
        CreateUIEventRequest(event_type="task_completed", screen_name="private title")
    state = next(iter(next(iter(TASK_GRAPHS.values())).states))
    assert CreateUIEventRequest(event_type="task_completed", screen_name=state,
        metadata={"step": 1, "duration_ms": 100, "explanation_depth": "concise"}).metadata["step"] == 1


def test_sensitive_input_removed_before_runtime():
    request = AgentTurnRequest("r", "s", RawGoalInput("글자 크기 test@example.com 010-1234-5678", "ko", DeviceContext("android")), (
        AccessibilityNode("password", class_name="android.widget.EditText", text="secret_name",
                          content_description="another_secret", extras={"value": 123456}, clickable=True),
        AccessibilityNode("slider", text="글자 크기 010-1234-5678", extras={"progress": 3, "secret": "hidden"}),
    ), vision_snapshot=VisionSnapshot(user_consented=True, screenshot_base64="RAW",
        elements=(VisionElement("ocr", "test@example.com"),)))
    safe = sanitize_request(request)
    encoded = json.dumps(safe.to_dict(), ensure_ascii=False)
    for secret in ("test@example.com", "010-1234-5678", "secret_name", "another_secret", "hidden", "RAW"):
        assert secret not in encoded
    assert not safe.accessibility_tree[0].enabled
    assert safe.accessibility_tree[1].extras == {"progress": 3}
    assert request.accessibility_tree[0].text == "secret_name"


def test_sensitive_screen_data_not_in_http_or_persistent_payloads():
    from app.models.agent_session import AgentCheckpoint
    value = payload()
    value["raw_goal"] += " test@example.com 010-1234-5678"
    value["accessibility_tree"]["nodes"].append({
        "node_id": "private-input", "class_name": "android.widget.EditText",
        "text": "arbitrary_private_value", "isPassword": True,
        "content_description": "private_description", "bounds_in_screen": [0, 0, 10, 10],
        "extras": {"unknown": "private_extra"}})
    app = create_app()
    with TestClient(app) as client:
        register(client)
        response = post(client, value)
        assert response.status_code == 200
        with app.state.session_factory() as db:
            checkpoint = db.scalar(select(AgentCheckpoint))
            stored = db.scalar(select(AgentTurn)).response
            encoded = response.text + json.dumps(stored) + str(checkpoint.state)
        for secret in ("test@example.com", "010-1234-5678", "arbitrary_private_value", "private_description", "private_extra"):
            assert secret not in encoded


@pytest.mark.parametrize("consent", [False, True])
def test_unverified_images_never_call_network(monkeypatch, consent):
    def forbidden(*args, **kwargs):
        pytest.fail("Image egress must remain disabled")
    monkeypatch.setenv("GEMINI_API_KEY", "fake-key")
    monkeypatch.setattr("urllib.request.urlopen", forbidden)
    result = LLMVLMFallback().analyze(VisionSnapshot(user_consented=consent,
        consent_request_id="verified-request" if consent else None,
        privacy_status="verified" if consent else "unverified",
        redaction_applied=True, screenshot_base64="RAW"), reason="test")
    assert result.status == ("not_configured" if consent else "consent_required")


def test_similarity_egress_only_contains_catalog_vocabulary(monkeypatch):
    bodies = []
    class Response:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def read(self, size): return b'{"embedding":{"values":[1,0]}}'
    def provider(request, timeout):
        assert isinstance(request, Request)
        bodies.append(request.data.decode())
        assert 0 < timeout <= 3
        return Response()
    monkeypatch.setattr("ai_agent.runtime.similarity.urlopen", provider)
    scorer = GeminiSimilarityScorer("fake-key")
    assert scorer.score("글자 크기 홍길동 secret@site.com", "글꼴 크기 010-1234-5678") == 1
    assert bodies
    text = " ".join(bodies)
    for secret in ("홍길동", "secret", "010-1234-5678"):
        assert secret not in text


def test_provider_failure_and_exhausted_budget_fall_back(monkeypatch):
    calls = []
    def unavailable(*args, **kwargs):
        calls.append(True)
        raise OSError("secret provider error")
    monkeypatch.setattr("ai_agent.runtime.similarity.urlopen", unavailable)
    assert 0 <= GeminiSimilarityScorer("fake").score("글자 크기", "글꼴 크기") <= 1
    assert len(calls) == 1
    token = deadline.set(monotonic() - 1)
    try:
        GeminiSimilarityScorer("fake").score("글자 크기", "글꼴 크기")
        assert len(calls) == 1
    finally:
        deadline.reset(token)


def test_trace_allowlist_capacity_and_expiry():
    now = [0]
    logger = TraceLogger(max_records=2, ttl_seconds=10, clock=lambda: now[0])
    for _ in range(4):
        logger.record({"request_id": "secret", "observation": {"label_sample": ["private"],
            "vision_status": "used", "accessibility_node_count": 3}, "typed_goal": {"source_text": "private"}})
    assert len(logger.traces) == 2
    assert "private" not in json.dumps(logger.traces) and "secret" not in json.dumps(logger.traces)
    assert logger.traces[0]["observation"]["vision_status"] == "used"
    now[0] = 11
    assert logger.traces == []


@pytest.mark.parametrize("metadata", [{"text": "private"}, {"step": {"text": "private"}},
                                     {"duration_ms": -1}, {"attempt_count": True}, {"explanation_depth": []}])
def test_event_rejects_unapproved_data(metadata):
    with pytest.raises(ValidationError):
        CreateUIEventRequest(event_type="task_completed", metadata=metadata)


def test_timeout_does_not_commit_late_result_and_retry_succeeds(monkeypatch):
    monkeypatch.setenv("AGENT_TURN_TIMEOUT_SECONDS", "0.05")
    release, finished = Event(), Event()
    class Slow(RuntimeAgentAdapter):
        def handle_turn(self, request):
            try:
                release.wait(5)
                return super().handle_turn(request)
            finally:
                finished.set()
    app = create_app()
    with TestClient(app) as client:
        register(client)
        service = app.state.turn_service
        service.engine_factory = Slow
        try:
            start = monotonic()
            response = post(client, payload())
            assert response.status_code == 504
            assert response.json()["error"]["code"] == "AGENT_TIMEOUT"
            assert monotonic() - start < 2
            assert session(client).json()["version"] == 0
        finally:
            release.set()
            assert finished.wait(5)
        assert session(client).json()["version"] == 0
        service.engine_factory = RuntimeAgentAdapter
        service.timeout_seconds = 5
        assert post(client, payload()).status_code == 200
        assert session(client).json()["version"] == 1


def test_graph_progress_is_independent_of_font_state_names():
    from dataclasses import replace
    graph = next(iter(TASK_GRAPHS.values()))
    names = {key: "custom_" + str(index) for index, key in enumerate(graph.states)}
    renamed = replace(graph, definition=replace(graph.definition,
        start_states=tuple(names[key] for key in graph.definition.start_states),
        done_state=names[graph.definition.done_state]),
        states={names[key]: replace(value, state_id=names[key]) for key, value in graph.states.items()},
        transitions=tuple(replace(t, from_state=names[t.from_state], to_state=names[t.to_state]) for t in graph.transitions))
    for key in graph.states:
        assert graph_progress(graph, key) == graph_progress(renamed, names[key])
    assert graph_progress(None, "unknown") == (0, 0)
    assert graph_progress(renamed, renamed.definition.done_state)[0] > 0


def test_event_retention_preserves_recent_records():
    from scripts.maintenance import cleanup_ui_events as cleanup
    app = create_app()
    now = datetime.now(timezone.utc)
    with TestClient(app) as client:
        auth = register(client)
        with app.state.session_factory() as db, db.begin():
            for hours in (1, 48):
                db.add(UIEvent(user_id=auth["user_id"], event_type="task_completed",
                    event_metadata={}, created_at=now - timedelta(hours=hours)))
        assert cleanup(app.state.session_factory, now=now) == 1
        with app.state.session_factory() as db:
            assert len(db.scalars(select(UIEvent)).all()) == 1
