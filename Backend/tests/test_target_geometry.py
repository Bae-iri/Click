from tests.helpers import authenticated_client as TestClient

from app.main import create_app


def hierarchical_tree():
    return {
        "package_name": "com.android.settings", "screen_width": 1080, "screen_height": 2400,
        "nodes": [
            {"node_id": "root", "bounds_in_screen": [0, 0, 1080, 2400]},
            {"node_id": "title", "parent_id": "root", "text": "Settings",
             "bounds_in_screen": [24, 40, 1056, 96]},
            {"node_id": "display-row", "parent_id": "root", "clickable": True,
             "class_name": "android.widget.LinearLayout",
             "bounds_in_screen": [24, 300, 1056, 420]},
            {"node_id": "display-label", "parent_id": "display-row", "text": "Display",
             "class_name": "android.widget.TextView", "bounds_in_screen": [64, 332, 300, 388]},
        ],
    }


def test_api_guides_actual_clickable_parent_in_screen_pixels():
    client = TestClient(create_app())
    response = client.post("/api/v1/agent/turn", json={
        "request_id": "hierarchy-1", "session_id": "hierarchy-session",
        "raw_goal": "increase font size", "accessibility_tree": hierarchical_tree(),
    })
    assert response.status_code == 200, response.text
    guide = response.json()["guide_command"]
    assert guide["action_type"] == "tap"
    assert guide["target_node_id"] == "display-row"
    assert guide["target_bounds"] == [24, 300, 1056, 420]

    # A follow-up uses the new position, not the previous target or the label's rectangle.
    moved = hierarchical_tree()
    moved["nodes"][2]["bounds_in_screen"] = [24, 500, 1056, 620]
    moved["nodes"][3]["bounds_in_screen"] = [64, 532, 300, 588]
    follow_up = client.post("/api/v1/agent/turn", json={
        "request_id": "hierarchy-2", "session_id": "hierarchy-session",
        "expected_version": response.json()["session_version"], "accessibility_tree": moved,
    })
    assert follow_up.status_code == 200, follow_up.text
    guide = follow_up.json()["guide_command"]
    assert guide["target_node_id"] == "display-row"
    assert guide["target_bounds"] == [24, 500, 1056, 620]


def test_hidden_matching_control_cannot_steal_visible_target():
    tree = hierarchical_tree()
    tree["nodes"].insert(0, {
        "node_id": "hidden-display", "text": "Display", "clickable": True,
        "visible_to_user": False, "bounds_in_screen": [10, 10, 1000, 100],
    })
    client = TestClient(create_app())
    response = client.post("/api/v1/agent/turn", json={
        "request_id": "visible-1", "session_id": "visible-session",
        "raw_goal": "increase font size", "accessibility_tree": tree,
    })
    assert response.status_code == 200, response.text
    assert response.json()["guide_command"]["target_node_id"] == "display-row"


def test_follow_up_keeps_screen_dimensions_for_rejecting_page_sized_target():
    tree = hierarchical_tree()
    # Named parent is independently usable on turn 1; unnamed page is not a row on turn 2.
    tree["nodes"][2]["text"] = "Display"
    tree["nodes"][2]["bounds_in_screen"] = [0, 0, 1080, 2400]
    client = TestClient(create_app())
    first = client.post("/api/v1/agent/turn", json={
        "request_id": "large-1", "session_id": "large-session",
        "raw_goal": "increase font size", "accessibility_tree": tree,
    })
    assert first.status_code == 200, first.text
    tree["nodes"][2].pop("text")
    second = client.post("/api/v1/agent/turn", json={
        "request_id": "large-2", "session_id": "large-session",
        "expected_version": first.json()["session_version"], "accessibility_tree": tree,
    })
    assert second.status_code == 200, second.text
    assert second.json()["guide_command"]["target_node_id"] != "display-row"
