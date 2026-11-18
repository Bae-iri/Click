"""All approved Settings graphs share the same recoverable application entry."""
from tests.jev_answers import with_screen_judgments
from unittest.mock import Mock
import pytest
from app.task_graphs import approved_graphs
from ai_agent.runtime.types import AccessibilityNode, ActionType
from ai_agent.runtime import ClickAgentEngine
from ai_agent.runtime.types import AgentTurnRequest, TypedGoal, DeviceContext

GRAPHS = list({g.definition.task_id:g for g in approved_graphs().values()
    if g.definition.target_app == 'android_settings'}.values())


@pytest.mark.parametrize('graph', GRAPHS, ids=lambda g:g.definition.task_id)
@pytest.mark.parametrize('actionable', [True, False])
def test_settings_entry_remains_available_when_jev_cannot_select(graph, actionable):
    resolver=Mock(resolve=Mock(return_value=None), last_diagnostics={
        'provider':'jev','mode':'primary','called':True,'status':'low_confidence'})
    engine=ClickAgentEngine(task_graphs={graph.definition.task_id:graph},screen_resolver=resolver)
    node=AccessibilityNode('icon',text='설정',clickable=actionable,
        package_name='com.sec.android.app.launcher',bounds_in_screen=(40,100,200,260))
    goal=TypedGoal(goal_type=graph.definition.goal_type,task_id=graph.definition.task_id,
        target_app='android_settings',task_type=graph.definition.task_type,slots=graph.definition.required_slots,
        source_text='',confidence=1,is_supported=True,required_slots_filled=True)
    result=engine.handle_turn(AgentTurnRequest(request_id='entry',session_id='entry',typed_goal=goal,raw_goal=None,
        device_context=DeviceContext(platform='android'),accessibility_tree=(node,),screen_bounds=(0,0,1080,2400),
        observation_id='entry-screen',captured_at_ms=1000))
    command=result.guide_command
    assert command.action_type == ActionType.OBSERVE_ONLY
    assert command.target_node_id is None
    assert command.target_bounds is None
    action=command.render_policy['recovery_action']
    assert action['android_intent_action']=='android.settings.SETTINGS'
    assert action['requires_user_activation'] is True
    assert command.render_policy['recovery']['reason_code']=='APP_NOT_OPEN'
    assert command.command_type.value != 'done'


def test_entry_recovery_does_not_override_jev_abstention_inside_settings():
    from tests.test_wifi_execution import wifi
    from tests.test_task_outcomes import Flow
    from app.services.screen_jev import JevScreenResolver
    from pytest import MonkeyPatch
    with MonkeyPatch.context() as patch:
        patch.setenv('AI1_UI_SELECTOR_PROVIDER','jev');patch.setenv('TYPESAFE_API_KEY','test-only')
        def abstain(self,**kwargs):
            self.last_diagnostics={'status':'low_confidence','provider':'jev','called':True}
            return None
        patch.setattr(JevScreenResolver,'resolve',abstain)
        flow=Flow();prepared=flow.resolve('와이파이 켜줘')
        body=flow.send('start_task',preparation_version='goal-inputs.v1',goal_resolution_id=prepared['preparation']['id'],
            source_text=flow.source_text,goal=prepared['goal'],device_context={'platform':'android','locale':'ko-KR'},
            accessibility_tree=wifi(False))
        assert 'recovery_action' not in body['guide_command']['render_policy']
        assert body['guide_command']['action_type']=='observe_only'


def test_opening_settings_retires_entry_wait_and_keeps_the_wifi_goal(monkeypatch):
    import httpx
    from app.services.screen_jev import JevScreenResolver
    from tests.test_task_outcomes import Flow
    from tests.test_visible_settings_navigation import screen
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev')
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only')
    def decide(self,payload,**kwargs):
        criteria=payload['questions']['screen']['criteria']
        choice=next((k for k,c in criteria.items() if isinstance(c,dict) and c['transition']=='OPEN_CATEGORY'),
            'insufficient_evidence')
        return httpx.Response(200,json=with_screen_judgments(payload, {'model':payload['model'],'answers':{'screen':{
            'type':'choice','choice':choice,'confidence':1,
            'probabilities':{k:float(k==choice) for k in criteria}}}}))
    monkeypatch.setattr(JevScreenResolver,'_post',decide)
    flow=Flow();prepared=flow.resolve('와이파이 켜줘')
    launcher={'package_name':'com.sec.android.app.launcher','screen_width':1080,'screen_height':2400,
        'observation_id':'launcher','captured_at_ms':1000,'nodes':[{'node_id':'settings','text':'설정',
        'package_name':'com.sec.android.app.launcher','clickable':True,'bounds_in_screen':[40,100,200,260]}]}
    entry=flow.send('start_task',preparation_version='goal-inputs.v1',goal_resolution_id=prepared['preparation']['id'],
        source_text=flow.source_text,goal=prepared['goal'],device_context={'platform':'android','locale':'ko-KR'},
        accessibility_tree=launcher)
    assert entry['guide_command']['render_policy']['recovery']['next_action']=='open_settings'
    home=screen('설정',[('connections','연결','Wi-Fi, 블루투스')])
    home.update(observation_id='opened-settings',captured_at_ms=2000)
    continued=flow.send('observe',accessibility_tree=home,user_action_event={
        'event_id':'open-settings','action_type':'manual_override','occurred_at_ms':1500})
    assert continued['episode_id']==entry['episode_id']
    assert continued['accepted_goal_type']=='settings.wifi.enable'
    assert continued['guide_command']['target_node_id']=='connections'
    assert continued['guide_command']['action_type']=='tap'
    assert continued['task_status']=='IN_PROGRESS'
