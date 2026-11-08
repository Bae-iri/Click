"""All 89 goal contracts, real HTTP flows and hostile native evidence."""
import json
from copy import deepcopy
from pathlib import Path
import pytest
from ai_agent.task_graph.settings import DOCUMENT
from app.task_graphs import releases
from tests.test_task_outcomes import Flow
from tests.test_visible_settings_navigation import screen

GOALS = DOCUMENT['goals']
NATIVE = [k for k,v in GOALS.items() if v['mode']=='native_checked']
ASSISTED = [k for k,v in GOALS.items() if v['mode']=='assisted']
CATALOG = {g['goal_type']:g for g in json.loads((Path(__file__).parents[1]/'app/task_graphs/goal_catalog.json').read_text())['goals']}


def tree(goal, checked=False, at=1000):
    rec=GOALS[goal]; c=DOCUMENT['toggles'][rec['setting']]
    result=screen(c['pages'][0],[])
    result['nodes'].append(dict(node_id='master',package_name='com.android.settings',window_id=42,
        class_name='android.widget.Switch',role='switch',text=c['labels'][0],resource_id='com.android.settings:id/switch_widget',
        clickable=True,checkable=True,enabled=True,bounds_in_screen=[40,260,1000,380],extras={'checked':checked}))
    for n in result['nodes']:n['window_id']=42
    result.update(active_window_id=42,observation_id=f'o-{at}',captured_at_ms=at)
    return result


def prepare(goal):
    f=Flow(); g=CATALOG[goal]; result=f.resolve(g.get('utterances', [' '.join(group[0] for group in g['keyword_groups'])])[0])
    while result['preparation']['question']:
        question=result['preparation']['question']; field=question['field']
        assert field in {'scope','audio_stream','direction','state'},(goal,result)
        value={'scope':'system','audio_stream':'media','direction':g['slots'].get('direction'), 'state':g['slots'].get('state')}[field]
        result=f.answer_input(value)
    assert result['goal'] and result['goal']['goal_type']==goal,(goal,result)
    assert result['preparation']['status']=='ready'
    return f,result


def start(goal, observation):
    f,ready=prepare(goal)
    return f,f.send('start_task',preparation_version='goal-inputs.v1',goal_resolution_id=ready['preparation']['id'],
        source_text=f.source_text,goal=ready['goal'],device_context={'platform':'android','locale':'ko-KR'},accessibility_tree=observation)


def approve(f,goal):
    return f.send('respond_confirmation',user_confirmation='accepted',confirmation_id=f.body['metadata']['confirmation']['id'],
        accessibility_tree=tree(goal,not goal.endswith('enable'),1100))


@pytest.mark.parametrize('goal',list(GOALS))
def test_every_original_settings_goal_can_be_prepared(goal):
    prepare(goal)


@pytest.mark.parametrize('goal',NATIVE)
def test_each_switch_confirms_and_verifies_the_requested_native_state(goal):
    expected=goal.endswith('enable')
    f,first=start(goal,tree(goal,not expected))
    assert first['task_status']=='WAITING_CONFIRMATION',first
    assert first['guide_command']['target_node_id']=='master'
    approve(f,goal)
    result=f.send('observe',accessibility_tree=tree(goal,expected,1800),user_action_event=f.event(action_type='tap_completed'))
    proof=result['metadata']['task_outcome']['technical']
    assert result['task_status']=='WAITING_RESULT',result
    assert proof['status']=='CHANGED'
    assert proof['baseline_checked'] is not expected and proof['after_checked'] is expected
    assert proof['requested_state']==('on' if expected else 'off')
    assert '동영상' not in result['metadata']['result_prompt']['message']
    assert f.answer('satisfied')['metadata']['task_outcome']['final']=='ACHIEVED'


@pytest.mark.parametrize('goal',NATIVE)
def test_each_switch_already_set_never_suggests_a_reverse_tap(goal):
    _,body=start(goal,tree(goal,goal.endswith('enable')))
    assert body['task_status']=='WAITING_RESULT',body
    assert body['metadata']['task_outcome']['technical']['status']=='ALREADY_SET'
    assert body['guide_command']['action_type']=='observe_only'


@pytest.mark.parametrize('goal',ASSISTED)
def test_assisted_tasks_offer_manual_steps_without_claiming_technical_completion(goal):
    rec=GOALS[goal]; f,body=start(goal,screen(rec['pages'][0],[]))
    assert body['task_status']=='IN_PROGRESS',body
    guide=body['guide_command'];assert guide['action_type']=='observe_only'
    assert guide['render_policy']['manual_handoff'] is True
    assert guide['render_policy']['allow_result_check'] is True
    assert guide['target_node_id'] is None
    result=f.send('request_result')
    assert result['metadata']['task_outcome']['technical']['status']=='UNVERIFIABLE'
    result=f.answer('satisfied')
    assert result['metadata']['task_outcome']['final']=='USER_REPORTED_RESOLVED'


@pytest.mark.parametrize('problem',['selected_only','wrong_page','different_control','vision','duplicate','missing_checked'])
def test_ambiguous_or_non_native_switch_cannot_authorize_change(problem):
    goal='settings.bluetooth.enable'; t=tree(goal)
    n=t['nodes'][-1]
    if problem=='selected_only':n['extras']={};n['selected']=False
    if problem=='missing_checked':n['extras']={}
    if problem=='wrong_page':t['nodes'][1]['text']='알림'
    if problem=='different_control':n['text']='모바일 데이터'
    if problem=='vision':n['node_id']='vision:master';n['extras']['confidence']=.99
    if problem=='duplicate':
        other=deepcopy(n);other.update(node_id='other',bounds_in_screen=[40,500,1000,620]);t['nodes'].append(other)
    _,result=start(goal,t)
    assert result['task_status'] not in {'WAITING_CONFIRMATION','WAITING_RESULT','COMPLETED'},result
    assert result['guide_command']['action_type']=='observe_only'


@pytest.mark.parametrize('problem',['wrong_event','different_window','different_node','stale','not_changed','missing_checked'])
def test_changed_state_requires_the_original_target_and_fresh_correlated_tap(problem):
    goal='settings.location.enable';f,_=start(goal,tree(goal));approve(f,goal)
    t=tree(goal,True,1800);event=f.event(action_type='tap_completed')
    if problem=='wrong_event':event['target_node_id']='other'
    if problem=='different_window':
        t['active_window_id']=55
        for n in t['nodes']:n['window_id']=55
    if problem=='different_node':t['nodes'][-1]['node_id']='other'
    if problem=='stale':t['captured_at_ms']=1100
    if problem=='not_changed':t['nodes'][-1]['extras']['checked']=False
    if problem=='missing_checked':t['nodes'][-1]['extras']={}
    body=f.send('observe',accessibility_tree=t,user_action_event=event)
    assert body['metadata']['task_outcome']['technical']['status']!='CHANGED',body


def test_assisted_graphs_never_target_destructive_or_personal_controls():
    graphs=[g for _,g in releases() if g.definition.task_type=='settings_assisted']
    assert len(graphs)==39
    for g in graphs:
        assert not any(t.target_role in {'toggle','slider','radio'} for t in g.transitions)
        assert all(t.action_type.value in {'tap','observe_only'} for t in g.transitions)
        assert all(not t.completes_task or t.verification_predicate=='manual_result_required' for t in g.transitions)
        if g.definition.required_slots.get('setting')=='factory_reset':
            assert not any('모든 데이터 삭제' in t.target_keywords or 'erase all data' in [w.lower() for w in t.target_keywords] for t in g.transitions)


def test_manual_handoff_does_not_ask_a_model_to_read_the_following_personal_choices(monkeypatch):
    from app.services.screen_jev import JevScreenResolver
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev')
    monkeypatch.setenv('TYPESAFE_API_KEY','synthetic-test-key')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    def forbidden(*args,**kwargs):raise AssertionError('Native handoff must not call a model')
    monkeypatch.setattr(JevScreenResolver,'resolve',forbidden)
    _,result=start('settings.app_data.clear',screen('앱',[]))
    assert result['guide_command']['render_policy']['manual_handoff'] is True
