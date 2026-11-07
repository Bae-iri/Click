"""Production Jev evidence through encrypted outbox, consent and offline assessment."""
from dataclasses import replace
import json

import pytest
from sqlalchemy import select, text

from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.services.screen_jev import JevScreenResolver
from scripts.agent2 import assess_jev, process_sessions, CONSUMER
from tests.test_agent2_operations import started_with_consent
from tests.test_primary_recovery import Scenario
from tests.test_runtime_recovery_events import follow
from ai_agent.graph_proposal.analysis.jev import REVIEW_SCHEMA


@pytest.fixture
def provider(monkeypatch):
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_MIN_CONFIDENCE", "0.8")
    scenario = Scenario(["selected"])
    monkeypatch.setattr(JevScreenResolver, "_post", lambda self, payload, **kwargs: scenario.send(payload, **kwargs))
    return scenario


def test_actual_calls_not_retained_guides_are_assessed_from_encrypted_storage(provider):
    app, client, user, body = started_with_consent()
    for _ in range(3):
        response = client.post("/api/v1/agent/commands", json=follow(body))
        assert response.status_code == 200, response.text
        body = response.json()
    report = assess_jev(app.state.session_factory)
    assert report["observations"]["provider_calls"] == len(provider.calls) == 1
    assert report["observations"]["retained_guides"] == 3
    assert report["execution"]["verified_attempts"] == 0
    assert report["groups"][0]["model"] == "jev-1.14.0"
    assert len(report["groups"][0]["code_hash"]) == 64
    assert process_sessions(app.state.session_factory)["processed"] == 1
    with app.state.session_factory() as db:
        result = db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer == CONSUMER)).result
        assert result["jev_analysis"]["observations"]["provider_calls"] == 1
    with app.state.engine.connect() as db:
        stored = db.execute(text("SELECT payload FROM runtime_events LIMIT 1")).scalar_one()
        assert "raw_confidence" not in str(stored)
    serialized = json.dumps(report)
    assert user not in serialized and "디스플레이" not in serialized and "test-only-key" not in serialized


def test_low_confidence_needs_an_exact_independent_review_and_withdrawal_invalidates_it(provider):
    provider.outcomes = [(.95, .81)]
    app, client, user, _ = started_with_consent()
    report = assess_jev(app.state.session_factory)
    assert report["decision_statuses"] == {"low_confidence":1}
    assert report["execution"]["success_rate"] is None
    item = report["review_queue"][0]
    reviews = {"schema_version":REVIEW_SCHEMA,"revision":"1","records":[{
        **item,"label":"correct","reviewer":"reviewer","review_revision":"1",
        "evidence_refs":[item["event_id"]],"split":"holdout","group_id":"captured-case"}]}
    assessed = assess_jev(app.state.session_factory, reviews=reviews)
    assert assessed["review_splits"]["holdout"]["target_accuracy"] == 1
    assert any(issue["reason"] == "reviewed_correct_target_held" for issue in assessed["issues"])
    assert assessed["execution"]["verified_attempts"] == 0
    assert client.post(f"/api/v1/users/{user}/consents",json={"purpose":"model_training","consented":False,"policy_version":"1"}).status_code == 201
    assert assess_jev(app.state.session_factory)["status"] == "NEEDS_DATA"
    with pytest.raises(ValueError, match="ineligible"):
        assess_jev(app.state.session_factory, reviews=reviews)


def test_confirmation_retention_and_later_success_share_the_original_decision(provider):
    from tests.test_jev_primary import setup_slider
    from ai_agent.runtime import UserActionEvent, UserActionType
    from ai_agent.telemetry.execution_events import produce
    from ai_agent.graph_proposal.contracts import EVENT_SCHEMA, fingerprint
    from ai_agent.graph_proposal.reconstruction.episodes import reconstruct
    from ai_agent.graph_proposal.analysis.jev import analyze_jev
    graph, _, engine, _, request = setup_slider()
    first = engine.handle_turn(request)
    state = engine.session_store.get("s")
    initial = produce(first, None, state)
    approved = engine.handle_turn(replace(request, request_id="approve", observation_id="approve", captured_at_ms=2000,
        user_confirmation="accepted"))
    action_state = engine.session_store.get("s")
    approval = produce(approved, state, action_state)
    assert initial["jev_decision"]["decision_hash"] == approval["jev_decision"]["decision_hash"]
    changed = replace(request.accessibility_tree[0], extras={"value":4,"min":0,"max":10})
    result = engine.handle_turn(replace(request, request_id="done", observation_id="done", captured_at_ms=3000,
        accessibility_tree=(changed,*request.accessibility_tree[1:]),
        applied_settings={**request.applied_settings,"font_scale":1.2},
        user_action_event=UserActionEvent(UserActionType.DRAG_COMPLETED, approved.guide_command.guide_command_id,
            changed.node_id, occurred_at_ms=2100, event_id="drag")))
    final = produce(result, action_state, engine.session_store.get("s"))
    base = {"schema_version":EVENT_SCHEMA,"session_key":"session","episode_id":"episode","created_at":100,
        "expires_at":1000,"task_id":graph.definition.task_id,"graph_version":"test",
        "graph_hash":fingerprint(graph.to_dict()),"source_kind":"synthetic","control":{}}
    events = [{**base,"event_id":f"e{i}","sequence":i,"runtime":event} for i,event in enumerate((initial,approval,final),1)]
    report = analyze_jev(events,reconstruct(events,now=200),now=200)
    assert report["observations"]["provider_calls"] == 1
    assert report["execution"]["verified_attempts"] == report["execution"]["successes"] == 1
