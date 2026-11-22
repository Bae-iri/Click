"""Samsung mode cards require an exact native pair and independent system value."""
from copy import deepcopy
import json
from pathlib import Path
import pytest
from tests.test_settings_execution import start
from tests.test_visible_settings_navigation import screen


def cards(mode=1, at=1000):
    tree=screen('디스플레이', [])
    for index, label in enumerate(('라이트','다크'),1):
        x=(index-1)*540
        tree['nodes'].extend([
            dict(node_id=f'item{index}',parent_id='root',package_name='com.android.settings',window_id=42,
                class_name='android.widget.LinearLayout',role='menu_item',clickable=True,enabled=True,
                resource_id=f'com.android.settings:id/item{index}',bounds_in_screen=[x,300,x+540,700]),
            dict(node_id=f'label{index}',parent_id=f'item{index}',package_name='com.android.settings',window_id=42,
                text=label,class_name='android.widget.TextView',resource_id='com.android.settings:id/icon_title',
                selected=(index==mode),bounds_in_screen=[x+20,500,x+510,560])])
    for node in tree['nodes']:node['window_id']=42
    tree.update(active_window_id=42,captured_at_ms=at,observation_id=f'mode-{at}')
    if mode is not None:
        tree['applied_settings']={'night_mode':mode,'night_mode_source':'android.app.UiModeManager.getNightMode'}
    return tree


def approve(flow,mode):
    return flow.send('respond_confirmation',user_confirmation='accepted',
        confirmation_id=flow.body['metadata']['confirmation']['id'],accessibility_tree=cards(mode,1100))


@pytest.mark.parametrize('goal,before,after,target',[
    ('settings.dark_mode.enable',1,2,'item2'),('settings.dark_mode.disable',2,1,'item1')])
def test_card_mode_change_is_confirmed_and_verified_from_system_state(goal,before,after,target):
    f,body=start(goal,cards(before))
    assert body['task_status']=='WAITING_CONFIRMATION',body
    assert body['guide_command']['target_node_id']==target
    approve(f,before)
    event=f.event(action_type='tap_completed')
    result=f.send('observe',accessibility_tree=cards(after,1800),user_action_event=event)
    assert result['task_status']=='WAITING_RESULT',result
    proof=result['metadata']['task_outcome']['technical']
    assert proof['status']=='CHANGED'
    assert proof['baseline_setting_value']==before and proof['setting_value']==after
    assert '모드 적용' in result['metadata']['result_prompt']['message']
    assert f.answer('satisfied')['metadata']['task_outcome']['final']=='ACHIEVED'


@pytest.mark.parametrize('goal,mode',[('settings.dark_mode.enable',2),('settings.dark_mode.disable',1)])
def test_already_selected_system_mode_never_clicks_the_other_mode(goal,mode):
    _,body=start(goal,cards(mode))
    assert body['task_status']=='WAITING_RESULT',body
    assert body['guide_command']['action_type']=='observe_only'
    assert body['metadata']['task_outcome']['technical']['status']=='ALREADY_SET'


@pytest.mark.parametrize('problem',['missing_system','schedule','duplicate','wrong_page','wrong_resource','vision','overlap'])
def test_untrusted_or_incomplete_cards_cannot_authorize_a_mode_change(problem):
    tree=cards(1)
    if problem=='missing_system':tree.pop('applied_settings')
    if problem=='schedule':tree['applied_settings']['night_mode']=0
    if problem=='duplicate':
        row=deepcopy(tree['nodes'][-2]);row.update(node_id='duplicate',text='다크',bounds_in_screen=[540,800,1080,1100]);tree['nodes'].append(row)
    if problem=='wrong_page':tree['nodes'][1]['text']='음량'
    if problem=='wrong_resource':tree['nodes'][-2]['resource_id']='com.android.settings:id/unknown'
    if problem=='vision':tree['nodes'][-2]['node_id']='vision:item2';tree['nodes'][-1]['parent_id']='vision:item2'
    if problem=='overlap':tree['nodes'][-2]['bounds_in_screen']=[0,300,540,700]
    _,body=start('settings.dark_mode.enable',tree)
    assert body['task_status'] not in {'WAITING_CONFIRMATION','WAITING_RESULT','COMPLETED'},body
    assert body['guide_command']['action_type']=='observe_only'


@pytest.mark.parametrize('problem',['selected_only','no_action','wrong_action','wrong_window','missing_source','stale','unchanged','late_action'])
def test_mode_completion_needs_original_action_and_new_system_value(problem):
    f,_=start('settings.dark_mode.enable',cards(1));approve(f,1)
    tree=cards(2,1800);event=f.event(action_type='tap_completed');kw={'user_action_event':event}
    if problem=='selected_only':tree['applied_settings']['night_mode']=1
    if problem=='unchanged':tree=cards(1,1800)
    if problem=='no_action':kw={}
    if problem=='wrong_action':event['target_node_id']='item1'
    if problem=='wrong_window':
        tree['active_window_id']=55
        for n in tree['nodes']:n['window_id']=55
    if problem=='missing_source':tree.pop('applied_settings')
    if problem=='stale':tree['captured_at_ms']=1100
    if problem=='late_action':
        early=f.send('observe',accessibility_tree=cards(2,1400),user_action_event=f.event(action_type='observe_only',occurred_at_ms=1300))
        event['occurred_at_ms']=1600
    body=f.send('observe',accessibility_tree=tree,**kw)
    assert body['metadata']['task_outcome']['technical']['status']!='CHANGED',body
    assert body['task_status']!='COMPLETED'


def test_jev_abstention_cannot_become_a_rule_selected_mode_card(monkeypatch):
    from app.services.screen_jev import JevScreenResolver
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev');monkeypatch.setenv('TYPESAFE_API_KEY','test-only')
    def abstain(self,**kw):
        self.last_diagnostics={'provider':'jev','called':True,'status':'abstained'}
        return None
    monkeypatch.setattr(JevScreenResolver,'resolve',abstain)
    _,body=start('settings.dark_mode.enable',cards(1))
    assert body['task_status']!='WAITING_CONFIRMATION'
    assert body['guide_command']['action_type']=='observe_only'


def test_recorded_samsung_card_hierarchy_yields_the_dark_card_with_controlled_system_value():
    tree=json.loads((Path(__file__).parent/'fixtures/expanded_settings/samsung-display-mode-cards.json').read_text())
    tree.update(captured_at_ms=1000,observation_id='recorded-mode-structure',
        applied_settings={'night_mode':1,'night_mode_source':'android.app.UiModeManager.getNightMode'})
    _,body=start('settings.dark_mode.enable',tree)
    assert body['task_status']=='WAITING_CONFIRMATION',body
    selected=next(n for n in tree['nodes'] if n['node_id']==body['guide_command']['target_node_id'])
    assert selected['resource_id']=='com.android.settings:id/item2'
    assert body['guide_command']['target_bounds']==[540,317,1080,733]


@pytest.mark.parametrize('goal',['settings.dark_mode.enable','settings.dark_mode.disable'])
def test_existing_switch_release_does_not_make_the_new_choice_release_ambiguous(goal):
    from app.task_graphs import approved_graphs
    from tests.test_settings_execution import prepare
    from app.models.task_graph import ApprovedTaskGraph
    flow,ready=prepare(goal)
    legacy=approved_graphs()[goal].to_dict()
    legacy['transitions']=[t for t in legacy['transitions'] if t['transition_id']!='APPLY_MODE_CHOICE']
    ref=flow.app.state.turn_service.graphs.register(legacy,version='1.0.0',approved=True,priority=100)
    newer=flow.resolve(flow.source_text)
    assert newer['preparation']['status']=='ready',newer
    assert newer['preparation']['graph_reference']['version']=='1.1.1'
    with flow.app.state.session_factory() as db:
        old=db.get(ApprovedTaskGraph,f"{ref['task_id']}:1.0.0")
        assert old.active and old.graph_hash==ref['sha256']
