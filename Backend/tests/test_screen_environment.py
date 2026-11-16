"""Shared foreground semantics, pre-model recovery, and finite client evidence."""
from pathlib import Path
from dataclasses import replace
import json
from unittest.mock import Mock
import pytest
from pydantic import ValidationError
from ai_agent.runtime.environment import classify_environment,policy,recovery_rule
from ai_agent.runtime.types import AccessibilityNode,AgentTurnRequest,DeviceContext
from ai_agent.runtime import ClickAgentEngine
from app.schemas.agent_turn import NativeWindowContext
from tests.test_screen_jev import scene

CASES=json.loads((Path(__file__).resolve().parents[2]/'AI-Agent/tests/fixtures/screen_environment_cases.json').read_text())
def request_for(item,**kw):
    pkg=item['package'];nodes=[]
    if item.get('title') is not None:nodes.append(AccessibilityNode('title',package_name=pkg,window_id=7,text=item['title'],class_name='android.widget.TextView',bounds_in_screen=(20,20,900,80)))
    if item.get('resource'):nodes.append(AccessibilityNode('structure',package_name=pkg,window_id=7,resource_id=item['resource'],class_name='android.view.View',bounds_in_screen=(0,100,1080,2200)))
    if item.get('sensitive') or item.get('empty_search'):
        nodes.append(AccessibilityNode('input',package_name=pkg,window_id=7,text=None if item.get('empty_search') else 'private',resource_id='android:id/search_src_text' if item.get('empty_search') else None,class_name='android.widget.EditText',role='text_input',extras={'editable':True},bounds_in_screen=(20,300,900,400)))
    if item.get('control'):
        nodes.extend((AccessibilityNode('wrapper',package_name=pkg,window_id=7,resource_id='com.android.systemui:id/brightness_dialog_slider',class_name='android.view.View',bounds_in_screen=(0,100,1080,400)),
          AccessibilityNode('slider',parent_id='wrapper',package_name=pkg,window_id=7,resource_id='com.android.systemui:id/slider',class_name='android.widget.SeekBar',role='slider',bounds_in_screen=(20,200,1000,300),extras={'progress':0.4,'min':0,'max':1})))
    return AgentTurnRequest('r','s',raw_goal=None,accessibility_tree=tuple(nodes),device_context=DeviceContext('android',app_package=pkg),active_window_id=7,screen_bounds=(0,0,1080,2400),window_context=item.get('hints',{}),**kw)

@pytest.mark.parametrize('item',[c for c in CASES if c.get('runtime',True)],ids=lambda c:c['name'])
def test_shared_environment_cases(item):
    result=classify_environment(request_for(item))
    assert result.kind==item['kind']
    assert set(result.public())=={'schema_version','kind','reason'}
    assert '개인' not in json.dumps(result.public(),ensure_ascii=False)

@pytest.mark.parametrize('case,reason,action',[
    ('vpn_first_entry','SETTINGS_OFF_PATH','navigate_back'),
    ('vpn_generic_toolbar','SETTINGS_OFF_PATH','navigate_back'),
    ('calendar','OTHER_APP','open_android_settings_or_show_app_drawer'),
    ('launcher_recents','RECENTS_VISIBLE','navigate_home'),
    ('unexpected_dialog','DIALOG_VISIBLE','dismiss_interruption'),
])
def test_environment_recovery_happens_before_jev_and_keeps_goal(case,reason,action):
    graph,_,_,goal=scene('font_size')
    resolver=Mock();resolver.resolve.side_effect=AssertionError('Out-of-route screen must not reach Jev')
    engine=ClickAgentEngine(task_graphs={goal.task_id:graph},screen_resolver=resolver)
    req=request_for(next(c for c in CASES if c['name']==case),typed_goal=goal,captured_at_ms=1000,observation_id='one')
    response=engine.handle_turn(req)
    assert response.guide_command.target_node_id is None
    assert response.guide_command.render_policy['recovery']['reason_code']==reason
    assert response.guide_command.render_policy['recovery_action']['type']==action
    assert engine.session_store.get('s').typed_goal==goal
    resolver.resolve.assert_not_called()


def test_foreign_app_does_not_leave_a_sticky_unknown_state_or_refund_action_budget():
    graph,_,_,goal=scene('font_size');engine=ClickAgentEngine(task_graphs={goal.task_id:graph})
    first=request_for(next(c for c in CASES if c['name']=='calendar'),typed_goal=goal,captured_at_ms=1000,observation_id='one')
    engine.handle_turn(first)
    engine.session_store.update('s',total_failed_attempts=1,transition_attempts={'spent':1})
    title=AccessibilityNode('title',package_name='com.android.settings',window_id=7,text='설정',class_name='android.widget.TextView',bounds_in_screen=(20,20,900,80))
    display=AccessibilityNode('display',package_name='com.android.settings',window_id=7,text='디스플레이',clickable=True,class_name='android.widget.TextView',bounds_in_screen=(20,300,900,400))
    result=engine.handle_turn(replace(first,request_id='two',typed_goal=None,device_context=DeviceContext('android',app_package='com.android.settings'),accessibility_tree=(title,display),captured_at_ms=2000,observation_id='two',window_context={'settings_activity':'HOME'}))
    assert result.guide_command.target_node_id=='display'
    state=engine.session_store.get('s');assert state.total_failed_attempts==1 and state.transition_attempts=={'spent':1}
    assert not state.task_completed

@pytest.mark.parametrize('value',[{'activity_class':'private'},{'settings_activity':'private'},{'keyboard_visible':'private'}])
def test_unreviewed_window_payloads_are_rejected(value):
    with pytest.raises(ValidationError):NativeWindowContext(**value)


def test_failure_policy_is_complete_and_never_loops_automatically():
    categories={rule['category'] for rule in policy()['recovery'].values()}
    assert {'REQUEST','NAVIGATION','INTERRUPTION','OBSERVATION','CONNECTION','TARGET','SELECTION','EXECUTION','VERIFICATION','INFRASTRUCTURE','PRIVACY','ASYNC','VERSION'}<=categories
    for reason,rule in policy()['recovery'].items():
        assert rule['automatic_retries']<=3 and rule['keep_goal'] is True
        if rule['category'] in {'PRIVACY','VERIFICATION','EXECUTION','SELECTION','NAVIGATION'}:assert rule['automatic_retries']==0
    assert recovery_rule('UNKNOWN_FUTURE_FAILURE')=={}


def test_task_related_dialog_is_not_blindly_dismissed():
    from ai_agent.runtime.environment import environment_recovery
    request=request_for(next(c for c in CASES if c['name']=='dialog_over_settings'))
    environment=classify_environment(request)
    assert environment.kind=='DIALOG'
    assert environment_recovery(environment,'font_size') is None


def test_system_permission_is_a_manual_choice_and_never_an_automatic_back_or_allow():
    from ai_agent.runtime.environment import environment_recovery
    environment=classify_environment(request_for(next(c for c in CASES if c['name']=='system_permission_choice')))
    assert environment_recovery(environment,'font_size')==('SYSTEM_DIALOG_VISIBLE','manual_choice')


def test_expected_private_picker_is_a_manual_handoff_only_for_its_goal():
    from ai_agent.runtime.environment import environment_recovery
    request=request_for({'package':'com.samsung.android.app.dressroom','title':'private photo'})
    environment=classify_environment(request)
    assert environment.kind=='OTHER_APP'
    assert environment_recovery(environment,'wallpaper')==('MANUAL_EXTERNAL_CHOICE','manual_choice')
    assert environment_recovery(environment,'font_size')==('OTHER_APP','open_settings')
    calendar=classify_environment(request_for({'package':'com.samsung.android.calendar','title':'private schedule'}))
    assert environment_recovery(calendar,'wallpaper')==('OTHER_APP','open_settings')


def test_registered_native_surface_has_exact_scope_and_never_exports_notification_siblings():
    from ai_agent.runtime.environment import native_control_surface,environment_recovery
    from ai_agent.runtime.screen_privacy import assess_screen,filter_nodes
    request=request_for(next(c for c in CASES if c.get('control')))
    environment=classify_environment(request)
    assert environment_recovery(environment,'brightness') is None
    assert environment_recovery(environment,'font_size')==('SYSTEM_PANEL_VISIBLE','dismiss_interruption')
    nodes=request.accessibility_tree+(AccessibilityNode('private',package_name='com.android.systemui',window_id=7,text='private notification body',bounds_in_screen=(0,500,1080,700)),)
    assert assess_screen(nodes,'brightness','com.android.systemui').status is None
    assert {n.node_id for n in filter_nodes(nodes,'brightness')}=={'wrapper','slider'}
    assert assess_screen(nodes,'font_size','com.android.systemui').status is not None
    assert native_control_surface((replace(nodes[1],parent_id=None),nodes[0])) is None
    assert native_control_surface(nodes+(replace(nodes[1],node_id='second'),)) is None
    assert native_control_surface((nodes[0],replace(nodes[1],extras={'progress':2,'min':0,'max':1}))) is None


def test_unlabeled_registered_brightness_is_a_model_candidate_without_private_notification_export(monkeypatch):
    from ai_agent.runtime.screen_privacy import filter_nodes
    from ai_agent.runtime.normalizer import normalize_accessibility_tree
    from app.services.screen_jev import JevScreenResolver
    from tests.test_screen_jev import sender_for
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    request=request_for(next(c for c in CASES if c.get('control')))
    raw=request.accessibility_tree+(AccessibilityNode('private',package_name='com.android.systemui',window_id=7,text='private notification body',bounds_in_screen=(0,500,1080,700)),)
    graph,transition,_,goal=scene('brightness');sent=[]
    def send(payload,**kwargs):
        sent.append(payload)
        return sender_for(transition)(payload,**kwargs)
    resolver=JevScreenResolver(send)
    selected=resolver.resolve(nodes=normalize_accessibility_tree(filter_nodes(raw,'brightness'),screen_bounds=request.screen_bounds,active_window_id=7),raw_nodes=raw,graph=graph,goal=goal)
    assert selected is not None,resolver.last_diagnostics
    assert selected.target_node_id=='slider'
    assert sent and 'private notification body' not in json.dumps(sent)
