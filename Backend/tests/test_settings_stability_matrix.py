"""All six approved tasks start at Settings; provider choices are controlled test responses."""
from dataclasses import replace
import pytest
from ai_agent.runtime import ClickAgentEngine, UserActionEvent, UserActionType
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, CommandType, DeviceContext
from app.clients.checkpoint import capture, restore
from app.services.screen_jev import JevScreenResolver
from tests.test_screen_jev import scene, sender_for

GOALS = [(s,d) for s in ('font_size','brightness','volume') for d in ('increase','decrease')]
PACKAGE = 'com.android.settings'


def node(identifier, text, *, slider=False, value=3, resource=None):
    return AccessibilityNode(identifier, package_name=PACKAGE, window_id=7, text=text,
        class_name='android.widget.SeekBar' if slider else 'android.widget.TextView',
        clickable=not slider and identifier != 'title', resource_id=resource,
        bounds_in_screen=(20,300,900,370) if slider else (20,20,900,90) if identifier=='title' else (20,130,900,220),
        extras={'progress':value,'min':0,'max':7} if slider else {})


class Run:
    def __init__(self, setting, direction):
        self.setting, self.direction = setting, direction
        graph, _, _, goal=scene(setting)
        if direction=='decrease':
            from app.task_graphs import approved_graphs
            graph=approved_graphs()[f'settings.{setting}.decrease']
            goal=replace(goal,goal_type=graph.definition.goal_type,task_id=graph.definition.task_id,
                         slots=graph.definition.required_slots)
        self.graph,self.goal=graph,goal
        self.edge=None; self.calls=[]; self.at=0
        def send(payload,**kw):
            self.calls.append(payload)
            return sender_for(self.edge)(payload,**kw)
        self.resolver=JevScreenResolver(send)
        self.engine=ClickAgentEngine(task_graphs={goal.task_id:graph},screen_resolver=self.resolver)
        self.home_edge=next(t for t in graph.transitions if t.from_state=='S1_SETTINGS_HOME')
        self.home=(node('title','설정'),node('menu',self.home_edge.target_keywords[0]))
        self.applied={'source':'android.provider.Settings.System.FONT_SCALE','font_scale':1.1,
            'brightness_source':'android.provider.Settings.System.SCREEN_BRIGHTNESS','screen_brightness':100,
            'media_volume_source':'android.media.AudioManager.STREAM_MUSIC','media_volume':4}
    def observe(self, nodes, *, event=None, confirmation=None, applied=None, stale=False):
        if not stale:self.at+=1000
        return self.engine.handle_turn(AgentTurnRequest(request_id=f'r{self.at}-{len(self.calls)}',session_id='s',
            episode_id='e',observation_id=f'o{self.at}',captured_at_ms=self.at,raw_goal=None,
            typed_goal=self.goal if not self.engine.session_store.get('s') else None,
            device_context=DeviceContext('android'),screen_bounds=(0,0,1080,2400),active_window_id=nodes[0].window_id,
            accessibility_tree=nodes,applied_settings=applied or self.applied,
            user_action_event=event,user_confirmation=confirmation))
    def route(self):
        self.edge=self.home_edge
        first=self.observe(self.home)
        assert first.guide_command.action_type.value=='tap'
        assert not first.debug['task_completed']
        if self.setting in {'font_size','volume'}:
            state=self.home_edge.to_state
            self.edge=next(t for t in self.graph.transitions if t.from_state==state)
            category=(node('title',self.graph.states[state].required_keywords[0]
                if self.graph.states[state].required_keywords else self.graph.states[state].required_keyword_groups[0][0]),
                node('submenu',self.edge.target_keywords[0]))
            middle=self.observe(category)
            assert middle.guide_command.action_type.value=='tap'
            assert not middle.debug['task_completed']
        self.edge=next(t for t in self.graph.transitions if t.target_role=='slider')
        label={'font_size':'글자 크기','brightness':'밝기','volume':'미디어'}[self.setting]
        self.control=(node('title',label),node('target',label,slider=True,resource='com.android.settings:id/seekBarForFontSize' if self.setting=='font_size' else None))
        pending=self.observe(self.control)
        assert pending.guide_command.command_type==CommandType.CONFIRMATION
        assert not pending.debug['task_completed']
        return self.observe(self.control,confirmation='accepted')


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')


@pytest.mark.parametrize('setting,direction',GOALS)
@pytest.mark.parametrize('case',['complete','no_action','wrong_slider','back','recreated_window','stale_response','restart'])
def test_settings_home_to_result_matrix(setting,direction,case):
    run=Run(setting,direction); approved=run.route()
    assert approved.guide_command.action_type.value=='drag'
    if case=='no_action':
        for _ in range(6):
            result=run.observe(run.control)
            assert not result.debug['task_completed']
            assert result.guide_command.command_type!=CommandType.DONE
        assert run.engine.session_store.get('s').total_failed_attempts==0
        return
    if case=='back':
        run.edge=run.home_edge
        result=run.observe(run.home,event=UserActionEvent(UserActionType.BACK_NAVIGATED,
            occurred_at_ms=run.at+500,session_id='s',episode_id='e'))
        assert not result.debug['task_completed']
        assert result.guide_command.target_node_id=='menu'
        return
    if case=='restart':
        saved=capture(run.engine.session_store,'s',run.graph)
        run.engine=ClickAgentEngine(session_store=restore(saved),task_graphs={run.goal.task_id:run.graph},screen_resolver=run.resolver)
        resumed=run.observe(run.control)
        assert not resumed.debug['task_completed']
        assert resumed.guide_command.target_node_id=='target'
        approved=resumed
    sign=1 if direction=='increase' else -1
    changed=(run.control[0],replace(run.control[1],extras={'progress':3+sign,'min':0,'max':7}))
    applied={**run.applied,{'font_size':'font_scale','brightness':'screen_brightness','volume':'media_volume'}[setting]:
        {'font_size':1.1+.2*sign,'brightness':100+10*sign,'volume':4+sign}[setting]}
    if case=='wrong_slider':
        changed=(*run.control,node('unrelated','벨소리' if setting=='volume' else '다른 설정',slider=True,value=3+sign))
    if case=='recreated_window':changed=tuple(replace(n,window_id=8) for n in changed)
    event=UserActionEvent(UserActionType.DRAG_COMPLETED,approved.guide_command.guide_command_id,
        'unrelated' if case=='wrong_slider' else 'target',occurred_at_ms=run.at+500,event_id='action',episode_id='e',session_id='s')
    result=run.observe(changed,event=event,applied=applied,stale=case=='stale_response')
    should_complete=case in {'complete','restart'} or case=='recreated_window' and setting=='font_size'
    assert bool(run.engine.session_store.get('s').task_completed) == should_complete
    if case=='stale_response':
        assert result.guide_command.render_policy['recovery']['reason_code']=='OBSERVATION_STALE'


@pytest.mark.parametrize('setting,direction',GOALS)
def test_layout_churn_does_not_refund_recovery_budget_or_unlock_a_paused_task(setting,direction):
    import httpx
    run=Run(setting,direction);run.route()
    def hold(payload,**kw):
        run.calls.append(payload)
        criteria=payload['questions']['screen']['criteria']
        return httpx.Response(200,json={'model':payload['model'],'answers':{'screen':{
            'type':'choice','choice':'insufficient_evidence','confidence':1,
            'probabilities':{k:float(k=='insufficient_evidence') for k in criteria}}}})
    run.resolver._sender=hold
    retry=UserActionEvent(UserActionType.MANUAL_OVERRIDE,occurred_at_ms=run.at+500,session_id='s',episode_id='e')
    for i in range(run.graph.policy.recovery_limit):
        # A shifted native rectangle used to reset the fingerprint-scoped budget.
        shifted=(run.control[0],replace(run.control[1],bounds_in_screen=(20,300+i,900,370+i)))
        result=run.observe(shifted,event=retry if i==0 else None)
    assert result.guide_command.render_policy['recovery']['waiting_for']=='user'
    calls=len(run.calls)
    result=run.observe((run.control[0],replace(run.control[1],bounds_in_screen=(20,350,900,420))))
    assert len(run.calls)==calls
    assert result.debug['ui_selection']['paused']


@pytest.mark.parametrize('setting,direction',GOALS)
def test_each_task_pauses_resumes_from_persisted_state_and_cannot_restart_after_cancel(setting,direction,monkeypatch):
    from uuid import uuid4
    from app.main import create_app
    from tests.helpers import authenticated_client
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev')
    run=Run(setting,direction)
    monkeypatch.setattr(JevScreenResolver,'_post',lambda self,payload,**kw:sender_for(run.home_edge)(payload,**kw))
    client=authenticated_client(create_app())
    session=str(uuid4());version=0;episode=None
    tree={'package_name':PACKAGE,'screen_width':1080,'screen_height':2400,'active_window_id':7,
          'nodes':[n.to_dict() for n in run.home],'captured_at_ms':1000,'observation_id':'start'}
    def command(kind,expected=200,**kw):
        nonlocal version,episode
        body={'contract_version':'agent-fe-contract.v0.3','session_id':session,'request_id':str(uuid4()),
              'expected_version':version,'command_type':kind,**kw}
        if episode:body['episode_id']=episode
        response=client.post('/api/v1/agent/commands',json=body)
        assert response.status_code==expected,response.text
        data=response.json()
        if expected==200:version=data['session_version'];episode=data['episode_id']
        return data
    started=command('start_task',goal={'catalog_version':'2.1.0','goal_type':run.goal.goal_type,
        'target_app':'android_settings','slots':run.graph.definition.required_slots},device_context={'platform':'android','locale':'ko-KR'},accessibility_tree=tree)
    assert started['guide_command']['action_type']=='tap'
    paused=command('pause_task');assert paused['task_status']=='PAUSED'
    command('observe',expected=409,accessibility_tree={**tree,'captured_at_ms':2000,'observation_id':'paused'})
    resumed=command('resume_task',accessibility_tree={**tree,'captured_at_ms':3000,'observation_id':'resumed'})
    assert resumed['task_status'] in {'IN_PROGRESS','WAITING_CONFIRMATION'}
    assert resumed['task_status']!='COMPLETED'
    fresh=command('observe',accessibility_tree={**tree,'captured_at_ms':4000,'observation_id':'after-resume'})
    assert fresh['guide_command']['target_node_id']=='menu'
    stopped=command('cancel_task');assert stopped['task_status']=='CANCELLED'
    command('observe',expected=409,accessibility_tree={**tree,'captured_at_ms':5000,'observation_id':'cancelled'})
