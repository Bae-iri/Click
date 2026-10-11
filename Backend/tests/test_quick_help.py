"""A help-card choice starts the matching approved graph without resolve_goal."""
from app.services.goal_resolution import font_goal
from tests.test_task_outcomes import Flow, screen


def test_font_size_help_card_starts_increase_graph_without_goal_selection():
    flow = Flow()
    response = flow.send("start_task", source_text="글자 크기를 크게 해줘",
        goal=font_goal("increase").model_dump(),
        device_context={"platform": "android", "locale": "ko-KR"},
        accessibility_tree=screen())
    assert response["accepted_goal_type"] == "settings.font_size.increase"
    assert response["task_id"] == "MVP-01"
    assert response["graph_reference"]["task_id"] == "MVP-01"
    assert response["task_status"] in {"IN_PROGRESS", "WAITING_CONFIRMATION"}
    assert response["response_type"] == "guidance"
    assert "goal_selection" not in response["metadata"]
