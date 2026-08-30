"""Rule-based goal typing for MVP-01."""

from __future__ import annotations

from ai_agent.runtime.keywords import normalize_text
from ai_agent.runtime.tasks.mvp_01_font_size_increase import GOAL_TYPE, TASK_ID
from ai_agent.runtime.types import RawGoalInput, TypedGoal


FONT_SIZE_TERMS = ("글자 크기", "글자", "font size", "text size", "font", "text")
DISPLAY_SIZE_TERMS = ("화면 크기", "화면 확대", "화면 크게", "display size", "screen size")
INCREASE_TERMS = (
    "increase",
    "bigger",
    "larger",
    "크게",
    "키우",
    "키워",
    "확대",
    "잘 보이",
    "더 크게",
    "작아",
    "작게 보여",
)
SETTINGS_TERMS = ("설정", "휴대폰", "핸드폰", "폰", "android")


def type_goal(raw_goal: RawGoalInput) -> TypedGoal:
    source = raw_goal.source_text
    normalized = normalize_text(source)

    has_font_signal = any(normalize_text(term) in normalized for term in FONT_SIZE_TERMS)
    has_display_size_signal = any(
        normalize_text(term) in normalized for term in DISPLAY_SIZE_TERMS
    )
    has_increase_signal = any(
        normalize_text(term) in normalized for term in INCREASE_TERMS
    )
    has_app_signal = (
        raw_goal.device_context.platform == "android"
        or raw_goal.device_context.app_package == "com.android.settings"
        or any(normalize_text(term) in normalized for term in SETTINGS_TERMS)
    )

    if has_display_size_signal and not has_font_signal:
        return _clarify(
            raw_goal,
            confidence=0.62,
            message="화면 전체 크기인지, 글자 크기인지 확인이 필요해요.",
        )

    slots: dict[str, str] = {}
    if has_font_signal:
        slots["setting"] = "font_size"
    if has_increase_signal:
        slots["direction"] = "increase"

    required_slots_filled = slots == {"setting": "font_size", "direction": "increase"}
    keyword_score = 0.3 if has_font_signal else 0.0
    direction_score = 0.2 if has_increase_signal else 0.0
    slot_score = 0.25 if required_slots_filled else 0.0
    app_prior = 0.1 if has_app_signal else 0.0
    confidence = min(0.95, keyword_score + direction_score + slot_score + app_prior)

    needs_clarification = confidence < 0.8 or not required_slots_filled
    clarification_message = None
    if needs_clarification:
        clarification_message = "글자 크기를 더 크게 바꾸려는 게 맞나요?"

    return TypedGoal(
        goal_type=GOAL_TYPE if has_font_signal else "unknown",
        target_app="android_settings" if has_app_signal else "unknown",
        task_type="settings_change" if has_font_signal else "unknown",
        slots=slots,
        source_text=source,
        confidence=confidence,
        task_id=TASK_ID if required_slots_filled and confidence >= 0.8 else None,
        typing_method="keyword+slot_rule+app_prior",
        is_supported=required_slots_filled and confidence >= 0.8,
        required_slots_filled=required_slots_filled,
        needs_clarification=needs_clarification,
        clarification_message=clarification_message,
    )


def _clarify(raw_goal: RawGoalInput, *, confidence: float, message: str) -> TypedGoal:
    return TypedGoal(
        goal_type="ambiguous",
        target_app="android_settings",
        task_type="settings_change",
        slots={},
        source_text=raw_goal.source_text,
        confidence=confidence,
        task_id=None,
        typing_method="keyword+slot_rule+app_prior",
        is_supported=False,
        required_slots_filled=False,
        needs_clarification=True,
        clarification_message=message,
    )
