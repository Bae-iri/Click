"""Accessibility node normalization."""

from __future__ import annotations

from ai_agent.runtime.keywords import any_keyword_matches, normalize_text
from ai_agent.runtime.types import AccessibilityNode, Bounds, NormalizedNode


def normalize_accessibility_tree(
    nodes: tuple[AccessibilityNode, ...],
    *,
    screen_bounds: Bounds | None = None,
) -> tuple[NormalizedNode, ...]:
    normalized: list[NormalizedNode] = []
    seen: set[tuple[str, Bounds | None, str]] = set()

    for node in nodes:
        label = _merge_label(node)
        role = infer_role(node)
        if not label and not node.scrollable and role not in {"slider", "toggle", "radio"}:
            continue

        normalized_label = normalize_text(label)
        actionable = node.enabled and (node.clickable or role in {"slider", "toggle", "radio"})
        normalized_node = NormalizedNode(
            node_id=node.node_id,
            label=normalized_label,
            role=role,
            semantic_tags=semantic_tags(normalized_label),
            bounds=node.bounds_in_screen,
            actionable=actionable,
            clickable=node.clickable,
            enabled=node.enabled,
            selected=node.selected,
            scrollable=node.scrollable,
            checkable=node.checkable,
            resource_id=node.resource_id,
            class_name=node.class_name,
            package_name=node.package_name,
            position_hint=position_hint(node.bounds_in_screen, screen_bounds),
            value=extract_numeric_value(node),
        )
        dedupe_key = (
            normalized_node.label,
            normalized_node.bounds,
            normalized_node.role,
        )
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        normalized.append(normalized_node)

    return tuple(normalized)


def infer_role(node: AccessibilityNode) -> str:
    class_name = (node.class_name or "").lower()
    resource_id = (node.resource_id or "").lower()
    label = normalize_text(_merge_label(node))

    if "seekbar" in class_name or "slider" in class_name or "slider" in resource_id:
        return "slider"
    if "switch" in class_name:
        return "toggle"
    if "radio" in class_name:
        return "radio"
    if "button" in class_name:
        return "button"
    if "edittext" in class_name:
        return "text_input"
    if "recyclerview" in class_name or "listview" in class_name or node.scrollable:
        return "list"
    if node.clickable and label:
        return "menu_item"
    return "text"


def semantic_tags(label: str) -> tuple[str, ...]:
    tags: list[str] = []
    if any_keyword_matches(label, ("디스플레이", "화면", "display", "screen")):
        tags.append("display")
    if any_keyword_matches(label, ("접근성", "시인성", "accessibility", "visibility")):
        tags.append("accessibility")
    if any_keyword_matches(label, ("글자 크기", "글자", "font size", "text size")):
        tags.append("font_size")
    if any_keyword_matches(label, ("크게", "확대", "오른쪽", "큰 글자")):
        tags.append("increase")
    return tuple(tags)


def position_hint(
    bounds: Bounds | None,
    screen_bounds: Bounds | None,
) -> str:
    if bounds is None or screen_bounds is None:
        return "unknown"

    _, top, _, bottom = bounds
    _, screen_top, _, screen_bottom = screen_bounds
    screen_height = max(1, screen_bottom - screen_top)
    center_y = (top + bottom) / 2
    ratio = (center_y - screen_top) / screen_height

    if ratio < 0.33:
        return "top"
    if ratio > 0.66:
        return "bottom"
    return "middle"


def extract_numeric_value(node: AccessibilityNode) -> float | None:
    candidate_keys = (
        "value",
        "progress",
        "current",
        "range_current",
        "percent",
    )
    for key in candidate_keys:
        raw_value = node.extras.get(key)
        if raw_value is None:
            continue
        try:
            return float(raw_value)
        except (TypeError, ValueError):
            continue
    return None


def _merge_label(node: AccessibilityNode) -> str:
    values = [
        value.strip()
        for value in (node.text, node.content_description)
        if value and value.strip()
    ]
    return " ".join(dict.fromkeys(values))
