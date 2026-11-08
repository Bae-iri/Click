"""Real HTTP/runtime Wi-Fi master-switch state verification with synthetic trees."""
import pytest
from tests.test_task_outcomes import Flow
from tests.test_visible_settings_navigation import screen


def wifi(checked=False,state=None,at=1000,label='Wi-Fi',extra=False,title='Wi-Fi'):
    tree=screen(title,[])
    tree['nodes'].append(dict(node_id='wifi-master',package_name='com.android.settings',window_id=42,
        class_name='android.widget.Switch',role='switch',text=label,
        resource_id='com.android.settings:id/switch_widget',clickable=True,enabled=True,checkable=True,
        bounds_in_screen=[40,250,1000,380],extras={} if checked is None else {'checked':checked}))
    if extra:tree['nodes'].append(dict(node_id='mobile',package_name='com.android.settings',window_id=42,
        class_name='android.widget.Switch',role='switch',text='모바일 데이터',clickable=True,enabled=True,
        checkable=True,bounds_in_screen=[40,450,1000,580],extras={'checked':False}))
    for node in tree['nodes']:node['window_id']=42
    tree.update(active_window_id=42,observation_id=f'wifi-{at}',captured_at_ms=at,
        applied_settings={'wifi_source':'android.net.wifi.WifiManager.getWifiState',
            'wifi_state':(3 if checked else 1) if state is None else state})
    return tree


def start(action='enable',initial=None):
    flow=Flow();text='와이파이 '+('켜줘' if action=='enable' else '꺼줘')
    ready=flow.resolve(text)
    assert ready['preparation']['status']=='ready',ready
    assert ready['goal']['goal_type']=='settings.wifi.'+action
    first=flow.send('start_task',preparation_version='goal-inputs.v1',goal_resolution_id=ready['preparation']['id'],
        source_text=text,goal=ready['goal'],device_context={'platform':'android','locale':'ko-KR'},
        accessibility_tree=initial or wifi(checked=action=='disable'))
    return flow,first


def approve(flow,initial=None):
    return flow.send('respond_confirmation',user_confirmation='accepted',
        confirmation_id=flow.body['metadata']['confirmation']['id'],accessibility_tree=initial or wifi(at=1100))


def event(flow,at=1200):return flow.event(action_type='tap_completed',occurred_at_ms=at)


@pytest.mark.parametrize('action,expected',[('enable',True),('disable',False)])
def test_wifi_toggle_requires_confirmation_and_agreeing_native_evidence(action,expected):
    flow,first=start(action,wifi(not expected))
    assert first['task_status']=='WAITING_CONFIRMATION',first
    assert first['guide_command']['target_node_id']=='wifi-master'
    accepted=approve(flow,wifi(not expected,at=1100))
    assert accepted['guide_command']['action_type']=='tap'
    result=flow.send('observe',accessibility_tree=wifi(expected,at=1800),user_action_event=event(flow))
    assert result['task_status']=='WAITING_RESULT',result
    proof=result['metadata']['task_outcome']['technical']
    assert proof['status']=='CHANGED'
    assert proof['baseline_checked'] is not expected and proof['after_checked'] is expected
    assert proof['setting_value']==(3 if expected else 1)
    assert proof['requested_state']==('on' if expected else 'off')
    assert '와이파이' in result['metadata']['result_prompt']['message']
    assert '글자' not in result['metadata']['result_prompt']['message']
    assert flow.answer('satisfied')['metadata']['task_outcome']['final']=='ACHIEVED'


@pytest.mark.parametrize('action,expected',[('enable',True),('disable',False)])
def test_already_desired_never_toggles_it_back(action,expected):
    flow,body=start(action,wifi(expected))
    assert body['task_status']=='WAITING_RESULT',body
    assert body['guide_command']['action_type']=='observe_only'
    assert body['metadata']['task_outcome']['technical']['status']=='ALREADY_SET'
    assert '이미' in body['metadata']['result_prompt']['message']
    assert 'confirmation' not in body['metadata']
    assert flow.answer('satisfied')['metadata']['task_outcome']['final']=='ACHIEVED'


@pytest.mark.parametrize('problem',['wrong_target','missing_checked','missing_state','preview_only',
    'wrong_direction','stale','different_switch','different_window'])
def test_partial_or_unrelated_evidence_never_claims_wifi_changed(problem):
    flow,_=start();approve(flow)
    tree=wifi(True,at=1800);kwargs={'user_action_event':event(flow)}
    master=next(n for n in tree['nodes'] if n['node_id']=='wifi-master')
    if problem=='no_event':kwargs={}
    if problem=='wrong_target':kwargs['user_action_event']['target_node_id']='mobile'
    if problem=='missing_checked':master['extras']={}
    if problem=='missing_state':tree.pop('applied_settings')
    if problem=='preview_only':tree['applied_settings']['wifi_state']=1
    if problem=='wrong_direction':tree=wifi(False,at=1800)
    if problem=='stale':tree=wifi(True,at=1100)
    if problem=='different_switch':master['text']='모바일 데이터';master['resource_id']='settings:id/mobile_data'
    if problem=='different_window':
        tree['active_window_id']=55
        for n in tree['nodes']:n['window_id']=55
    result=flow.send('observe',accessibility_tree=tree,**kwargs)
    assert result['metadata']['task_outcome']['technical']['status']!='CHANGED',result
    assert result['task_status'] not in {'WAITING_RESULT','COMPLETED'},result


@pytest.mark.parametrize('state',[0,2,4])
def test_transition_state_cannot_complete_or_repeat_the_toggle(state):
    flow,_=start();approve(flow)
    body=flow.send('observe',accessibility_tree=wifi(True,state,1800),user_action_event=event(flow))
    assert body['task_status']=='IN_PROGRESS',body
    assert body['guide_command']['action_type']=='observe_only'
    assert body['metadata']['task_outcome']['technical']['status']!='CHANGED'


def test_internet_page_does_not_treat_mobile_data_as_wifi():
    _,body=start(initial=wifi(False,label='',title='Internet',extra=True))
    assert body['guide_command']['target_node_id'] is None,body
    assert body['task_status']!='WAITING_RESULT'


def test_status_only_master_switch_keeps_identity_across_state_text():
    flow,body=start(initial=wifi(False,label='사용 안 함'))
    assert body['task_status']=='WAITING_CONFIRMATION',body
    approve(flow,wifi(False,label='사용 안 함',at=1100))
    body=flow.send('observe',accessibility_tree=wifi(True,label='사용 중',at=1800),user_action_event=event(flow))
    assert body['metadata']['task_outcome']['technical']['status']=='CHANGED',body


def test_wifi_connection_has_a_separate_user_operated_route():
    body=Flow().resolve('와이파이 연결해줘')
    assert body['goal']['goal_type']=='settings.wifi.connect'
    assert body['preparation']['status']=='ready'
    assert '직접' in body['message']


def test_observed_desired_state_without_action_is_not_attributed_as_a_change():
    flow,_=start();approve(flow)
    body=flow.send('observe',accessibility_tree=wifi(True,at=1800))
    assert body['metadata']['task_outcome']['technical']['status']=='ALREADY_SET'
    assert body['guide_command']['action_type']=='observe_only'


@pytest.mark.parametrize('checked,state,action',[(False,3,'enable'),(True,1,'disable')])
def test_inconsistent_initial_state_never_proposes_an_extra_toggle(checked,state,action):
    _,body=start(action,wifi(checked,state))
    assert body['guide_command']['action_type']=='observe_only'
    assert body['task_status'] not in {'WAITING_CONFIRMATION','WAITING_RESULT'}


def test_internet_page_targets_the_labeled_wifi_switch_not_mobile_data():
    flow,body=start(initial=wifi(False,title='Internet',extra=True))
    assert body['task_status']=='WAITING_CONFIRMATION',body
    assert body['guide_command']['target_node_id']=='wifi-master'


def test_jev_abstention_never_becomes_a_rule_selected_toggle(monkeypatch):
    from app.services.screen_jev import JevScreenResolver
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev')
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    def abstain(self,**kwargs):
        self.last_diagnostics={'provider':'jev','mode':'primary','called':True,'status':'abstained'}
        return None
    monkeypatch.setattr(JevScreenResolver,'resolve',abstain)
    _,body=start(initial=wifi(False))
    assert body['task_status']!='WAITING_CONFIRMATION'
    assert body['guide_command']['action_type']=='observe_only'


def test_missing_native_wifi_state_does_not_authorize_a_toggle():
    tree=wifi(False);tree.pop('applied_settings')
    _,body=start(initial=tree)
    assert body['task_status']!='WAITING_CONFIRMATION'
    assert body['guide_command']['action_type']=='observe_only'


def test_early_observed_change_is_not_attributed_to_a_late_action():
    flow,_=start();approve(flow)
    late=event(flow,at=1600)
    intermediate=flow.send('observe',accessibility_tree=wifi(True,at=1400),user_action_event=flow.event(action_type='observe_only',occurred_at_ms=1300))
    # A subsequently delivered tap happened after we had already observed the change.
    body=flow.send('observe',accessibility_tree=wifi(True,at=2000),user_action_event=late)
    assert body['metadata']['task_outcome']['technical']['status']!='CHANGED'


def test_uncorrelated_tap_never_tells_user_to_toggle_an_already_desired_state_again():
    flow,_=start();approve(flow)
    wrong=event(flow);wrong['target_node_id']='mobile'
    body=flow.send('observe',accessibility_tree=wifi(True,at=1800),user_action_event=wrong)
    assert body['guide_command']['action_type']=='observe_only'
    assert body['metadata']['task_outcome']['technical']['status']!='CHANGED'
