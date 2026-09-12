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
    seen: set[tuple] = set()
    nodes_by_id = {node.node_id: node for node in nodes}
    inherited_labels: dict[str, list[str]] = {}
    for node in nodes:
        label = _merge_label(node)
        if not label or not node.visible_to_user or not node.enabled or _is_control(node):
            continue
        target = _label_target(node, nodes_by_id, screen_bounds)
        if target is not None:
            inherited_labels.setdefault(target.node_id, []).append(label)

    for node in nodes:
        if not node.visible_to_user:
            continue
        label = _merge_label(node) or " ".join(dict.fromkeys(inherited_labels.get(node.node_id, [])))
        role = infer_role(node, label=label)
        if not label and not node.scrollable and role not in {"slider", "toggle", "radio"}:
            continue

        normalized_label = normalize_text(label)
        actionable = node.enabled and _is_control(node) and _has_area(node.bounds_in_screen)
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
            normalized_node.actionable,
            normalized_node.node_id if normalized_node.actionable else None,
        )
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        normalized.append(normalized_node)

    return tuple(normalized)


def _has_area(bounds: Bounds | None) -> bool:
    return bounds is not None and bounds[0] < bounds[2] and bounds[1] < bounds[3]


def _is_control(node: AccessibilityNode) -> bool:
    return node.clickable or infer_role(node) in {"slider", "toggle", "radio"}


def _label_target(
    label_node: AccessibilityNode,
    nodes_by_id: dict[str, AccessibilityNode],
    screen_bounds: Bounds | None,
) -> AccessibilityNode | None:
    """Name the nearest unnamed control, preserving its real ID and screen bounds.

    Never infer ancestry from an ID string or expand a text rectangle geometrically.
    Stop at control/list/input boundaries rather than borrowing a sibling's label.
    """
    child_bounds = label_node.bounds_in_screen
    if not _has_area(child_bounds):
        return None
    visited = {label_node.node_id}
    parent_id = label_node.parent_id
    while parent_id is not None and parent_id not in visited:
        visited.add(parent_id)
        parent = nodes_by_id.get(parent_id)
        if parent is None or not parent.visible_to_user or not parent.enabled:
            return None
        bounds = parent.bounds_in_screen
        if (parent.package_name != label_node.package_name or not _has_area(bounds)
                or not (bounds[0] <= child_bounds[0] < child_bounds[2] <= bounds[2]
                        and bounds[1] <= child_bounds[1] < child_bounds[3] <= bounds[3])):
            return None
        if parent.scrollable or infer_role(parent) in {"list", "text_input"}:
            return None
        if _is_control(parent):
            # Root/page-sized containers are not a defensible target for one text label.
            if parent.parent_id is None or _merge_label(parent):
                return None
            if _has_area(screen_bounds):
                screen_area = (screen_bounds[2] - screen_bounds[0]) * (screen_bounds[3] - screen_bounds[1])
                if (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]) >= screen_area / 2:
                    return None
            return parent
        parent_id = parent.parent_id
    return None


def infer_role(node: AccessibilityNode, *, label: str | None = None) -> str:
    class_name = (node.class_name or "").lower()
    package_name = (node.package_name or "").lower()
    resource_id = (node.resource_id or "").lower()
    label = normalize_text(_merge_label(node) if label is None else label)

    if _looks_like_launcher_app_icon(package_name, resource_id, label, node.clickable):
        return "app_icon"
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


def _looks_like_launcher_app_icon(
    package_name: str,
    resource_id: str,
    label: str,
    clickable: bool,
) -> bool:
    if not clickable or not label:
        return False
    launcher_packages = (
        "launcher",
        "nexuslauncher",
        "trebuchet",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
    )
    launcher_resources = ("workspace", "hotseat", "apps_view", "app_icon")
    return any(candidate in package_name for candidate in launcher_packages) or any(
        candidate in resource_id for candidate in launcher_resources
    )


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
