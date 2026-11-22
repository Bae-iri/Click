"""Display size requires correlated native movement and independent display density."""
from copy import deepcopy
from pathlib import Path
import json
import pytest
from pydantic import ValidationError
from app.schemas.agent_turn import AppliedSettings
from tests.test_settings_execution import start, prepare
from tests.test_visible_settings_navigation import screen
SOURCE='android.content.res.Configuration.densityDpi'

def size(value=2,density=420,at=1000,window=42):
    t=screen('화면 크게/작게',[])
    t['nodes'].append(dict(node_id='size',package_name='com.android.settings',window_id=window,
        class_name='android.widget.SeekBar',role='slider',resource_id='com.android.settings:id/size_slider',
        bounds_in_screen=[80,500,1000,600],extras=dict(progress=value,min=0,max=4)))
    for n in t['nodes']:n['window_id']=window
    t.update(active_window_id=window,captured_at_ms=at,observation_id=f'density-{at}')
    if density is not None:t['applied_settings']={'display_density_dpi':density,'display_density_source':SOURCE}
    return t

def approve(f):
    return f.send('respond_confirmation',user_confirmation='accepted',
        confirmation_id=f.body['metadata']['confirmation']['id'],accessibility_tree=size(at=1100))

@pytest.mark.parametrize('direction,value,density',[('increase',3,480),('decrease',1,360)])
@pytest.mark.parametrize('recreated',[False,True])
def test_confirmed_size_change_uses_density_and_survives_native_window_recreation(direction,value,density,recreated):
    f,b=start('settings.display_size.'+direction,size())
    assert b['task_status']=='WAITING_CONFIRMATION',b
    assert b['guide_command']['target_node_id']=='size'
    approve(f)
    t=size(value,density,1800,52 if recreated else 42)
    if recreated:
        t['nodes'][-1]['node_id']='new-size';t['nodes'][-1]['bounds_in_screen']=[90,520,990,630]
    b=f.send('observe',accessibility_tree=t,user_action_event=f.event())
    assert b['task_status']=='WAITING_RESULT',b
    proof=b['metadata']['task_outcome']['technical']
    assert proof['status']=='CHANGED',proof
    assert proof['baseline_setting_value']==420 and proof['setting_value']==density
    assert '화면 요소 크기' in b['metadata']['result_prompt']['message']
    assert f.answer('satisfied')['metadata']['task_outcome']['final']=='ACHIEVED'

@pytest.mark.parametrize('problem',['preview_only','density_only','wrong_direction','missing_density','wrong_target','no_event','stale','wrong_resource','changed_range'])
def test_partial_unrelated_or_old_evidence_never_proves_size_changed(problem):
    f,_=start('settings.display_size.increase',size());approve(f)
    t=size(3,480,1800);event=f.event();kw={'user_action_event':event}
    if problem=='preview_only':t['applied_settings']['display_density_dpi']=420
    if problem=='density_only':t['nodes'][-1]['extras']['progress']=2
    if problem=='wrong_direction':t=size(1,360,1800)
    if problem=='missing_density':t.pop('applied_settings')
    if problem=='wrong_target':event['target_node_id']='other'
    if problem=='no_event':kw={}
    if problem=='stale':t['captured_at_ms']=900
    if problem=='wrong_resource':t['nodes'][-1]['resource_id']='com.android.settings:id/other'
    if problem=='changed_range':t['nodes'][-1]['extras']['max']=9
    b=f.send('observe',accessibility_tree=t,**kw)
    assert (b['metadata'].get('task_outcome') or {}).get('technical',{}).get('status')!='CHANGED',b

@pytest.mark.parametrize('problem',['wrong_page','duplicate','vision','font_control'])
def test_native_display_page_and_one_grounded_size_slider_are_required(problem):
    t=size()
    if problem=='wrong_page':t['nodes'][1]['text']='소리 및 진동'
    if problem=='duplicate':
        n=deepcopy(t['nodes'][-1]);n.update(node_id='duplicate',bounds_in_screen=[80,800,1000,900]);t['nodes'].append(n)
    if problem=='vision':t['nodes'][-1]['node_id']='vision:size'
    if problem=='font_control':t['nodes'][-1]['text']='글자 크기'
    _,b=start('settings.display_size.increase',t)
    assert b['task_status']!='WAITING_CONFIRMATION',b

@pytest.mark.parametrize('payload',[{'display_density_dpi':420},{'display_density_source':SOURCE},
    {'display_density_dpi':True,'display_density_source':SOURCE},{'display_density_dpi':420.0,'display_density_source':SOURCE},
    {'display_density_dpi':0,'display_density_source':SOURCE},{'display_density_dpi':2001,'display_density_source':SOURCE},
    {'display_density_dpi':420,'display_density_source':'slider'}])
def test_density_has_strict_bounded_provenance(payload):
    with pytest.raises(ValidationError):AppliedSettings(**payload)

@pytest.mark.parametrize('goal',json.loads((Path(__file__).parent/'fixtures/expanded_settings/legacy-settings89-1.0.json').read_text()))
def test_new_routes_do_not_replace_existing_pinned_graphs(goal):
    legacy=json.loads((Path(__file__).parent/'fixtures/expanded_settings/legacy-settings89-1.0.json').read_text())[goal]
    f,_=prepare(goal);repo=f.app.state.turn_service.graphs
    old=repo.register(legacy,version='1.0.0',approved=True,priority=100)
    fresh=f.resolve(f.source_text)['preparation']['graph_reference']
    # These routes were expanded again after the initial 1.1 rollout.
    expected='1.2.1' if goal in {'settings.keyboard.change','settings.private_dns.change',
        'settings.hotspot.password.change'} else '1.1.1'
    assert fresh['version']==expected
    pinned,_=repo.select_pinned({'task_id':old['task_id'],'graph_version':'1.0.0',
        'goal_type':goal,'goal':{'target_app':'android_settings'},'graph_hash':old['sha256']})
    assert pinned.definition.task_type=='settings_assisted'


@pytest.mark.parametrize('direction',['increase','decrease'])
def test_virtual_seekbar_tick_children_never_compete_with_the_real_range(direction):
    t=size();t['nodes'][-1]['resource_id']='com.android.settings:id/seek_bar'
    for i in range(5):
        t['nodes'].append(dict(node_id=f'tick-{i}',parent_id='size',package_name='com.android.settings',
            window_id=42,class_name='android.widget.SeekBar',role='slider',checkable=True,
            bounds_in_screen=[80+i*180,500,260+i*180,600],extras={'checked':i==2}))
    _,b=start('settings.display_size.'+direction,t)
    assert b['task_status']=='WAITING_CONFIRMATION',b
    assert b['guide_command']['target_node_id']=='size',b


def test_opaque_seekbar_never_borrows_checked_tick_state_as_a_drag_value():
    t=size();t['nodes'][-1]['extras']={'checked':True}
    _,b=start('settings.display_size.increase',t)
    assert b['task_status']!='WAITING_CONFIRMATION',b
