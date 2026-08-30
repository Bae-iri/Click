"""Vision fallback boundary for low-confidence or missing Accessibility data."""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import os
from typing import Any
from urllib import error, request

from ai_agent.runtime.types import AccessibilityNode, Bounds, VisionElement, VisionSnapshot


@dataclass(frozen=True, slots=True)
class VisionFallbackAnalysis:
    status: str
    reason: str
    provider: str
    elements: tuple[VisionElement, ...] = ()
    ocr_text: str | None = None

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["element_count"] = len(self.elements)
        data.pop("elements", None)
        return data


class LLMVLMFallback:
    """Analyze a consented vision snapshot without leaking raw images into traces."""

    provider = "vision-fallback"

    def analyze(
        self,
        snapshot: VisionSnapshot | None,
        *,
        reason: str,
    ) -> VisionFallbackAnalysis:
        if snapshot is None:
            return VisionFallbackAnalysis(
                status="not_requested",
                reason=reason,
                provider=self.provider,
            )
        if not snapshot.user_consented:
            return VisionFallbackAnalysis(
                status="consent_required",
                reason="external_vlm_processing",
                provider=snapshot.provider or self.provider,
            )
        if snapshot.elements or snapshot.ocr_text:
            return VisionFallbackAnalysis(
                status="used",
                reason=reason,
                provider=snapshot.provider or "frontend_vision_payload",
                elements=snapshot.elements,
                ocr_text=snapshot.ocr_text,
            )
        if snapshot.screenshot_base64:
            return self._analyze_with_gemini(snapshot, reason=reason)
        return VisionFallbackAnalysis(
            status="not_configured",
            reason="vision_snapshot_has_no_elements_or_image",
            provider=snapshot.provider or self.provider,
        )

    def _analyze_with_gemini(
        self,
        snapshot: VisionSnapshot,
        *,
        reason: str,
    ) -> VisionFallbackAnalysis:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            return VisionFallbackAnalysis(
                status="not_configured",
                reason="GEMINI_API_KEY is not set",
                provider="gemini",
            )

        endpoint = (
            "https://generativelanguage.googleapis.com/v1beta/"
            f"models/{os.environ.get('GEMINI_VISION_MODEL', 'gemini-2.5-flash')}:generateContent"
            f"?key={api_key}"
        )
        prompt = (
            "Return compact JSON only. Detect visible Android UI text for a task that "
            "changes font size. Shape: {\"elements\":[{\"text\":\"...\","
            "\"bounds_in_screen\":[left,top,right,bottom],\"role\":\"menu_item\","
            "\"confidence\":0.0,\"actionable\":true}],\"ocr_text\":\"...\"}."
        )
        body = {
            "contents": [
                {
                    "parts": [
                        {"text": prompt},
                        {
                            "inline_data": {
                                "mime_type": snapshot.mime_type,
                                "data": snapshot.screenshot_base64,
                            }
                        },
                    ]
                }
            ]
        }
        try:
            req = request.Request(
                endpoint,
                data=json.dumps(body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with request.urlopen(req, timeout=8) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except (OSError, error.URLError, json.JSONDecodeError) as exc:
            return VisionFallbackAnalysis(
                status="provider_error",
                reason=str(exc),
                provider="gemini",
            )

        text = _extract_model_text(payload)
        parsed = _parse_json_object(text)
        if not parsed:
            return VisionFallbackAnalysis(
                status="provider_error",
                reason="Gemini response did not contain parseable JSON",
                provider="gemini",
            )
        elements = tuple(
            _vision_element_from_dict(index, element)
            for index, element in enumerate(parsed.get("elements") or ())
            if isinstance(element, dict) and element.get("text")
        )
        return VisionFallbackAnalysis(
            status="used" if elements or parsed.get("ocr_text") else "no_signal",
            reason=reason,
            provider="gemini",
            elements=elements,
            ocr_text=parsed.get("ocr_text"),
        )


def vision_elements_to_accessibility_nodes(
    analysis: VisionFallbackAnalysis,
    *,
    package_name: str | None,
) -> tuple[AccessibilityNode, ...]:
    nodes: list[AccessibilityNode] = []
    for index, element in enumerate(analysis.elements):
        nodes.append(
            AccessibilityNode(
                node_id=f"vision:{element.element_id or index}",
                package_name=package_name,
                class_name=_class_name_for_role(element.role),
                text=element.text,
                bounds_in_screen=element.bounds_in_screen,
                clickable=element.actionable,
                enabled=True,
                extras={
                    "vision_confidence": element.confidence,
                    "vision_provider": analysis.provider,
                },
            )
        )
    if analysis.ocr_text and not nodes:
        nodes.append(
            AccessibilityNode(
                node_id="vision:ocr_text",
                package_name=package_name,
                class_name="android.widget.TextView",
                text=analysis.ocr_text,
                clickable=False,
                enabled=True,
                extras={"vision_provider": analysis.provider},
            )
        )
    return tuple(nodes)


def _class_name_for_role(role: str | None) -> str:
    if role == "slider":
        return "android.widget.SeekBar"
    if role == "button":
        return "android.widget.Button"
    return "android.widget.TextView"


def _extract_model_text(payload: dict[str, Any]) -> str:
    candidates = payload.get("candidates") or ()
    parts = (
        candidates[0].get("content", {}).get("parts", ())
        if candidates and isinstance(candidates[0], dict)
        else ()
    )
    return "\n".join(part.get("text", "") for part in parts if isinstance(part, dict))


def _parse_json_object(text: str) -> dict[str, Any] | None:
    stripped = text.strip()
    if stripped.startswith("```"):
        stripped = stripped.strip("`")
        stripped = stripped.removeprefix("json").strip()
    start = stripped.find("{")
    end = stripped.rfind("}")
    if start < 0 or end < start:
        return None
    try:
        parsed = json.loads(stripped[start : end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _vision_element_from_dict(index: int, data: dict[str, Any]) -> VisionElement:
    return VisionElement(
        element_id=str(data.get("element_id") or data.get("id") or index),
        text=str(data["text"]),
        bounds_in_screen=_bounds_from_value(data.get("bounds_in_screen") or data.get("bounds")),
        role=data.get("role"),
        confidence=_float_or_none(data.get("confidence")),
        actionable=bool(data.get("actionable", True)),
    )


def _bounds_from_value(value: Any) -> Bounds | None:
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        return None
    try:
        left, top, right, bottom = (int(item) for item in value)
    except (TypeError, ValueError):
        return None
    if right < left or bottom < top:
        return None
    return (left, top, right, bottom)


def _float_or_none(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
