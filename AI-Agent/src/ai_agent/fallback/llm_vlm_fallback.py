"""Vision fallback boundary for low-confidence or missing Accessibility data."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any

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
        # Client assertions cannot authorize unverified raw-image egress.
        return VisionFallbackAnalysis(status="not_configured",
            reason="server_image_redaction_unavailable", provider="local")


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
