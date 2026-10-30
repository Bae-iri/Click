import json
import time
from types import SimpleNamespace
import httpx
import pytest
from app.services.guide_narration import GuideNarrator
from app.schemas.agent_turn import AgentTurnResponse

@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv("SELF_HOSTED_LLM_URL", "http://localhost:8001/v1/chat/completions")
    monkeypatch.setenv("SELF_HOSTED_LLM_TOKEN", "test")
    monkeypatch.setenv("SELF_HOSTED_LLM_MODEL", "test")
    monkeypatch.setenv("GUIDE_NARRATION_ENABLED", "true")

def reply(value, **extra):
    return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message":
        {"content": json.dumps(value, ensure_ascii=False), **extra}}]})

def response(**guide_updates):
    guide = dict(guide_command_id="g", command_type="highlight", action_type="tap", message="디스플레이를 눌러주세요.",
                 target_label="디스플레이", target_node_id="n", target_bounds=[0,0,10,10],
                 safety_level="LOW_RISK", requires_user_action=True, render_policy={"step_id":"S1_TO_S2_DISPLAY_MENU"})
    guide.update(guide_updates)
    return AgentTurnResponse(request_id="r", session_id="s", task_id="MVP-01", step=1,total_steps=5,
        state_id="S1_SETTINGS_HOME",state_confidence=1,task_status="IN_PROGRESS",guide_command=guide)

def test_only_expression_changes_and_private_projection():
    sent=[]
    def send(payload, **kwargs):
        sent.append(payload)
        return reply({"opening":"이제 다음 단계로 가볼게요.","instruction":"화면에서 디스플레이 항목을 눌러주세요."})
    original=response()
    decorated=GuideNarrator(send).decorate(original)
    assert decorated.guide_command.message.startswith("이제 다음 단계")
    assert original.guide_command.message == "디스플레이를 눌러주세요."
    before=original.model_dump();after=decorated.model_dump()
    after['guide_command']['message']=before['guide_command']['message']
    assert before==after
    data=json.loads(sent[0]['messages'][1]['content'])
    assert set(data)=={'target','action','direction','openings','instructions'}
    assert 'session_id' not in json.dumps(sent)

@pytest.mark.parametrize('value', [
    {"opening":"완료했어요.","instruction":"화면에서 디스플레이 항목을 눌러주세요."},
    {"opening":"","instruction":"설정을 눌러주세요."},
    {"opening":"","instruction":"디스플레이를 결제해주세요."},
    {"opening":"","instruction":"화면에서 디스플레이 항목을 눌러주세요.","action":"tap"},
    {}, [],
])
def test_invalid_model_text_keeps_original(value):
    original=response()
    assert GuideNarrator(lambda *a,**kw: reply(value)).decorate(original) is original

def test_wrong_direction_rejected():
    narrator=GuideNarrator(lambda *a,**kw: reply({"opening":"","instruction":"글자 크기 조절 막대를 왼쪽으로 한 단계 움직여주세요."}))
    assert narrator.generate("글자 크기","drag","right") is None

@pytest.mark.parametrize('updates', [
    {'command_type':'confirmation'}, {'safety_level':'BLOCKED'},
    {'render_policy':{'vision_derived':True}}, {'target_label':'홍길동 계좌'},
    {'action_type':'scroll'}, {'target_bounds':None},
])
def test_sensitive_or_non_action_steps_never_call_model(updates):
    def fail(*a,**kw): pytest.fail('must not call model')
    original=response(**updates)
    assert GuideNarrator(fail).decorate(original) is original

def test_terminal_and_personalized_responses_kept():
    def fail(*a,**kw): pytest.fail('must not call model')
    for state in ['COMPLETED','BLOCKED','WAITING_RESULT','WAITING_CONFIRMATION']:
        original=response();original.task_status=state
        assert GuideNarrator(fail).decorate(original) is original
    original=response();original.metadata['ui_profile']={'explanation_depth':'detailed'}
    assert GuideNarrator(fail).decorate(original) is original

def test_disabled_or_missing_config_falls_back(monkeypatch):
    original=response()
    monkeypatch.setenv('GUIDE_NARRATION_ENABLED','false')
    assert GuideNarrator().decorate(original) is original
    monkeypatch.setenv('GUIDE_NARRATION_ENABLED','true');monkeypatch.delenv('SELF_HOSTED_LLM_MODEL')
    assert GuideNarrator().decorate(original) is original

def test_slow_generation_has_hard_deadline():
    def slow(*a,**kw): time.sleep(.15);return reply({})
    start=time.monotonic();original=response()
    assert GuideNarrator(slow,timeout=.02).decorate(original) is original
    assert time.monotonic()-start < .12

def test_font_goal_context_allows_goal_specific_opening():
    narrator=GuideNarrator(lambda *a,**kw: reply({"opening":"글자 크기를 바꾸러 가볼게요.",
        "instruction":"화면에서 디스플레이 항목을 눌러주세요."}))
    assert narrator.decorate(response()).guide_command.message.startswith('글자 크기를 바꾸러')
    other=response();other.task_id='OTHER'
    assert narrator.decorate(other) is other

def test_model_http_failure_or_exception_preserves_message():
    for sender in [lambda *a,**kw:httpx.Response(503), lambda *a,**kw:reply({},tool_calls=[{}])]:
        original=response()
        assert GuideNarrator(sender).decorate(original) is original

def test_duplicate_json_keys_and_unknown_policy_fall_back():
    raw='{"opening":"완료","opening":"","instruction":"화면에서 디스플레이 항목을 눌러주세요."}'
    model=httpx.Response(200,json={'choices':[{'finish_reason':'stop','message':{'content':raw}}]})
    original=response()
    assert GuideNarrator(lambda *a,**kw:model).decorate(original) is original
    original=response(render_policy={'await_user':True})
    assert GuideNarrator(lambda *a,**kw:pytest.fail('must not call')).decorate(original) is original

def test_real_turn_and_cached_retry_use_presentation_layer(monkeypatch):
    from tests.helpers import authenticated_client
    from tests.test_visible_settings_navigation import screen
    from app.main import create_app
    calls=[]
    def send(payload,**kwargs):
        calls.append(json.loads(payload['messages'][1]['content']))
        return reply({'opening':'글자 크기를 바꾸러 가볼게요.',
                      'instruction':'화면에서 디스플레이 항목을 눌러주세요.'})
    from app.services.goal_llm import LocalGoalLlm
    monkeypatch.setattr(LocalGoalLlm,'configuration',staticmethod(lambda:(SimpleNamespace(post=send),'test')))
    snapshot=screen('설정', [('display','디스플레이','디스플레이'),('accessibility','접근성','접근성')])
    client=authenticated_client(create_app())
    payload={'request_id':'narration-request','session_id':'narration-session',
             'raw_goal':'increase font size','accessibility_tree':snapshot}
    first=client.post('/api/v1/agent/turn',json=payload)
    assert first.status_code==200
    assert calls, json.dumps(first.json(),ensure_ascii=False)
    assert first.json()['guide_command']['message'].startswith('글자 크기를 바꾸러')
    again=client.post('/api/v1/agent/turn',json=payload)
    assert again.status_code==200
    assert again.json()==first.json()


def test_added_warning_and_changed_source_are_never_discarded():
    original=response(message="디스플레이를 눌러주세요. 변경 전 내용을 확인해 주세요.")
    def fail(*a,**kw): pytest.fail('must preserve original warning')
    assert GuideNarrator(fail).decorate(original) is original

def test_approved_slider_instruction_keeps_original_direction():
    from ai_agent.runtime.tasks import TASK_GRAPHS
    transition=next(t for t in TASK_GRAPHS['MVP-01'].transitions if t.action_type.value=='drag')
    original=response(action_type='drag',target_label=None,safety_level='CONFIRM_REQUIRED',
        message=transition.guide_message,render_policy={'step_id':transition.transition_id,
        'direction':'right','automatable_setting':'font_size'})
    original.state_id=transition.from_state
    narrator=GuideNarrator(lambda *a,**kw:reply({'opening':'',
        'instruction':'글자 크기 조절 막대를 오른쪽으로 한 단계 움직여주세요.'}))
    decorated=narrator.decorate(original)
    assert decorated is not original
    assert '오른쪽' in decorated.guide_command.message
    assert decorated.guide_command.safety_level=='CONFIRM_REQUIRED'
