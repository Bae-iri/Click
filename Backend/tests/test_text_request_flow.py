"""The request queue also accepts typed input, without a speech transcription step."""
from uuid import uuid4

from tests.test_task_outcomes import Flow, screen


def test_typed_font_size_request_reaches_guidance_through_queue():
    flow = Flow()
    text = "글자크기 키우고싶어"
    queued = flow.client.post("/api/v1/voice-requests/enqueue",
        json={"id": str(uuid4()), "raw_goal": text, "priority": "NORMAL"})
    assert queued.status_code == 200, queued.text
    claimed = flow.client.post("/api/v1/voice-requests/next/claim")
    assert claimed.status_code == 200, claimed.text
    running = claimed.json()["running"]
    assert running["raw_goal"] == text
    flow.session = running["agent_session_id"]
    selection = flow.resolve(running["raw_goal"])
    assert selection["goal"] is None
    assert selection["preparation"]["question"]["field"] == "scope"
    selection = flow.answer_input("system")
    goal = selection["goal"]
    assert goal["goal_type"] == "settings.font_size.increase"
    started = flow.send("start_task", preparation_version="goal-inputs.v1", goal_resolution_id=selection["preparation"]["id"], goal=goal,
        source_text=text, device_context={"platform": "android", "locale": "ko-KR"},
        accessibility_tree=screen())
    assert started["guide_command"]
    assert started["task_status"] in {"IN_PROGRESS", "WAITING_CONFIRMATION"}
