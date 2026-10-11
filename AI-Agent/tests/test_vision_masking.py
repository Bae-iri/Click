from dataclasses import replace
import json
import pytest
from ai_agent.runtime.privacy import sanitize_request
from ai_agent.runtime import ClickAgentEngine, CommandType, VisionElement
from test_vision_fallback import request


@pytest.mark.parametrize("private", ["김철수", "서울시 강남구 주소", "test@example.com", "010-1234-5678",
    "900101-1234567", "1234 5678 9012 3456", "OTP: 123456", "디스플레이 김철수"])
def test_arbitrary_private_ocr_never_becomes_model_input_or_target(private):
    payload = request()
    snapshot = replace(payload.vision_snapshot, provider=private, ocr_text=private,
        elements=(VisionElement(private, private, bounds_in_screen=(20, 100, 900, 200)),))
    safe = sanitize_request(replace(payload, vision_snapshot=snapshot))
    assert safe.vision_snapshot.privacy_status == "verified"
    assert safe.vision_snapshot.elements == ()
    assert safe.vision_snapshot.ocr_text is None
    assert private not in json.dumps(safe.to_dict(), ensure_ascii=False)


def test_safe_label_does_not_reflect_private_provider_or_element_id():
    payload = request()
    safe = sanitize_request(replace(payload, vision_snapshot=replace(payload.vision_snapshot,
        provider="private@example.com", elements=(VisionElement("Kim-private", "설정"),))))
    encoded = json.dumps(safe.to_dict())
    assert "Kim-private" not in encoded and "private@example.com" not in encoded
    assert safe.vision_snapshot.elements[0].element_id == "ocr_0"


@pytest.mark.parametrize("failure", ["client_did_not_mask", "sanitizer_exception"])
def test_masking_failure_blocks_and_wipes_payload(failure, monkeypatch):
    payload = request()
    if failure == "client_did_not_mask":
        payload = replace(payload, vision_snapshot=replace(payload.vision_snapshot, redaction_applied=False))
    else:
        def broken(value):
            raise RuntimeError("private screen text must not leak")
        monkeypatch.setattr("ai_agent.runtime.privacy.allowed_vision_label", broken)
    engine = ClickAgentEngine()
    response = engine.handle_turn(payload)
    assert response.guide_command.command_type == CommandType.BLOCKED
    assert response.guide_command.target_node_id is None
    assert response.debug["vision_fallback"]["status"] == "masking_failed"
    assert response.debug["observation"]["vision_element_count"] == 0
    assert "private screen text" not in json.dumps(response.to_dict())
    assert engine.traces[-1]["vision_fallback"]["status"] == "masking_failed"
