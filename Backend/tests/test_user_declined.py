"""The final slider No is a user choice, never a failed or successful action."""
import pytest
from app.main import create_app
from app.models.agent_session import AgentCheckpoint
from app.models.lifecycle import SafetyConfirmation
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload
from tests.test_interactive_workflow import command, waiting_result
from tests.test_voice_queue import enqueue, claim, BASE

@pytest.mark.parametrize("setting,label", [("brightness","밝기"),("font_size","글자 크기"),("volume","음량")])
@pytest.mark.parametrize("direction", ["increase","decrease"])
def test_slider_decline_clears_approval_and_persists_normal_terminal_outcome(setting,label,direction):
    app=create_app(); client=authenticated_client(app)
    start=start_payload()
    start["goal"].update(catalog_version="2.1.0", goal_type=f"settings.{setting}.{direction}",
        slots={"setting":setting,"direction":direction})
    title={"brightness":"밝기","font_size":"글자 크기","volume":"미디어 음량"}[setting]
    start["accessibility_tree"]["nodes"]=[
        {"node_id":"title","text":title,"bounds_in_screen":[20,20,900,90]},
        {"node_id":"slider","text":title,"class_name":"android.widget.SeekBar",
         "bounds_in_screen":[80,200,900,260],"range_info":{"min":0,"max":10,"current":5}}]
    response=client.post("/api/v1/agent/commands",json=start)
    assert response.status_code==200,response.text
    before=response.json()
    assert before["task_status"]=="WAITING_CONFIRMATION",before
    request=command(before,"respond_confirmation",user_confirmation="rejected",
        confirmation_id=before["metadata"]["confirmation"]["id"])
    response=client.post("/api/v1/agent/commands",json=request)
    assert response.status_code==200,response.text
    body=response.json(); guide=body["guide_command"]
    assert body["task_status"]=="CANCELLED"
    assert guide["command_type"]=="cancelled" and guide["action_type"]=="observe_only"
    assert not guide["requires_user_action"] and guide["proposed_action"] is None
    assert guide["target_node_id"] is None and guide["target_bounds"] is None
    expected={"brightness":"밝기를", "font_size":"글자 크기를", "volume":"음량을"}[setting]
    assert guide["message"]==expected+" 바꾸지 않고 안내를 마칠게요."
    termination=body["metadata"]["termination"]
    assert termination["category"]=="USER_CHOICE" and termination["counts_as_failure"] is False
    assert body["metadata"]["task_outcome"]["final"]=="USER_DECLINED"
    assert client.post("/api/v1/agent/commands",json=request).json()==body
    snapshot=client.get("/api/v1/agent/sessions/"+before["session_id"]).json()
    assert snapshot["status"]=="CANCELLED" and snapshot["task_outcome"]["termination"]==termination
    with app.state.session_factory() as db:
        assert db.get(AgentCheckpoint,before["session_id"]) is None
        assert db.get(SafetyConfirmation,before["session_id"]) is None
    stale_accept={**request,"request_id":"late-approval","user_confirmation":"accepted","expected_version":body["session_version"],"accessibility_tree":start["accessibility_tree"]}
    assert client.post("/api/v1/agent/commands",json=stale_accept).status_code==409

@pytest.mark.parametrize("endpoint,field",[("cancel","reason"),("fail","failure_code")])
@pytest.mark.parametrize("reason",["USER_DECLINED","USER_ABORTED"])
def test_user_choice_is_cancelled_not_failed_and_preserves_the_next_task(reason,endpoint,field):
    client=authenticated_client(create_app()); enqueue(client)
    second=enqueue(client,"밝기를 높여줘").json()["request"]
    running=claim(client).json()["running"]
    path=BASE+"/"+running["id"]+"/"+endpoint
    response=client.post(path,json={field:reason})
    assert response.status_code==200,response.text
    body=response.json()
    assert body["request"]["status"]=="CANCELLED"
    assert body["request"]["failure_code"] is None
    assert body["request"]["termination_reason"]==reason
    assert body["request"]["counts_as_failure"] is False
    assert body["running"]["id"]==second["id"]
    repeated=client.post(path,json={field:reason}).json()
    assert repeated["queue_version"]==body["queue_version"]
    assert repeated["request"]==body["request"] and repeated["running"]==body["running"]
    assert repeated["claimed"] is False


def test_no_at_result_question_means_more_help_not_refusal():
    client=authenticated_client(create_app()); previous=waiting_result(client)
    request=command(previous,"respond_result",user_result="needs_help",result_prompt_id=previous["metadata"]["result_prompt"]["id"])
    response=client.post("/api/v1/agent/commands",json=request)
    assert response.status_code==200,response.text
    assert response.json()["task_status"]=="WAITING_ADJUSTMENT"
    assert response.json()["metadata"].get("termination") is None


@pytest.mark.process_runtime
def test_decline_survives_the_production_process_boundary_without_a_screen():
    test_slider_decline_clears_approval_and_persists_normal_terminal_outcome("brightness","밝기","increase")
