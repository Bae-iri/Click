"""Jev transport/schema failures must not become an executable target."""
import json
from time import monotonic
from unittest.mock import Mock

import pytest

from ai_agent.runtime.budget import deadline
from ai_agent.runtime.jev_ui import (
    ABSTENTIONS, JEV_ENDPOINT, MAX_BYTES, JevUiSelector, configured_ui_selector,
)


@pytest.fixture(autouse=True)
def jev_env(monkeypatch):
    monkeypatch.setenv("TYPESAFE_API_KEY", "test-key")
    monkeypatch.setenv("JEV_MODEL", "jev-1.14.0")
    monkeypatch.setenv("JEV_UI_MIN_CONFIDENCE", "0.8")
    monkeypatch.delenv("AI1_UI_SELECTOR_PROVIDER", raising=False)


OBSERVATION = {"step": {"action": "tap", "target_terms": ["디스플레이"]},
               "candidates": [{"id": "ui_0", "primary_terms": ["디스플레이"]},
                              {"id": "ui_1", "primary_terms": ["화면"]}]}


def response(choice="ui_0", probability=0.97, confidence=0.96):
    options = ["ui_0", "ui_1", *ABSTENTIONS]
    return {"model": "jev-1.14.0", "answers": {"target": {
        "type": "choice", "choice": choice, "confidence": confidence,
        "probabilities": {key: probability if key == choice else (1 - probability) / 3
                          for key in options},
    }}, "usage": {"input_tokens": 100, "output_tokens": 20}}


def encoded(value):
    return json.dumps(value).encode()


def test_choice_uses_pinned_model_with_abstentions_and_one_bounded_call():
    sender = Mock(return_value=(200, encoded(response())))
    result = JevUiSelector(sender).select(OBSERVATION)
    assert (result.status, result.choice, result.called) == ("selected", "ui_0", True)
    assert result.confidence == pytest.approx(0.96)
    sender.assert_called_once()
    payload = sender.call_args.args[0]
    assert payload["model"] == "jev-1.14.0"
    assert payload["state"] == OBSERVATION
    assert set(payload["questions"]["target"]["criteria"]) == {"ui_0", "ui_1", *ABSTENTIONS}
    assert 0 < sender.call_args.kwargs["timeout"] <= 1.5


@pytest.mark.parametrize("env,value", [
    ("TYPESAFE_API_KEY", ""), ("TYPESAFE_API_KEY", "bad\nkey"),
    ("JEV_MODEL", "jev-latest"), ("JEV_UI_MIN_CONFIDENCE", "NaN"),
    ("JEV_UI_MIN_CONFIDENCE", "0"), ("JEV_UI_MIN_CONFIDENCE", "1.1"),
])
def test_invalid_configuration_does_not_call_provider(monkeypatch, env, value):
    monkeypatch.setenv(env, value)
    sender = Mock()
    assert JevUiSelector(sender).select(OBSERVATION).status == "not_configured"
    sender.assert_not_called()


@pytest.mark.parametrize("problem", [
    "model", "question", "type", "extra_action", "unknown_choice", "missing_probability",
    "bad_sum", "boolean_probability", "nan_probability", "boolean_confidence", "negative_confidence",
])
def test_invalid_response_cannot_select_a_target(problem):
    result = response()
    answer = result["answers"]["target"]
    if problem == "model":
        result["model"] = "jev-1.13.0"
    elif problem == "question":
        result["answers"]["completion"] = True
    elif problem == "type":
        answer["type"] = "score"
    elif problem == "extra_action":
        answer["action"] = "drag"
    elif problem == "unknown_choice":
        answer["choice"] = "outside-node"
    elif problem == "missing_probability":
        del answer["probabilities"]["uncertain"]
    elif problem == "bad_sum":
        answer["probabilities"]["ui_0"] = 0.5
    elif problem == "boolean_probability":
        answer["probabilities"]["ui_0"] = True
    elif problem == "nan_probability":
        answer["probabilities"]["ui_0"] = float("nan")
    elif problem == "boolean_confidence":
        answer["confidence"] = True
    else:
        answer["confidence"] = -1
    outcome = JevUiSelector(Mock(return_value=(200, encoded(result)))).select(OBSERVATION)
    assert outcome.status == "invalid_response"
    assert outcome.choice is None


@pytest.mark.parametrize("raw", [b'{', b'null', b'{"answers":{},"answers":{}}', b' ' * (MAX_BYTES + 1)])
def test_malformed_duplicate_or_oversized_json_is_rejected(raw):
    result = JevUiSelector(Mock(return_value=(200, raw))).select(OBSERVATION)
    assert result.status == "invalid_response"
    assert result.choice is None


@pytest.mark.parametrize("choice,probability,confidence,status", [
    ("ui_0", 0.97, 0.5, "low_confidence"),
    ("ui_0", 0.6, 1.0, "low_confidence"),
    ("ui_0", 0.25, 1.0, "uncertain"),
    ("not_visible", 0.97, 0.96, "abstained"),
    ("uncertain", 0.97, 0.96, "abstained"),
])
def test_uncertainty_or_abstention_does_not_supply_target(choice, probability, confidence, status):
    sender = Mock(return_value=(200, encoded(response(choice, probability, confidence))))
    result = JevUiSelector(sender).select(OBSERVATION)
    assert result.status == status
    assert result.choice is None


@pytest.mark.parametrize("status", [401, 429, 503])
def test_http_failure_is_not_retried(status):
    sender = Mock(return_value=(status, b"private-provider-error"))
    result = JevUiSelector(sender).select(OBSERVATION)
    assert result.status == "provider_error"
    assert "private-provider-error" not in repr(result)
    sender.assert_called_once()


def test_timeout_or_expired_turn_cannot_return_a_target(monkeypatch):
    sender = Mock(side_effect=TimeoutError)
    assert JevUiSelector(sender).select(OBSERVATION).status == "timeout"
    sender.reset_mock()
    token = deadline.set(monotonic() - 1)
    try:
        assert JevUiSelector(sender).select(OBSERVATION).status == "deadline_exceeded"
        sender.assert_not_called()
    finally:
        deadline.reset(token)
    monkeypatch.setattr("ai_agent.runtime.jev_ui.monotonic", Mock(side_effect=[0.0, 2.0]))
    sender = Mock(return_value=(200, encoded(response())))
    assert JevUiSelector(sender).select(OBSERVATION).status == "timeout"


def test_transport_keeps_auth_on_fixed_endpoint_and_caps_response(monkeypatch):
    raw_response = Mock(status=200)
    raw_response.read.return_value = encoded(response())
    connection = Mock()
    connection.__enter__ = Mock(return_value=raw_response)
    connection.__exit__ = Mock(return_value=False)
    opener = Mock()
    opener.open.return_value = connection
    factory = Mock(return_value=opener)
    monkeypatch.setattr("ai_agent.runtime.jev_ui.build_opener", factory)
    assert JevUiSelector().select(OBSERVATION).status == "selected"
    request = opener.open.call_args.args[0]
    assert request.full_url == JEV_ENDPOINT
    assert request.method == "POST"
    assert request.get_header("Authorization") == "Bearer test-key"
    raw_response.read.assert_called_once_with(MAX_BYTES + 1)
    proxy, redirect = factory.call_args.args
    assert proxy.proxies == {}
    assert redirect.redirect_request(request, None, 302, "", {}, "https://other.test") is None


def test_provider_is_opt_in(monkeypatch):
    assert configured_ui_selector() is None
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "jev")
    assert isinstance(configured_ui_selector(), JevUiSelector)
    monkeypatch.setenv("AI1_UI_SELECTOR_PROVIDER", "typo")
    with pytest.raises(ValueError, match="AI1_UI_SELECTOR_PROVIDER"):
        configured_ui_selector()
