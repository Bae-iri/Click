"""Independent target correctness and observed execution are different evidence."""
from copy import deepcopy
import math

import pytest

from ai_agent.graph_proposal.analysis.jev import analyze_jev, compare_assessments, render_report, REVIEW_SCHEMA
from ai_agent.graph_proposal.contracts import fingerprint, validate_event
from ai_agent.graph_proposal.reconstruction.episodes import reconstruct
from test_agent2_pipeline import events_for


def instrument(episode="case", *, status="selected", raw=.95, normalized=.95, source="synthetic", outcome="SUCCESS"):
    events = events_for(episode, "B", outcome, source=source)
    initial = events[0]["runtime"]
    initial["jev_decision"] = {
        "schema_version":"jev-decision.v1", "provider":"jev", "mode":"primary", "called":True,
        "status":status, "decision_hash":fingerprint("decision-"+episode), "screen_hash":fingerprint("screen-"+episode),
        "chosen_target_hash":initial["target"]["node_hash"], "chosen_transition_hash":fingerprint("B"),
        "model":"jev-test", "code_hash":fingerprint("code"), "minimum_confidence":.8,
        "confidence":min(raw,normalized), "raw_confidence":raw, "normalized_probability":normalized,
        "choice_kind":"target", "choice_count":3, "candidate_count":1,
        "hold_reason":"normalized_probability_below_minimum" if status == "low_confidence" else "none"}
    return events if status == "selected" else events[:1]


def reviews_for(events, label="correct", split="holdout"):
    records = []
    for event in events:
        d = event["runtime"].get("jev_decision")
        if d and d["called"]:
            records.append({"event_id":event["event_id"], "decision_hash":d["decision_hash"],
                "screen_hash":d["screen_hash"], "target_hash":d["chosen_target_hash"], "label":label,
                "reviewer":"independent-reviewer", "review_revision":"1", "evidence_refs":[event["event_id"]],
                "split":split, "group_id":event["episode_id"]})
    return {"schema_version":REVIEW_SCHEMA,"revision":"1","records":records}


def report(events, reviews=None):
    return analyze_jev(events, reconstruct(events, now=200), now=200, reviews=reviews)


def test_success_is_not_a_model_correctness_label_and_holds_are_not_failed_attempts():
    selected = instrument("accepted")
    held = instrument("held", status="low_confidence", normalized=.75)
    events = selected + held
    result = report(events)
    assert result["observations"]["provider_calls"] == 2
    assert result["execution"]["verified_attempts"] == 1
    assert result["execution"]["success_rate"] == 1
    assert result["review_splits"]["holdout"]["target_accuracy"] is None
    assert not any(i["reason"] == "reviewed_correct_target_held" for i in result["issues"])
    review = reviews_for(events)
    review["records"][0]["label"] = "incorrect"
    assessed = report(events, review)
    assert assessed["execution"]["success_rate"] == 1
    assert assessed["review_splits"]["holdout"]["target_accuracy"] == .5
    assert {i["reason"] for i in assessed["issues"]} >= {"high_confidence_wrong_target","reviewed_correct_target_held"}
    assert assessed["review_splits"]["holdout"]["scores"]["confidence"]["brier_score"] == pytest.approx((.95**2 + .25**2)/2)
    threshold = next(r for r in assessed["review_splits"]["holdout"]["thresholds"] if r["threshold"] == .8)
    assert threshold["accepted"] == threshold["wrong_accepted"] == threshold["correct_held"] == 1


def test_retained_waiting_paused_and_duplicate_events_do_not_inflate_calls_or_outcomes():
    events = instrument()
    waiting = deepcopy(events[0])
    waiting.update(event_id="wait", sequence=2)
    waiting["runtime"].update(previous_guide_hash=events[0]["runtime"]["guide_hash"],
        before_observation_hash=events[0]["runtime"]["after_observation_hash"],
        after_observation_hash=fingerprint("wait"), verification_status="WAITING_FOR_OBSERVATION")
    waiting["runtime"]["jev_decision"].update(called=False, retained=True)
    events[1]["sequence"] = 3
    events[1]["runtime"]["before_observation_hash"] = fingerprint("wait")
    repeated = deepcopy(events[1])
    repeated.update(event_id="duplicate-outcome", sequence=4)
    repeated["runtime"]["guide_hash"] = fingerprint("third-guide")
    result = report([events[0], waiting, events[1], repeated, waiting])
    assert result["observations"]["provider_calls"] == 1
    assert result["observations"]["retained_guides"] == 1
    assert result["execution"]["verified_attempts"] == 1


def test_unexecuted_selected_guides_and_missing_legacy_scores_are_explicit():
    assert report(instrument()[:1])["execution"]["selected_without_verified_outcome"] == 1
    legacy = report(events_for("old"))
    assert legacy["status"] == "NEEDS_DATA"
    assert legacy["observations"]["without_jev_evidence"] == 2
    assert legacy["execution"]["success_rate"] is None


def test_execution_on_a_rule_replacement_does_not_validate_the_model_target():
    events = instrument()
    events[0]["runtime"]["jev_decision"]["chosen_target_hash"] = fingerprint("different-model-target")
    result = report(events)
    assert result["outcomes_on_overridden_or_unidentified_targets"] == 1
    assert result["execution"]["verified_attempts"] == 0


@pytest.mark.parametrize("field,value", [("screen_hash", fingerprint("wrong-screen")),
    ("target_hash", fingerprint("wrong-target")), ("event_id", "wrong-event")])
def test_independent_review_must_match_the_exact_captured_choice(field, value):
    events = instrument()
    reviews = reviews_for(events)
    reviews["records"][0][field] = value
    with pytest.raises(ValueError, match="does not match"):
        report(events, reviews)


def test_missing_expired_or_withdrawn_review_evidence_is_not_used():
    events = instrument()
    reviews = reviews_for(events)
    for event in events:
        event["expires_at"] = 150
    with pytest.raises(ValueError, match="ineligible"):
        report(events, reviews)


def test_reviews_cannot_mix_session_or_group_splits():
    a, b = instrument("a"), instrument("b")
    for index, event in enumerate(b, 3):
        event.update(session_key=a[0]["session_key"], sequence=index)
    reviews = reviews_for(a + b)
    reviews["records"][1]["split"] = "train"
    with pytest.raises(ValueError, match="session leakage"):
        report(a + b, reviews)
    reviews["records"][1]["group_id"] = "a"
    with pytest.raises(ValueError, match="group leakage"):
        report(a + b, reviews)


@pytest.mark.parametrize("field,value", [("raw_text", "private screen"), ("confidence", float('nan')),
    ("normalized_probability", 1.1), ("called", 1), ("decision_hash", "raw-id"),
    ("choice_temperature", 0), ("choice_temperature", float('inf')), ("choice_temperature", True),
    ("judgment_mode", "other"), ("question_count", 2), ("question_count", True),
    ("page_confidence", "1"), ("target_support_probability", -.1), ("tempered_confidence", 1.1),
    ("page_target_consistent", 1)])
def test_decision_contract_rejects_private_unbounded_and_invalid_fields(field, value):
    event = instrument()[0]
    event["runtime"]["jev_decision"][field] = value
    with pytest.raises(ValueError):
        validate_event(event)


def test_atomic_scores_survive_closed_evidence_without_exporting_page_or_probability_vectors():
    from ai_agent.telemetry.trace_logger import diagnostic_payload
    from ai_agent.telemetry.jev_decisions import decision_evidence
    event = instrument()[0]
    fields = {"judgment_mode": "atomic", "question_count": 3, "choice_temperature": .7,
        "page_confidence": .96, "target_support_probability": .91, "page_target_consistent": False,
        "tempered_top_probability": .98, "tempered_probability_margin": .95,
        "tempered_confidence": .97, "hold_reason": "page_target_conflict"}
    selection = {**event["runtime"]["jev_decision"], **fields,
        "page_text": "private fixture", "probabilities": {"private fixture": 1}}
    safe = diagnostic_payload({"ui_selection": selection})["ui_selection"]
    assert all(safe[key] == value for key, value in fields.items())
    assert "private fixture" not in str(safe)
    event["runtime"]["jev_decision"] = decision_evidence({"ui_selection": selection})
    validate_event(event)


@pytest.mark.parametrize("reason,support", [("target_not_supported", .2),
    ("page_not_identified", 1), ("page_target_conflict", 1)])
def test_threshold_assessment_never_uses_sharpened_scores_to_override_atomic_holds(reason, support):
    events = instrument(status="low_confidence", raw=.99, normalized=.99)
    events[0]["runtime"]["jev_decision"].update(judgment_mode="atomic", question_count=3,
        choice_temperature=.01, tempered_confidence=1, page_confidence=1,
        target_support_probability=support, hold_reason=reason,
        page_target_consistent=reason == "target_not_supported")
    result = report(events, reviews_for(events))
    assert all(row["accepted"] == 0 and row["correct_held"] == 1
               for row in result["review_splits"]["holdout"]["thresholds"])
    assert result["groups"][0]["judgment_mode"] == "atomic"
    assert result["groups"][0]["choice_temperature"] == .01


def test_an_earlier_numeric_hold_cannot_hide_a_page_conflict_in_threshold_sweeps():
    events = instrument(status="low_confidence", raw=.7, normalized=.99)
    events[0]["runtime"]["jev_decision"].update(judgment_mode="atomic", question_count=3,
        choice_temperature=.7, page_confidence=1, target_support_probability=1,
        page_target_consistent=False, hold_reason="raw_confidence_below_minimum")
    result = report(events, reviews_for(events))
    assert all(row["accepted"] == 0 for row in result["review_splits"]["holdout"]["thresholds"])


def test_assessment_keeps_atomic_and_legacy_cohorts_separate():
    old, new = instrument("old"), instrument("new")
    new[0]["runtime"]["jev_decision"].update(judgment_mode="atomic", question_count=3,
        choice_temperature=.7, page_confidence=.99, target_support_probability=.99, page_target_consistent=True)
    assert len(report(old + new)["groups"]) == 2


def test_same_step_holds_and_device_model_cohorts_are_reported_separately():
    a, b = instrument("a", status="low_confidence", normalized=.7), instrument("b", status="low_confidence", normalized=.7)
    b[0].update(session_key=a[0]["session_key"], episode_id=a[0]["episode_id"], sequence=2)
    result = report(a + b)
    assert next(i for i in result["issues"] if i["reason"] == "repeated_low_confidence_at_same_step")["count"] == 2
    b[0]["device_cohort"] = "samsung:android-14"
    b[0]["session_key"], b[0]["episode_id"] = "other-session", "other-episode"
    b[0]["sequence"] = 1
    result = report(a + b)
    assert len(result["groups"]) == 2
    assert "실제 Jev 호출: 2회" in render_report(result)


def test_before_after_uses_identical_reviewed_device_holdout_and_requires_review():
    before = instrument("paired", status="low_confidence", normalized=.75, source="device_test")
    after = instrument("paired", normalized=.95, source="device_test")
    after[0]["runtime"]["jev_decision"]["code_hash"] = fingerprint("new-code")
    comparison = compare_assessments(report(before, reviews_for(before)), report(after, reviews_for(after)))
    assert comparison["status"] == "REVIEW_REQUIRED"
    assert comparison["before"]["correct_targets_held"] == 1
    assert comparison["after"]["correct_targets_held"] == 0
    assert comparison["execution_success_change"] is None
    assert comparison["automatic_changes"] is False
    after[0]["runtime"]["jev_decision"]["screen_hash"] = fingerprint("different-screen")
    mismatch = compare_assessments(report(before, reviews_for(before)), report(after, reviews_for(after)))
    assert mismatch["status"] == "NEEDS_DATA"


def test_synthetic_or_unreviewed_inputs_cannot_claim_a_product_improvement():
    events = instrument()
    result = report(events, reviews_for(events))
    assert compare_assessments(result, result)["status"] == "NEEDS_DATA"
    unreviewed = report(instrument(source="device_test"))
    assert compare_assessments(unreviewed, unreviewed)["status"] == "NEEDS_DATA"
