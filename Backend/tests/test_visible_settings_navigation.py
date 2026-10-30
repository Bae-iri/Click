"""DB graph routing with named Android rows and visible child titles."""
import pytest

from app.main import create_app
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


def screen(title, rows):
    package = "com.android.settings"
    nodes = [
        {"node_id": "root", "bounds_in_screen": [0, 0, 1080, 2400]},
        {"node_id": "title", "parent_id": "root", "text": title,
         "class_name": "android.widget.TextView", "bounds_in_screen": [40, 80, 1000, 180]},
        {"node_id": "list", "parent_id": "root", "scrollable": True,
         "class_name": "androidx.recyclerview.widget.RecyclerView",
         "bounds_in_screen": [0, 240, 1080, 2200]},
    ]
    for index, (key, label, description) in enumerate(rows):
        top = 260 + index * 220
        nodes.extend([
            {"node_id": key, "parent_id": "list", "clickable": True,
             "class_name": "android.widget.LinearLayout", "content_description": description,
             "bounds_in_screen": [0, top, 1080, top + 200]},
            {"node_id": key + "-title", "parent_id": key, "text": label,
             "resource_id": "android:id/title", "class_name": "android.widget.TextView",
             "bounds_in_screen": [40, top + 20, 1000, top + 80]},
        ])
    return {"package_name": package, "screen_width": 1080, "screen_height": 2400,
            "nodes": [{**node, "package_name": package} for node in nodes]}


@pytest.mark.parametrize("direction", ["increase", "decrease"])
@pytest.mark.parametrize("start_on_display", [False, True])
def test_typed_command_targets_visible_title_inside_named_row(direction, start_on_display):
    app = create_app()
    with authenticated_client(app) as client:
        payload = start_payload()
        payload["goal"].update(catalog_version="2.1.0", goal_type="settings.font_size." + direction)
        payload["goal"]["slots"]["direction"] = direction
        if start_on_display:
            payload["accessibility_tree"] = screen("디스플레이", [
                ("font", "글자 크기와 스타일", "글꼴 설정 열기"),
            ])
        else:
            payload["accessibility_tree"] = screen("설정", [
                ("display", "디스플레이", "밝기와 화면 설정"),
                ("accessibility", "접근성", "접근성"),
            ])
        response = client.post("/api/v1/agent/commands", json=payload)
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["graph_reference"]["version"] == "1.0.1"
        assert body["state_id"] == ("S2_DISPLAY_MENU" if start_on_display else "S1_SETTINGS_HOME")
        guide = body["guide_command"]
        assert guide["target_node_id"] == ("font" if start_on_display else "display")
        assert guide["target_bounds"] == [0, 260, 1080, 460]
        assert "접근성" not in guide["message"]


@pytest.mark.parametrize("page", ["scrolled_home", "accessibility"])
def test_off_path_and_offscreen_are_distinct_recovery_actions(page):
    with authenticated_client(create_app()) as client:
        payload = start_payload()
        tree = screen("설정" if page == "scrolled_home" else "접근성", [
            ("accessibility", "접근성" if page == "scrolled_home" else "시인성 향상", "시인성 향상")])
        if page == "accessibility":
            tree["nodes"].append({"node_id": "back", "parent_id": "root", "clickable": True,
                "package_name": "com.android.settings", "content_description": "뒤로",
                "class_name": "android.widget.ImageButton", "bounds_in_screen": [0, 80, 40, 180]})
        payload["accessibility_tree"] = tree
        response = client.post("/api/v1/agent/commands", json=payload)
        assert response.status_code == 200, response.text
        guide = response.json()["guide_command"]
        policy = guide["render_policy"]
        if page == "scrolled_home":
            assert policy["recovery"]["reason_code"] == "TARGET_NOT_VISIBLE"
            assert guide["target_node_id"] is None
            assert "디스플레이" in guide["message"]
        else:
            assert policy["recovery_action"]["type"] == "navigate_back"
            assert "설정 앱" not in guide["message"]
