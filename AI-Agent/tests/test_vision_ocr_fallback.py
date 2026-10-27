from ai_agent.runtime.vision import VisionFallback
from ai_agent.runtime.types import VisionElement, VisionSnapshot


def snapshot(*elements, **overrides):
    values = {
        "user_consented": True,
        "consent_request_id": "trusted-unit-request",
        "privacy_status": "verified",
        "redaction_applied": True,
        "provider": "frontend_ocr",
        "elements": tuple(elements),
    }
    values.update(overrides)
    return VisionSnapshot(**values)


def test_accepts_only_consented_redacted_frontend_ocr_elements():
    element = VisionElement("display", "Display", (10, 20, 300, 80), "menu_item", 0.9)
    result = VisionFallback().analyze(snapshot(element), reason="target_not_found")
    assert result.status == "used"
    assert result.provider == "frontend_ocr"
    assert result.elements == (element,)


def test_filters_low_confidence_and_coordinate_free_elements():
    result = VisionFallback().analyze(snapshot(
        VisionElement("noise", "noise", (1, 1, 10, 10), confidence=0.2),
        VisionElement("no-box", "Display", confidence=0.99),
    ), reason="target_not_found")
    assert result.status == "no_signal"


def test_unknown_confidence_abstains_instead_of_guessing():
    result = VisionFallback().analyze(snapshot(
        VisionElement("unknown", "Display", (1, 1, 100, 30), confidence=None),
    ), reason="target_not_found")
    assert result.status == "no_signal"


def test_rejects_raw_images_wrong_provider_and_missing_redaction():
    raw = VisionFallback().analyze(snapshot(screenshot_base64="RAW"), reason="test")
    assert raw.status == "not_configured"
    assert raw.reason == "raw_screenshot_ingress_forbidden"

    provider = VisionFallback().analyze(
        snapshot(provider="external_vlm"), reason="test")
    assert provider.reason == "unsupported_vision_provider"

    redaction = VisionFallback().analyze(
        snapshot(redaction_applied=False), reason="test")
    assert redaction.reason == "redaction_not_attested"
