"""Per-analysis authorization at the real authenticated HTTP boundary."""
from copy import deepcopy
import time
import pytest
from sqlalchemy import select

from app.main import create_app
from app.models.lifecycle import SafetyConfirmation
from app.models.agent_session import AgentCheckpoint
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload

PATH = "/api/v1/agent/commands"


def challenge(client, *, nodes=None):
    payload = start_payload()
    payload["accessibility_tree"].update(nodes=nodes or [], active_window_id=42,
        observation_id="before-capture", captured_at_ms=int(time.time() * 1000))
    response = client.post(PATH, json=payload)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["task_status"] == "WAITING_CONFIRMATION"
    assert body["metadata"]["confirmation"]["purpose"] == "vision"
    assert body["metadata"]["confirmation"]["scope"] == "single_analysis"
    assert body["guide_command"]["proposed_action"] is None
    return payload, body


def accepted(payload, body):
    tree = deepcopy(payload["accessibility_tree"])
    tree.update(observation_id="capture-1", captured_at_ms=int(time.time() * 1000))
    return {
        "contract_version": "agent-fe-contract.v0.2", "command_type": "respond_confirmation",
        "session_id": payload["session_id"], "episode_id": body["episode_id"],
        "expected_version": body["session_version"], "request_id": "vision-accepted",
        "user_confirmation": "accepted", "confirmation_id": body["metadata"]["confirmation"]["id"],
        "accessibility_tree": tree,
        "vision_snapshot": {
            "user_consented": True, "redaction_applied": True, "provider": "mlkit_on_device_ocr",
            "package_name": tree["package_name"], "screen_width": 1080, "screen_height": 2400,
            "observation_id": tree["observation_id"], "active_window_id": 42,
            "captured_at_ms": tree["captured_at_ms"],
            "elements": [{"element_id": "display", "text": "디스플레이", "role": "menu_item",
                "bounds_in_screen": [20, 100, 900, 200], "actionable": True},
                {"element_id": "title", "text": "설정", "actionable": False,
                 "bounds_in_screen": [20, 10, 500, 70]}],
        },
    }


def test_one_analysis_per_consent_and_exact_http_retry():
    app = create_app()
    client = authenticated_client(app)
    payload, body = challenge(client)
    request = accepted(payload, body)
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["guide_command"]["target_node_id"] == "vision:ocr_0"
    assert result["metadata"]["debug"]["vision_fallback"]["status"] == "used"
    assert client.post(PATH, json=request).json() == result
    reused = {**request, "request_id": "vision-reused", "expected_version": result["session_version"]}
    assert client.post(PATH, json=reused).status_code == 409
    observe = {"contract_version": request["contract_version"], "session_id": request["session_id"],
        "episode_id": request["episode_id"], "expected_version": result["session_version"],
        "request_id": "new-observation", "command_type": "observe",
        "accessibility_tree": {**request["accessibility_tree"], "observation_id": "fresh-after-vision",
            "captured_at_ms": request["accessibility_tree"]["captured_at_ms"] + 1000}}
    again = client.post(PATH, json=observe)
    assert again.status_code == 200, again.text
    assert again.json()["metadata"]["confirmation"]["id"] != request["confirmation_id"]
    # No per-session consent is persisted in the runtime checkpoint.
    with app.state.session_factory() as db:
        checkpoint = db.scalar(select(AgentCheckpoint))
        assert "consent_request_id" not in str(checkpoint.state)
        assert "screenshot_base64" not in str(checkpoint.state)


@pytest.mark.parametrize("field,value", [
    ("user_consented", False), ("observation_id", "stale"),
    ("active_window_id", 43), ("package_name", "another.app"),
    ("screen_width", 720), ("screen_height", 1200), ("captured_at_ms", 1),
    ("screenshot_base64", "raw-image"),
])
def test_invalid_vision_is_rejected_before_runtime(field, value, monkeypatch):
    from app.clients.runtime_agent import RuntimeAgentAdapter
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request["vision_snapshot"][field] = value
    monkeypatch.setattr(RuntimeAgentAdapter, "handle_turn", lambda *a: pytest.fail("must not invoke runtime"))
    response = client.post(PATH, json=request)
    assert response.status_code == 409, response.text
    assert response.headers["X-Conflict-Code"] == "CONFIRMATION_INVALID"


@pytest.mark.parametrize("mutation", ["changed_screen", "expired", "different_owner", "missing_id", "missing_payload", "bounds"])
def test_challenge_is_bound_to_owner_screen_time_and_payload(mutation):
    app = create_app()
    client = authenticated_client(app)
    payload, body = challenge(client)
    request = accepted(payload, body)
    if mutation == "changed_screen":
        request["accessibility_tree"]["nodes"] = [{"node_id": "changed", "text": "다른 화면"}]
    elif mutation == "expired":
        with app.state.session_factory() as db:
            row = db.get(SafetyConfirmation, request["session_id"])
            row.expires_at = int(time.time()) - 1
            db.commit()
    elif mutation == "different_owner":
        client = authenticated_client(app)
    elif mutation == "missing_id":
        del request["confirmation_id"]
    elif mutation == "missing_payload":
        del request["vision_snapshot"]
    else:
        request["vision_snapshot"]["elements"][0]["bounds_in_screen"] = [-1, 1, 9999, 200]
    assert client.post(PATH, json=request).status_code in {403, 404, 409, 422}


def test_rejection_sends_no_vision_and_stops():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request.pop("vision_snapshot")
    request["user_confirmation"] = "rejected"
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "BLOCKED"
    assert response.json()["guide_command"]["target_node_id"] is None


def test_vision_consent_does_not_approve_setting_change():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request["vision_snapshot"]["elements"] = [
        {"element_id": "title", "text": "글자 크기", "actionable": False,
         "bounds_in_screen": [20, 20, 500, 90]},
        {"element_id": "slider", "text": "글자 크기", "role": "slider",
         "bounds_in_screen": [100, 430, 900, 480]}]
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    assert response.json()["guide_command"]["command_type"] == "confirmation"
    assert response.json()["guide_command"]["action_type"] == "observe_only"
    assert response.json()["metadata"]["confirmation"]["purpose"] == "action"


def test_no_signal_does_not_recapture_automatically():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request["vision_snapshot"]["elements"] = []
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "WAITING_RESULT"
    assert response.json()["guide_command"]["command_type"] == "result_confirmation"
    assert not response.json()["guide_command"]["render_policy"]
    assert response.json()["metadata"]["task_outcome"]["technical"]["status"] == "UNVERIFIABLE"
    assert "confirmation" not in response.json()["metadata"]


def test_internal_consent_binding_cannot_be_supplied_by_client():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request["vision_snapshot"]["consent_request_id"] = request["request_id"]
    assert client.post(PATH, json=request).status_code == 422


def test_frontend_masking_failure_sends_only_code_and_rejected_confirmation():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request.pop("vision_snapshot")
    request.update(user_confirmation="rejected", vision_failure="masking_failed")
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "BLOCKED"
    assert response.json()["metadata"]["vision_failure"] == "masking_failed"
    assert "개인정보" in response.json()["guide_command"]["message"]
    assert "confirmation" not in response.json()["metadata"]


def test_masking_failure_cannot_upload_the_failed_payload():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request.update(user_confirmation="rejected", vision_failure="masking_failed")
    assert client.post(PATH, json=request).status_code == 422


def test_backend_does_not_trust_client_redaction_flag():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    private = "김철수 서울시 강남구 test@example.com 010-1234-5678"
    request["vision_snapshot"].update(redaction_applied=True, provider=private, ocr_text=private,
        elements=[{"element_id": private, "text": private, "bounds_in_screen": [20, 100, 900, 200]}])
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "WAITING_RESULT"
    assert response.json()["guide_command"]["command_type"] == "result_confirmation"
    assert not response.json()["guide_command"]["render_policy"]
    assert response.json()["metadata"]["task_outcome"]["technical"]["status"] == "UNVERIFIABLE"
    assert private not in response.text
    assert response.json()["metadata"]["debug"]["vision_fallback"]["status"] == "no_signal"


def test_missing_masking_signal_blocks_consumes_consent_and_never_returns_target():
    client = authenticated_client(create_app())
    payload, body = challenge(client)
    request = accepted(payload, body)
    request["vision_snapshot"]["redaction_applied"] = False
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "WAITING_RESULT"
    assert response.json()["guide_command"]["command_type"] == "result_confirmation"
    assert not response.json()["guide_command"]["render_policy"]
    assert response.json()["metadata"]["task_outcome"]["technical"]["status"] == "UNVERIFIABLE"
    assert response.json()["guide_command"]["target_node_id"] is None
    assert response.json()["metadata"]["debug"]["vision_fallback"]["status"] == "masking_failed"
    assert "confirmation" not in response.json()["metadata"]


def test_masking_exception_never_leaks_error_or_persists_vision(monkeypatch):
    from app.models.agent_session import AgentTurn
    app = create_app()
    client = authenticated_client(app)
    payload, body = challenge(client)
    request = accepted(payload, body)
    def broken(_):
        raise RuntimeError("PRIVATE-RAW-OCR")
    monkeypatch.setattr("ai_agent.runtime.privacy.allowed_vision_label", broken)
    response = client.post(PATH, json=request)
    assert response.status_code == 200, response.text
    assert response.json()["task_status"] == "WAITING_RESULT"
    assert response.json()["guide_command"]["command_type"] == "result_confirmation"
    assert not response.json()["guide_command"]["render_policy"]
    assert response.json()["metadata"]["task_outcome"]["technical"]["status"] == "UNVERIFIABLE"
    with app.state.session_factory() as db:
        stored = str([turn.response for turn in db.scalars(select(AgentTurn))])
        checkpoint = str(db.scalar(select(AgentCheckpoint)).state)
    assert "PRIVATE-RAW-OCR" not in response.text + stored + checkpoint
    assert "screenshot_base64" not in stored + checkpoint
