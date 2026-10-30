"""Galaxy's combined font page must reach the result prompt through DB-owned graphs."""
import pytest

from tests.test_task_outcomes import Flow, screen


def samsung_screen(value=4, at=1000, scale=1.3):
    tree = screen(value, at, scale)
    slider = tree["nodes"][0]
    slider.update(text=None, content_description=str(value + 1), clickable=False,
                  resource_id="com.android.settings:id/seekBarForFontSize",
                  bounds=[135, 2009, 945, 2071])
    tree["nodes"].extend([
        {"node_id": "title", "text": "글자 크기와 스타일", "bounds": [158, 179, 688, 274]},
        {"node_id": "font-family", "text": "글꼴", "clickable": True,
         "bounds": [68, 1372, 1024, 1543]},
        {"node_id": "font-label", "text": "글자 크기", "bounds": [45, 1826, 359, 1995]},
        {"node_id": "smaller", "content_description": "글자 크기 작게", "clickable": True,
         "bounds": [45, 1995, 135, 2085]},
        {"node_id": "larger", "content_description": "글자 크기 크게", "clickable": True,
         "bounds": [945, 1995, 1035, 2085]},
    ])
    return tree


@pytest.mark.parametrize("direction", ["increase", "decrease"])
@pytest.mark.parametrize("action_observed", [True, False])
def test_samsung_slider_reaches_result_without_repeating_ambiguous_target(direction, action_observed):
    flow = Flow()
    ready = flow.prepare(direction=direction)
    started = flow.send("start_task", preparation_version="goal-inputs.v1",
        goal_resolution_id=ready["preparation"]["id"], goal=ready["goal"], source_text=flow.source_text,
        device_context={"platform": "android", "locale": "ko-KR"}, accessibility_tree=samsung_screen())
    assert started["state_id"] == "S4_FONT_SIZE_CONTROL"
    assert started["guide_command"]["target_node_id"] == "font-slider"
    assert started["task_status"] == "WAITING_CONFIRMATION"
    flow.send("respond_confirmation", user_confirmation="accepted",
        confirmation_id=started["metadata"]["confirmation"]["id"], accessibility_tree=samsung_screen(at=1100))
    value, scale = (5, 1.5) if direction == "increase" else (3, 1.15)
    kwargs = {"user_action_event": flow.event()} if action_observed else {}
    result = flow.send("observe", accessibility_tree=samsung_screen(value, 1800, scale), **kwargs)
    if action_observed:
        assert result["task_status"] == "WAITING_RESULT"
        technical = result["metadata"]["task_outcome"]["technical"]
        assert technical["status"] == "CHANGED"
        assert technical["baseline_value"] == 4
        assert technical["after_value"] == value
        assert technical["baseline_font_scale"] == 1.3
        assert technical["font_scale"] == scale
    else:
        # A manual change without a correlated event can ask the user about the
        # result, but must not be presented as a technically verified success.
        recovery = result["guide_command"]["render_policy"]["recovery"]
        assert recovery["reason_code"] == "ACTION_EVENT_MISSING"
        result = flow.send("request_result")
        assert result["task_status"] == "WAITING_RESULT"
        assert result["metadata"]["task_outcome"]["technical"]["status"] != "CHANGED"
    assert result["metadata"]["result_prompt"]
    done = flow.answer("satisfied")
    assert done["task_status"] == "COMPLETED"
    assert done["metadata"]["task_outcome"]["final"] == (
        "ACHIEVED" if action_observed else "USER_REPORTED_RESOLVED")
