"""Decision evidence survives HTTP responses, retries and persisted turn records."""
from tests.jev_answers import with_screen_judgments
from hashlib import sha256
import json

import httpx
import pytest
from sqlalchemy import select

from app.main import create_app
from app.models.agent_session import AgentTurn
from app.services.screen_jev import JevScreenResolver
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


@pytest.mark.parametrize("choice_kind,raw,probability,status,reason", [
    ("candidate", 0.9, 0.85, "low_confidence", "normalized_probability_below_minimum"),
    ("candidate", 0.77, 0.85, "low_confidence", "both_below_minimum"),
    ("candidate", 0.77, 0.95, "low_confidence", "raw_confidence_below_minimum"),
    ("candidate", 0.95, 0.95, "selected", "none"),
    ("candidate", 0.8, 0.95, "selected", "none"),
    ("wait_for_result", 0.9, 0.85, "low_confidence", "normalized_probability_below_minimum"),
    ("insufficient_evidence", 0.9, 0.85, "low_confidence", "normalized_probability_below_minimum"),
    ("wait_for_result", 1, 1, "waiting", "wait_for_result"),
    ("insufficient_evidence", 1, 1, "abstained", "insufficient_evidence"),
])
def test_decision_evidence_survives_reobservation_retry_and_storage(
        monkeypatch, choice_kind, raw, probability, status, reason):
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_MIN_CONFIDENCE", "0.8")
    calls = []

    def post(self, payload, **kwargs):
        options = payload["questions"]["screen"]["criteria"]
        assert len(options) == 3
        candidate = next(key for key, value in options.items() if isinstance(value, dict))
        choice = candidate if choice_kind == "candidate" else choice_kind
        calls.append((choice, options[candidate]["transition"]))
        return httpx.Response(200, json=with_screen_judgments(payload, {"model": payload["model"], "answers": {"screen": {
            "type": "choice", "choice": choice, "confidence": raw,
            "probabilities": {key: probability if key == choice else (1 - probability) / 2
                              for key in options}}}}))

    monkeypatch.setattr(JevScreenResolver, "_post", post)
    app = create_app()
    with authenticated_client(app) as client:
        payload = start_payload()
        selections = []
        for turn in range(3):
            payload["accessibility_tree"].update(observation_id=f"decision-{turn}",
                                                  captured_at_ms=1000 * (turn + 1))
            response = client.post("/api/v1/agent/commands", json=payload)
            assert response.status_code == 200, response.text
            body = response.json()
            selection = body["metadata"]["debug"]["ui_selection"]
            selections.append(selection)
            assert selection["choice"] == calls[-1][0]
            assert selection["raw_confidence"] == raw
            assert selection["top_probability"] == probability
            assert selection["choice_count"] == 3
            adjusted = (probability - 1 / 3) / (1 - 1 / 3)
            assert selection["normalized_probability"] == pytest.approx(adjusted)
            assert selection["confidence"] == pytest.approx(min(raw, adjusted))
            assert selection["minimum_confidence"] == 0.8
            assert selection["hold_reason"] == reason
            assert selection["status"] == status
            if choice_kind == "candidate":
                assert selection["chosen_transition_hash"] == sha256(calls[-1][1].encode()).hexdigest()
            else:
                assert "chosen_transition_hash" not in selection
            assert "chosen_transition" not in selection
            assert "chosen_state" not in selection
            assert body["guide_command"]["target_node_id"] == ("display" if status == "selected" else None)
            # Identical requests replay the saved evidence without calling Jev again.
            replay = client.post("/api/v1/agent/commands", json=payload)
            assert replay.status_code == 200, replay.text
            assert replay.json() == body
            assert len(calls) == 1
            assert selection["called"] is (turn == 0)
            if status != "selected":
                failure = selection["failure"]
                assert failure["stage"] == "model_selection"
                assert failure["required_change"] == "user_clarification"
                assert body["metadata"]["debug"]["recovery"]["failure"] == failure
            payload = {"contract_version": payload["contract_version"], "command_type": "observe",
                "request_id": f"decision-observe-{turn + 1}", "session_id": body["session_id"],
                "expected_version": body["session_version"], "episode_id": body["episode_id"],
                "device_context": payload["device_context"], "accessibility_tree": payload["accessibility_tree"]}
        with app.state.session_factory() as db:
            records = db.scalars(select(AgentTurn).order_by(AgentTurn.version)).all()
            assert [record.response["metadata"]["debug"]["ui_selection"] for record in records] == selections


@pytest.mark.parametrize("fresh_sessions", [False, True])
def test_synthetic_diagnostic_report_distinguishes_target_wait_and_abstention(
        monkeypatch, tmp_path, capsys, fresh_sessions):
    from scripts.diagnose_jev_screen import main

    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_MIN_CONFIDENCE", "0.8")
    calls = []

    def post(self, payload, **kwargs):
        criteria = payload["questions"]["screen"]["criteria"]
        candidate = next(key for key, value in criteria.items() if isinstance(value, dict))
        choice = (candidate, "wait_for_result", "insufficient_evidence")[len(calls)]
        probability = 0.95 if not calls else 0.85
        calls.append(choice)
        return httpx.Response(200, json=with_screen_judgments(payload, {"model": payload["model"], "answers": {"screen": {
            "type": "choice", "choice": choice, "confidence": 0.9,
            "probabilities": {key: probability if key == choice else (1 - probability) / 2
                              for key in criteria}}}}))

    monkeypatch.setattr(JevScreenResolver, "_post", post)
    output = tmp_path / "diagnostics.json"
    args = ["--attempts", "3", "--output", str(output)]
    assert main(args + (["--fresh-sessions"] if fresh_sessions else [])) == 0
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report == json.loads(capsys.readouterr().out)
    assert report["actual_clicks"] is False
    observations = report["observations"]
    if not fresh_sessions:
        # Unexecuted, still-valid guides are retained instead of being reclassified.
        assert len(calls) == 1
        assert all(o["ui_selection"]["choice"] == calls[0] for o in observations)
        assert all(o["ui_selection"]["retained"] for o in observations[1:])
        assert [o["ui_selection"]["called"] for o in observations] == [True, False, False]
        assert [o["planned_action_present"] for o in observations] == [False, True, True]
        assert not any(o["previous_action_present"] for o in observations)
        return
    assert [o["ui_selection"]["choice"] for o in observations] == calls
    assert "디스플레이" in observations[0]["chosen_target_names"]
    assert all("chosen_transition" not in o for o in observations[1:])
    assert observations[0]["ui_selection"]["hold_reason"] == "none"
    assert all(o["ui_selection"]["hold_reason"] == "normalized_probability_below_minimum" for o in observations[1:])
    assert not any(o["previous_action_present"] for o in observations)
    assert all(o["previous_action_event_received"] is False for o in observations)
    assert "test-only-key" not in output.read_text(encoding="utf-8")
