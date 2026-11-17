"""Atomic judgment joins, temperature math, and a single provider round trip."""
import json
import math

import httpx
import pytest
from dataclasses import replace

from app.clients.runtime_agent import RuntimeAgentAdapter, runtime_configuration
from app.services.jev_probabilities import temperature_softmax
from app.services.screen_jev import JevScreenResolver
from ai_agent.runtime.types import NormalizedNode
from ai_agent.runtime.primary import selection_fingerprint
from ai_agent.runtime.state_manager import build_screen_snapshot
from tests.test_screen_jev import scene, sender_for


@pytest.fixture(autouse=True)
def configuration(monkeypatch):
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-only-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_MIN_CONFIDENCE", "0.8")
    monkeypatch.delenv("JEV_SCREEN_JUDGMENT_MODE", raising=False)
    monkeypatch.delenv("JEV_CHOICE_TEMPERATURE", raising=False)


def test_temperature_is_power_normalization_of_probabilities():
    values = {"a": .6, "b": .3, "c": .1}
    assert temperature_softmax(values, .5) == pytest.approx({"a": 36 / 46, "b": 9 / 46, "c": 1 / 46})
    assert temperature_softmax(values, 1) == pytest.approx(values)


@pytest.mark.parametrize("temperature", [.7, .01, 1e-320, 1, 10])
def test_transform_is_finite_normalized_and_preserves_winners_ties_and_zeros(temperature):
    values = {"a": .4, "b": .4, "c": .2, "d": 0}
    result = temperature_softmax(values, temperature)
    assert math.fsum(result.values()) == pytest.approx(1)
    assert result["a"] == result["b"] >= result["c"] >= result["d"] == 0
    assert all(math.isfinite(p) for p in result.values())


@pytest.mark.parametrize("temperature", [0, -1, True, "0.7", float("nan"), float("inf")])
def test_invalid_temperatures_are_rejected(temperature):
    with pytest.raises(ValueError):
        temperature_softmax({"a": .8, "b": .2}, temperature)


@pytest.mark.parametrize("distribution", [{}, {"a": 1}, {"a": 0, "b": 0},
    {"a": .6, "b": .5}, {"a": True, "b": 0}, {"a": float("nan"), "b": .5},
    {"a": 1.1, "b": -.1}, {"": .8, "b": .2}])
def test_invalid_distributions_cannot_be_sharpened(distribution):
    with pytest.raises(ValueError):
        temperature_softmax(distribution, .7)


def test_atomic_default_uses_three_short_judgments_in_one_call():
    graph, transition, node, goal = scene("font_size")
    calls = []
    def send(payload, **kwargs):
        calls.append(payload)
        assert set(payload["questions"]) == {"page", "target_supported", "screen"}
        assert payload["questions"]["target_supported"]["type"] == "noul"
        assert "graph" not in payload["state"]
        assert "temperature" not in payload
        assert "bounds" not in json.dumps(payload)
        return sender_for(transition)(payload, **kwargs)
    resolver = JevScreenResolver(send)
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal).transition_id == transition.transition_id
    assert len(calls) == 1
    assert resolver.last_diagnostics["question_count"] == 3
    assert resolver.last_diagnostics["choice_temperature"] == .7


@pytest.mark.parametrize("missing", ["screen", "page", "target_supported"])
def test_partial_atomic_response_never_falls_back_or_retries(missing):
    graph, transition, node, goal = scene("font_size")
    calls = []
    def send(payload, **kwargs):
        calls.append(payload)
        return sender_for(transition, mutate=lambda r: r["answers"].pop(missing))(payload, **kwargs)
    resolver = JevScreenResolver(send)
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal) is None
    assert resolver.last_diagnostics["status"] == "invalid_response"
    assert len(calls) == 1


@pytest.mark.parametrize("change,reason,status", [
    ("unsupported", "target_not_supported", "abstained"),
    ("unknown_page", "page_not_identified", "abstained"),
    ("weak_page", "page_confidence_below_minimum", "low_confidence"),
])
def test_confident_target_cannot_override_a_weak_or_unknown_observation(change, reason, status):
    graph, transition, node, goal = scene("font_size")
    def mutate(result):
        page = result["answers"]["page"]
        if change == "unsupported":
            result["answers"]["target_supported"]["noul"] = .79
        elif change == "unknown_page":
            page.update(choice="unknown_page", probabilities={k: float(k == "unknown_page") for k in page["probabilities"]})
        else:
            page["probabilities"] = {k: .85 if k == page["choice"] else .15 for k in page["probabilities"]}
    resolver = JevScreenResolver(sender_for(transition, mutate=mutate))
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal) is None
    assert resolver.last_diagnostics["raw_confidence"] == 1
    assert resolver.last_diagnostics["hold_reason"] == reason
    assert resolver.last_diagnostics["status"] == status


def test_page_and_target_must_refer_to_the_same_observed_graph_step():
    graph, transition, node, goal = scene("font_size")
    menu = NormalizedNode("display-row", "디스플레이", "menu_item", bounds=(10, 200, 300, 240),
        actionable=True, clickable=True, package_name="com.android.settings")
    def mutate(result):
        page = result["answers"]["page"]
        other = next(k for k in page["probabilities"] if k not in {page["choice"], "unknown_page"})
        page.update(choice=other, probabilities={k: float(k == other) for k in page["probabilities"]})
    resolver = JevScreenResolver(sender_for(transition, mutate=mutate))
    assert resolver.resolve(nodes=(node, menu), graph=graph, goal=goal) is None
    assert resolver.last_diagnostics["hold_reason"] == "page_target_conflict"


@pytest.mark.parametrize("part,value", [("noul", True), ("noul", float("nan")),
    ("noul", 1.01), ("page_confidence", "1"), ("page_extra", "unexpected"), ("page_tie", None)])
def test_malformed_atoms_cannot_authorize_a_target(part, value):
    graph, transition, node, goal = scene("font_size")
    def mutate(result):
        page = result["answers"]["page"]
        if part == "noul":
            result["answers"]["target_supported"]["noul"] = value
        elif part == "page_tie":
            page["probabilities"] = {k: 1 / len(page["probabilities"]) for k in page["probabilities"]}
        else:
            page["confidence" if part == "page_confidence" else "extra"] = value
    def send(payload, **kwargs):
        result = sender_for(transition)(payload, **kwargs).json()
        mutate(result)
        return httpx.Response(200, content=json.dumps(result))
    resolver = JevScreenResolver(send)
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal) is None
    assert resolver.last_diagnostics["status"] == "invalid_response"


@pytest.mark.parametrize("temperature", ["0.7", "0.01"])
def test_sharpening_never_promotes_a_raw_rejected_decision(monkeypatch, temperature):
    monkeypatch.setenv("JEV_CHOICE_TEMPERATURE", temperature)
    graph, transition, node, goal = scene("font_size")
    def mutate(result):
        answer = result["answers"]["screen"]
        answer["confidence"] = .79
        answer["probabilities"] = {k: .85 if k == answer["choice"] else .15 / (len(answer["probabilities"]) - 1)
                                   for k in answer["probabilities"]}
    resolver = JevScreenResolver(sender_for(transition, mutate=mutate))
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal) is None
    diagnostic = resolver.last_diagnostics
    assert diagnostic["status"] == "low_confidence"
    assert diagnostic["raw_confidence"] == .79
    assert diagnostic["tempered_confidence"] > .8
    assert diagnostic["hold_reason"] == "both_below_minimum"


def test_legacy_is_explicit_and_keeps_original_acceptance(monkeypatch):
    monkeypatch.setenv("JEV_SCREEN_JUDGMENT_MODE", "legacy")
    graph, transition, node, goal = scene("font_size")
    def send(payload, **kwargs):
        assert set(payload["questions"]) == {"screen"}
        assert "graph" in payload["state"]
        return sender_for(transition)(payload, **kwargs)
    resolver = JevScreenResolver(send)
    assert resolver.resolve(nodes=(node,), graph=graph, goal=goal).transition_id == transition.transition_id
    assert resolver.last_diagnostics["question_count"] == 1


@pytest.mark.parametrize("key,value", [("JEV_SCREEN_JUDGMENT_MODE", "typo"),
    ("JEV_CHOICE_TEMPERATURE", "0"), ("JEV_CHOICE_TEMPERATURE", "nan"),
    ("JEV_CHOICE_TEMPERATURE", "11")])
def test_bad_policy_configuration_fails_at_startup(monkeypatch, key, value):
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    monkeypatch.setenv(key, value)
    with pytest.raises(ValueError):
        RuntimeAgentAdapter()


def test_public_runtime_identity_exposes_policy_without_credentials(monkeypatch):
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    configuration = runtime_configuration()
    assert configuration["screen_judgment_mode"] == "atomic"
    assert configuration["choice_temperature"] == .7
    assert "test-only-key" not in str(configuration)


def test_retry_identity_ignores_geometry_and_generated_ids_but_tracks_window_eligibility():
    _, _, node, _ = scene("font_size")
    node = replace(node, window_id=7)
    other = replace(node, node_id="other", label="밝기", bounds=(10, 300, 300, 340))
    snapshot = build_screen_snapshot("S1_SETTINGS_HOME", (node, other))
    bounds = (0, 0, 1080, 2400)
    original = selection_fingerprint(snapshot, (node, other), bounds, {})
    shifted = (replace(node, node_id="regenerated", bounds=(10, 120, 300, 160), window_id=8),
               replace(other, node_id="also-new", window_id=8))
    assert selection_fingerprint(snapshot, shifted, bounds, {}) == original
    mixed = (node, replace(other, window_id=8))
    assert selection_fingerprint(snapshot, mixed, bounds, {}) != original
    assert selection_fingerprint(snapshot, (replace(node, enabled=False), other), bounds, {}) != original
    assert selection_fingerprint(snapshot, (replace(node, value=4), other), bounds, {}) != original
