from dataclasses import replace

import pytest

from ai_agent.runtime import AccessibilityNode
from ai_agent.runtime.normalizer import normalize_accessibility_tree


SCREEN = (0, 0, 1080, 2400)
ROOT = AccessibilityNode("root", package_name="com.android.settings", bounds_in_screen=SCREEN)
ROW = AccessibilityNode(
    "display-row", parent_id="root", package_name=ROOT.package_name,
    class_name="android.widget.LinearLayout", bounds_in_screen=(24, 300, 1056, 420),
    clickable=True,
)
LABEL = AccessibilityNode(
    "display-text", parent_id=ROW.node_id, package_name=ROOT.package_name,
    class_name="android.widget.TextView", text="Display", bounds_in_screen=(64, 332, 300, 388),
)


def normalized(*nodes):
    return {node.node_id: node for node in normalize_accessibility_tree(nodes, screen_bounds=SCREEN)}


def test_text_names_clickable_row_without_rewriting_either_rectangle():
    result = normalized(ROOT, ROW, LABEL)
    assert result[ROW.node_id].label == "display"
    assert result[ROW.node_id].role == "menu_item"
    assert result[ROW.node_id].actionable
    assert result[ROW.node_id].bounds == ROW.bounds_in_screen
    assert result[LABEL.node_id].bounds == LABEL.bounds_in_screen
    assert not result[LABEL.node_id].actionable


def test_intermediate_containers_and_input_order_do_not_change_target():
    container = replace(ROW, node_id="wrapper", parent_id=ROW.node_id, clickable=False)
    label = replace(LABEL, parent_id=container.node_id)
    result = normalized(label, container, ROW, ROOT)
    assert result[ROW.node_id].label == "display"
    assert result[ROW.node_id].bounds == ROW.bounds_in_screen


def test_sibling_controls_keep_their_own_labels_and_bounds():
    other_row = replace(ROW, node_id="other-row", bounds_in_screen=(24, 450, 1056, 570))
    other_label = replace(LABEL, node_id="other-text", parent_id=other_row.node_id,
                          text="Accessibility", bounds_in_screen=(64, 482, 300, 538))
    result = normalized(ROOT, ROW, LABEL, other_row, other_label)
    assert result[ROW.node_id].label == "display"
    assert result[other_row.node_id].label == "accessibility"
    assert result[other_row.node_id].bounds == other_row.bounds_in_screen


@pytest.mark.parametrize("row", [
    replace(ROW, enabled=False),
    replace(ROW, visible_to_user=False),
    replace(ROW, scrollable=True),
    replace(ROW, class_name="android.widget.RecyclerView"),
    replace(ROW, class_name="android.widget.EditText"),
    replace(ROW, parent_id=None),
    replace(ROW, bounds_in_screen=SCREEN),
    replace(ROW, bounds_in_screen=(400, 300, 1000, 420)),
    replace(ROW, bounds_in_screen=(24, 300, 24, 420)),
    replace(ROW, package_name="other.app"),
])
def test_labels_are_not_promoted_to_invalid_or_overly_broad_parents(row):
    result = normalized(ROOT, row, LABEL)
    assert row.node_id not in result or result[row.node_id].label != "display"


def test_an_existing_accessible_name_takes_precedence_over_child_text():
    result = normalized(ROOT, replace(ROW, content_description="Open details"), LABEL)
    assert result[ROW.node_id].label == "open details"


def test_visible_child_title_can_name_a_row_with_its_own_accessible_description():
    result = normalized(ROOT, replace(ROW, content_description="Open details"), LABEL)
    assert "display" in result[ROW.node_id].label_parts
    assert result[ROW.node_id].bounds == ROW.bounds_in_screen
    assert not result[LABEL.node_id].actionable


def test_child_summary_does_not_become_a_named_rows_primary_label():
    summary = replace(LABEL, text="Display", resource_id="android:id/summary")
    result = normalized(ROOT, replace(ROW, content_description="Wallpaper"), summary)
    assert result[ROW.node_id].label_parts == ("wallpaper",)


def test_clickable_child_keeps_its_target_and_does_not_name_an_outer_control():
    result = normalized(ROOT, ROW, replace(LABEL, clickable=True))
    assert ROW.node_id not in result
    assert result[LABEL.node_id].actionable
    assert result[LABEL.node_id].bounds == LABEL.bounds_in_screen


@pytest.mark.parametrize("label", [
    replace(LABEL, visible_to_user=False),
    replace(LABEL, enabled=False),
    replace(LABEL, bounds_in_screen=(64, 332, 64, 388)),
    replace(LABEL, parent_id="missing"),
])
def test_hidden_disabled_empty_or_orphaned_labels_do_not_name_controls(label):
    assert ROW.node_id not in normalized(ROOT, ROW, label)


def test_cyclic_noninteractive_hierarchy_terminates_without_guessing():
    a = replace(ROW, node_id="a", parent_id="b", clickable=False)
    b = replace(ROW, node_id="b", parent_id="a", clickable=False)
    result = normalized(a, b, replace(LABEL, parent_id="a"))
    assert all(not node.actionable for node in result.values())


def test_hidden_controls_are_absent_and_empty_bounds_cannot_be_action_targets():
    assert normalized(replace(LABEL, clickable=True, visible_to_user=False)) == {}
    result = normalized(replace(LABEL, clickable=True, bounds_in_screen=(0, 0, 0, 0)))
    assert not result[LABEL.node_id].actionable


def test_flat_legacy_payload_keeps_its_original_click_target():
    node = replace(LABEL, parent_id=None, clickable=True)
    result = normalized(node)
    assert result[node.node_id].bounds == node.bounds_in_screen
    assert result[node.node_id].actionable
