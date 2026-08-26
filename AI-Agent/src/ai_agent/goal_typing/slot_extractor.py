"""Rule-first slot extraction for typed goals."""

from __future__ import annotations


class SlotExtractor:
    FONT_WORDS = ("글자", "글씨", "글꼴", "폰트", "font", "text")
    INCREASE_WORDS = ("크게", "키우", "확대", "잘 보이게", "더 크게")

    def extract(self, normalized_text: str) -> dict[str, str]:
        slots: dict[str, str] = {}
        lowered = normalized_text.lower()
        if any(word in lowered for word in self.FONT_WORDS):
            slots["setting"] = "font_size"
        if any(word in lowered for word in self.INCREASE_WORDS):
            slots["direction"] = "increase"
        return slots
