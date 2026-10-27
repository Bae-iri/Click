"""Contract, leakage, evidence and release regressions using synthetic events only."""
from copy import deepcopy
from dataclasses import asdict
import pytest

from ai_agent.graph_proposal.contracts import EVENT_SCHEMA, MANIFEST_SCHEMA, fingerprint, validate_event, validate_manifest
from ai_agent.graph_proposal.reconstruction.episodes import reconstruct
from ai_agent.graph_proposal.workflows.pipeline import run_pipeline
from ai_agent.graph_proposal.graphs import graph_errors, apply_proposal
from ai_agent.graph_proposal.datasets.dataset_builder import TrainingDatasetBuilder
from ai_agent.graph_proposal.evaluation.graph_validator import GraphValidator
from ai_agent.graph_proposal.models.model_trainer import ModelTrainer
from ai_agent.graph_proposal.models.model_registry import ModelRegistry
from ai_agent.graph_proposal.releases.versioning import GraphVersionManager


def graph():
    edge = {"from_state": "START", "to_state": "DONE", "action_type": "tap", "action_name": "open",
        "target_keywords": ["setting"], "target_role": "button", "safety_level": "CONFIRM_REQUIRED",
        "guide_message": "confirm", "confirmation_message": "confirm", "requires_user_action": True,
        "completes_task": True, "verification_predicate": "state_reached", "selector": {}}
    return {"definition": {"task_id": "TEST", "goal_type": "settings.test", "name": "test",
        "target_app": "android_settings", "task_type": "settings", "start_states": ["START"], "done_state": "DONE", "required_slots": {}},
        "states": {s: {"state_id": s, "name": s, "confidence_threshold": 0.75} for s in ("START", "DONE")},
        "transitions": [{**edge, "transition_id": "A", "priority": 0}, {**edge, "transition_id": "B", "priority": 1}],
        "synonyms": {}, "policy": {}}


def events_for(episode, transition="A", status="NO_CHANGE", *, source="synthetic", session=None, offset=0):
    base = {"schema_version": EVENT_SCHEMA, "session_key": session or "session-" + episode,
        "episode_id": episode, "created_at": 100, "expires_at": 1000, "task_id": "TEST",
        "graph_version": "1.0.0", "graph_hash": fingerprint(graph()), "source_kind": source, "control": {}}
    first = {"schema_version": "agent-execution-event.v1", "state_id": "START",
        "guide_hash": fingerprint("guide-" + episode), "after_observation_hash": fingerprint("before-" + episode),
        "target": {"node_hash": fingerprint("node"), "role": "button", "transition_id": transition},
        "count_attempt": False, "task_completed": False}
    second = {"schema_version": "agent-execution-event.v1", "state_id": "START",
        "previous_guide_hash": first["guide_hash"], "guide_hash": fingerprint("next-" + episode),
        "attempt_hash": fingerprint("attempt-" + episode), "attempt_target_hash": fingerprint("node"),
        "before_observation_hash": first["after_observation_hash"], "after_observation_hash": fingerprint("after-" + episode),
        "verification_status": status, "count_attempt": True, "task_completed": status == "SUCCESS",
        "reason_code": "expected_state_observed" if status == "SUCCESS" else "verified_attempt_failed",
        "target": {"node_hash": None, "role": "other", "transition_id": None}}
    return [{**base, "event_id": episode + "-1", "sequence": offset + 1, "runtime": first},
            {**base, "event_id": episode + "-2", "sequence": offset + 2, "runtime": second}]


def manifest_for(events, *, holdout=()):
    episodes = {e["episode_id"]: e["source_kind"] for e in events}
    return {"schema_version": MANIFEST_SCHEMA, "dataset_id": "fixture", "revision": "1", "source_revision": "test-v1",
        "records": [{"episode_id": eid, "group_id": "group-" + eid, "split": "holdout" if eid in holdout else "train",
            "source_kind": source, "review_status": "independent_review" if eid in holdout else "pending",
            "reviewer": "test-reviewer" if eid in holdout else None, "review_revision": "1" if eid in holdout else None,
            "evidence_refs": [e["event_id"] for e in events if e["episode_id"] == eid],
            "label": "unknown", "label_method": "independent_evidence" if eid in holdout else "weak"}
            for eid, source in sorted(episodes.items())]}


def training_fixture():
    events = []
    for index in range(2):
        events += events_for("bad" + str(index))
        events += events_for("good" + str(index), "B", "SUCCESS")
    return events


def proposed():
    events = training_fixture()
    result = run_pipeline(events, {fingerprint(graph()): graph()}, now=200, manifest=manifest_for(events))
    from ai_agent.graph_proposal.schemas import GraphUpdateProposal
    return GraphUpdateProposal(**result["proposals"][0])


def test_reconstruct_deduplicates_orders_and_correlates_previous_guide():
    events = events_for("one", "B", "SUCCESS")
    result = reconstruct([events[1], events[0], events[0]], now=200)
    assert result[0]["steps"][-1]["transition_id"] == "B"
    assert result[0]["steps"][-1]["label"] == "verified_success"
    assert result[0]["task_label"] == "weak_success"
    assert result[0]["complete_evidence"] is True
    corrupted = deepcopy(events)
    corrupted[1]["runtime"]["attempt_target_hash"] = fingerprint("wrong")
    result = reconstruct(corrupted, now=200)[0]
    assert result["steps"][-1]["label"] == "unknown"
    assert not result["complete_evidence"]


def test_reconstruct_rejects_conflicts_and_does_not_count_repeated_attempts():
    events = events_for("one")
    conflict = {**events[0], "created_at": 101}
    with pytest.raises(ValueError, match="reused"):
        reconstruct(events + [conflict], now=200)
    repeat = deepcopy(events[1])
    repeat.update(event_id="one-3", sequence=3)
    repeat["runtime"]["guide_hash"] = fingerprint("third")
    result = reconstruct(events + [repeat], now=200)[0]
    assert result["steps"][-1]["label"] == "duplicate_attempt"
    assert reconstruct(events, now=1000) == []


def test_missing_sequence_and_graph_changes_are_not_silent():
    events = events_for("one")
    events[1]["sequence"] = 3
    assert "missing_session_events" in reconstruct(events, now=200)[0]["issues"]
    events[1]["graph_version"] = "2.0.0"
    with pytest.raises(ValueError, match="Graph/source"):
        reconstruct(events, now=200)


@pytest.mark.parametrize("field,value", [("raw_goal", "private"), ("user_id", "private")])
def test_private_or_unversioned_fields_are_rejected(field, value):
    event = events_for("one")[0]
    event[field] = value
    with pytest.raises(ValueError):
        validate_event(event)


def test_learner_generates_hash_bound_existing_route_patch_only():
    events = training_fixture()
    result = run_pipeline(events, {fingerprint(graph()): graph()}, now=200, manifest=manifest_for(events))
    proposal = proposed()
    candidate = apply_proposal(graph(), proposal)
    assert candidate["transitions"][1]["priority"] < candidate["transitions"][0]["priority"]
    assert graph()["transitions"][0]["priority"] == 0
    assert result["evaluations"][0]["decision"] == "NEEDS_DATA"
    assert result["analysis"]["counts"]["verified_failure"] == 2
    assert result["analysis"]["counts"]["verified_success"] == 2
    changed = graph()
    changed["definition"]["name"] = "changed"
    with pytest.raises(ValueError, match="Stale"):
        apply_proposal(changed, proposal)


def test_dataset_split_and_source_leakage_are_blocked():
    events = events_for("one", session="shared") + events_for("two", session="shared", offset=2)
    episodes = reconstruct(events, now=200)
    manifest = manifest_for(events, holdout={"two"})
    with pytest.raises(ValueError, match="Session leakage"):
        TrainingDatasetBuilder().build(episodes, manifest=manifest)
    manifest["records"][1]["group_id"] = manifest["records"][0]["group_id"]
    with pytest.raises(ValueError, match="Group leakage"):
        validate_manifest(manifest)


def test_holdout_cannot_produce_candidate_or_train_frequency_baseline():
    events = training_fixture()
    manifest = manifest_for(events, holdout={"good0", "good1"})
    result = run_pipeline(events, {fingerprint(graph()): graph()}, now=200, manifest=manifest)
    assert result["proposals"] == []
    trained = ModelTrainer().train(result["dataset"], allow_weak=True)
    assert trained["artifact"]["training_samples"] == 2
    assert all(v["successes"] == 0 for v in trained["artifact"]["entries"].values())
    assert all(not s["product_gold"] for s in result["dataset"]["splits"]["holdout"])


def evaluation_fixture(source="synthetic"):
    events = events_for("test", source=source)
    manifest = manifest_for(events, holdout={"test"})
    case = {"episode_id": "test", "source_kind": source, "task_id": "TEST", "graph_hash": fingerprint(graph()),
        "review_revision": "1", "evidence_refs": [e["event_id"] for e in events]}
    return manifest, [case]


def test_evaluation_requires_real_holdout_and_does_not_mask_tool_errors():
    manifest, cases = evaluation_fixture()
    kwargs = dict(base_graph=graph(), cases=cases, manifest=manifest,
        evaluator=lambda body, case: {"checks": 1, "failures": []}, graph_loader=lambda body: body)
    report = GraphValidator().validate(proposed(), **kwargs)
    assert report["decision"] == "NEEDS_DATA" and "independent_device_holdout" in report["missing"]
    def broken(body, case):
        raise RuntimeError("test replay unavailable")
    kwargs["evaluator"] = broken
    assert GraphValidator().validate(proposed(), **kwargs)["decision"] == "ERROR"
    from ai_agent.graph_proposal.contracts import EvidenceRequired
    def unobserved(body, case):
        raise EvidenceRequired("new_device_transition")
    kwargs["evaluator"] = unobserved
    assert GraphValidator().validate(proposed(), **kwargs)["decision"] == "NEEDS_DATA"
    kwargs["evaluator"] = lambda body, case: {"checks": 1, "failures": ["wrong target"]}
    assert GraphValidator().validate(proposed(), **kwargs)["decision"] == "REJECT"


def test_release_gate_binds_reviewed_evaluation_and_exact_artifact():
    manifest, cases = evaluation_fixture("device_test")  # Simulated metadata, no actual device claim.
    proposal = proposed()
    report = GraphValidator().validate(proposal, base_graph=graph(), cases=cases, manifest=manifest,
        evaluator=lambda body, case: {"checks": 1, "failures": []}, graph_loader=lambda body: body)
    assert report["decision"] == "PASS"
    manager = GraphVersionManager()
    release = manager.prepare(proposal, graph(), candidate_version="2.0.0", evaluation=report,
                              reviewer="test-reviewer", reviewed_at=200)
    assert release["graph_hash"] == fingerprint(release["body"])
    report["candidate_hash"] = fingerprint("different")
    with pytest.raises(ValueError, match="matching PASS"):
        manager.prepare(proposal, graph(), candidate_version="2.0.0", evaluation=report, reviewer="r", reviewed_at=200)
    with pytest.raises(ValueError, match="durable"):
        manager.promote(release)


def test_registry_persists_immutable_models_and_training_needs_explicit_weak_use(tmp_path):
    events = training_fixture()
    dataset = run_pipeline(events, {}, now=200, manifest=manifest_for(events))["dataset"]
    with pytest.raises(ValueError, match="allow_weak"):
        ModelTrainer().train(dataset)
    artifact = ModelTrainer().train(dataset, allow_weak=True)["artifact"]
    registry = ModelRegistry(tmp_path)
    registry.register(artifact["model_id"], artifact)
    assert ModelRegistry(tmp_path).get(artifact["model_id"]) == artifact
    with pytest.raises(ValueError, match="immutable"):
        registry.register(artifact["model_id"], {**artifact, "deployment_status": "modified"})
    with pytest.raises(ValueError):
        registry.get("../private")


def test_static_audit_catches_dangling_edges_without_banning_recovery_cycles():
    body = graph()
    assert graph_errors(body) == []
    body["transitions"][1]["to_state"] = "ABSENT"
    assert "dangling_transition" in graph_errors(body)
