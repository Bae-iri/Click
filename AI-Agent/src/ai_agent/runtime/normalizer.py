"""Accessibility node normalization."""

from __future__ import annotations

from math import isfinite

from ai_agent.runtime.keywords import any_keyword_matches, normalize_text
from ai_agent.runtime.types import AccessibilityNode, Bounds, NormalizedNode


def normalize_accessibility_tree(
    nodes: tuple[AccessibilityNode, ...],
    *,
    screen_bounds: Bounds | None = None,
    active_window_id: int | None = None,
) -> tuple[NormalizedNode, ...]:
    if active_window_id is not None:
        nodes = tuple(node for node in nodes if node.window_id == active_window_id)
    normalized: list[NormalizedNode] = []
    seen: set[tuple] = set()
    nodes_by_id = {node.node_id: node for node in nodes}
    inherited_labels: dict[str, list[str]] = {}
    inherited_titles: dict[str, list[tuple]] = {}
    for node in nodes:
        label = _merge_label(node)
        if not label or not node.visible_to_user or not node.enabled or _is_control(node):
            continue
        target = _label_target(node, nodes_by_id, screen_bounds)
        if target is not None:
            inherited_labels.setdefault(target.node_id, []).append(label)
            inherited_titles.setdefault(target.node_id, []).append((
                _description(node), not (node.resource_id or "").endswith(":id/title"), node.bounds_in_screen[1],
                node.bounds_in_screen[0], node.node_id, label))

    for node in nodes:
        if not node.visible_to_user:
            continue
        # OEM SeekBars may expose checked tick children as SeekBars too. Only
        # suppress a valueless native child inside its real parent range; two
        # actual ranges must remain distinct and ambiguous.
        parent = nodes_by_id.get(node.parent_id)
        if (parent and infer_role(node) == 'slider' and extract_numeric_value(node) is None
                and infer_role(parent) == 'slider' and parent.visible_to_user and parent.enabled
                and node.package_name == parent.package_name and node.window_id == parent.window_id
                and extract_confidence(node) is None and extract_confidence(parent) is None
                and not node.node_id.startswith('vision:') and not parent.node_id.startswith('vision:')
                and _has_area(node.bounds_in_screen) and _has_area(parent.bounds_in_screen)):
            progress, minimum, maximum = (parent.extras.get(k) for k in ('progress', 'min', 'max'))
            bounds, child = parent.bounds_in_screen, node.bounds_in_screen
            if (all(type(v) in (int, float) and isfinite(v) for v in (progress, minimum, maximum))
                    and minimum < maximum and minimum <= progress <= maximum
                    and bounds[0] <= child[0] < child[2] <= bounds[2]
                    and bounds[1] <= child[1] < child[3] <= bounds[3]):
                continue
        label = _merge_label(node)
        # Numeric descriptions describe a slider's changing value, not its setting.
        if (infer_role(node) == 'slider' and inherited_labels.get(node.node_id)
                and (not label or _numeric_slider_label(label))):
            label = " ".join(dict.fromkeys(inherited_labels[node.node_id]))
        label = label or " ".join(dict.fromkeys(inherited_labels.get(node.node_id, [])))
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
            window_id=node.window_id,
            position_hint=position_hint(node.bounds_in_screen, screen_bounds),
            value=extract_numeric_value(node),
            range_min=node.extras.get("min"),
            range_max=node.extras.get("max"),
            label_parts=_label_parts(node, inherited_titles.get(node.node_id, ())),
            structure_role=_structure_role(node, nodes_by_id),
            source_confidence=extract_confidence(node),
            checked=node.extras.get("checked") if node.checkable and type(node.extras.get("checked")) is bool else None,
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


def _label_parts(node, child_titles):
    # A row's accessibility description and its visible title are separate evidence.
    # Keep its own name, but do not discard a real descendant title when that name exists.
    titles = [entry for entry in child_titles if not entry[0]]
    # A visible descendant title is the identity of a row. Its changing content
    # description/summary must not become a second, competing menu identity.
    parts = [min(titles)[-1]] if titles else [
        part for part in (node.text, node.content_description) if part and part.strip()][:1]
    return tuple(dict.fromkeys(normalize_text(part) for part in parts))


def _description(node):
    resource = (node.resource_id or "").lower().rsplit("/", 1)[-1]
    return "summary" in resource or resource in {"subtitle", "description", "secondary_text"}


def _numeric_slider_label(label):
    import re
    return re.fullmatch(r'[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)\s*%?', label.strip()) is not None


def _structure_role(node, nodes_by_id):
    if _is_control(node):
        return "control"
    if node.scrollable or infer_role(node) == "list":
        return "container"
    if _description(node):
        return "description"
    seen = {node.node_id}
    parent_id = node.parent_id
    while parent_id and parent_id not in seen:
        seen.add(parent_id)
        parent = nodes_by_id.get(parent_id)
        if parent is None:
            break
        if _is_control(parent) or parent.scrollable or infer_role(parent) == "list":
            return "control_label"
        parent_id = parent.parent_id
    return "screen_text"


def _has_area(bounds: Bounds | None) -> bool:
    return bounds is not None and bounds[0] < bounds[2] and bounds[1] < bounds[3]


def _is_control(node: AccessibilityNode) -> bool:
    return node.clickable or infer_role(node) in {"slider", "toggle", "radio"}


def _label_target(
    label_node: AccessibilityNode,
    nodes_by_id: dict[str, AccessibilityNode],
    screen_bounds: Bounds | None,
) -> AccessibilityNode | None:
    """Associate a title with its nearest control, preserving its real ID and bounds.

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
        if (parent.package_name != label_node.package_name or parent.window_id != label_node.window_id
                or not _has_area(bounds)
                or not (bounds[0] <= child_bounds[0] < child_bounds[2] <= bounds[2]
                        and bounds[1] <= child_bounds[1] < child_bounds[3] <= bounds[3])):
            return None
        if parent.scrollable or infer_role(parent) in {"list", "text_input"}:
            return None
        # Native preference rows can expose the title and Switch as siblings,
        # including inside a clickable row. Bind the title to that one checked
        # widget before treating the whole preference as a navigation menu.
        if (label_node.resource_id == 'android:id/title'
                and label_node.package_name in {'com.android.settings', 'com.samsung.android.settings'}
                and extract_confidence(label_node) is None and not label_node.node_id.startswith('vision:')
                and parent.parent_id is not None):
            height = ((screen_bounds[3] - screen_bounds[1]) if _has_area(screen_bounds)
                      else max(n.bounds_in_screen[3] for n in nodes_by_id.values()))
            if bounds[3] - bounds[1] < height / 2:
                children = [n for n in nodes_by_id.values()
                    if n.visible_to_user and n.package_name == parent.package_name
                    and n.window_id == parent.window_id
                    and _descends_from(n, parent.node_id, nodes_by_id)]
                switches = [n for n in children if infer_role(n) == 'toggle']
                titles = [n for n in children if n.resource_id == 'android:id/title']
                if len(switches) == len(titles) == 1:
                    switch = switches[0]
                    from ai_agent.runtime.settings_controls import GENERIC_LABELS
                    switch_name = normalize_text(_merge_label(switch))
                    title_name = normalize_text(_merge_label(label_node))
                    if (switch.enabled and switch.checkable and type(switch.extras.get('checked')) is bool
                            and (not switch_name or switch_name in GENERIC_LABELS or switch_name == title_name)
                            and switch.resource_id in {'android:id/switch_widget', 'com.android.settings:id/switch_widget'}
                            and extract_confidence(switch) is None and not switch.node_id.startswith('vision:')
                            and _has_area(switch.bounds_in_screen)
                            and bounds[0] <= switch.bounds_in_screen[0] < switch.bounds_in_screen[2] <= bounds[2]
                            and bounds[1] <= switch.bounds_in_screen[1] < switch.bounds_in_screen[3] <= bounds[3]):
                        return switch
        if _is_control(parent):
            # Root/page-sized containers are not a defensible target for one text label.
            if parent.parent_id is None:
                return None
            if _has_area(screen_bounds):
                screen_area = (screen_bounds[2] - screen_bounds[0]) * (screen_bounds[3] - screen_bounds[1])
                if (bounds[2] - bounds[0]) * (bounds[3] - bounds[1]) >= screen_area / 2:
                    return None
            return parent
        # Volume pages label each SeekBar in a small non-clickable row. Associate
        # only a real sibling control in that row, never a page/list-wide label.
        if parent.parent_id is not None and not _description(label_node):
            height = ((screen_bounds[3] - screen_bounds[1]) if _has_area(screen_bounds)
                      else max(n.bounds_in_screen[3] for n in nodes_by_id.values()))
            if bounds[3] - bounds[1] < height / 2:
                sliders = [n for n in nodes_by_id.values()
                           if n.visible_to_user and n.enabled and infer_role(n) == 'slider'
                           and extract_numeric_value(n) is not None
                           and n.package_name == parent.package_name and n.window_id == parent.window_id
                           and _descends_from(n, parent.node_id, nodes_by_id)]
                if len(sliders) == 1:
                    return sliders[0]
        parent_id = parent.parent_id
    return None


def _descends_from(node, ancestor_id, nodes_by_id):
    visited = {node.node_id}
    parent_id = node.parent_id
    while parent_id and parent_id not in visited:
        if parent_id == ancestor_id:
            return True
        visited.add(parent_id)
        parent = nodes_by_id.get(parent_id)
        if (parent is None or parent.scrollable or (_is_control(parent)
                and not (infer_role(parent) == 'slider' and extract_numeric_value(parent) is None))):
            return False
        parent_id = parent.parent_id
    return False


def infer_role(node: AccessibilityNode, *, label: str | None = None) -> str:
    class_name = (node.class_name or "").lower()
    package_name = (node.package_name or "").lower()
    resource_id = (node.resource_id or "").lower()
    label = normalize_text(_merge_label(node) if label is None else label)

    # Samsung exposes mutually exclusive sound/vibrate/mute choices as plain
    # clickable LinearLayouts. Their "Sound" label is not a Settings menu row.
    if package_name in {"com.android.settings", "com.samsung.android.settings"} and resource_id in {
            "com.android.settings:id/low_button", "com.android.settings:id/mid_button",
            "com.android.settings:id/high_button"}:
        return "radio"
    if _looks_like_launcher_app_icon(package_name, resource_id, label, node.clickable):
        return "app_icon"
    declared_range = [node.extras.get(key) for key in ("progress", "min", "max")]
    if (node.role == "slider" and all(type(value) in (int, float) and isfinite(value) for value in declared_range)
            and declared_range[1] < declared_range[2] and declared_range[1] <= declared_range[0] <= declared_range[2]):
        return "slider"
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


def extract_confidence(node: AccessibilityNode) -> float | None:
    raw_value = node.extras.get("vision_confidence")
    if type(raw_value) not in (int, float):
        return None
    value = float(raw_value)
    return value if 0.0 <= value <= 1.0 else None


def _merge_label(node: AccessibilityNode) -> str:
    values = [
        value.strip()
        for value in (node.text, node.content_description)
        if value and value.strip()
    ]
    return " ".join(dict.fromkeys(values))
