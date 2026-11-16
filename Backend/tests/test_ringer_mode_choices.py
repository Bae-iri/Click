"""Native modes require an authored choice, consent and independent system evidence."""
from copy import deepcopy
import pytest
from pydantic import ValidationError
from app.schemas.agent_turn import AppliedSettings
from tests.test_settings_execution import start
from tests.test_visible_settings_navigation import screen

SOURCE='android.media.AudioManager.getRingerMode'
CASES=[('settings.mute.enable',2,0,'high_button'),('settings.mute.enable',1,0,'high_button'),
 ('settings.mute.disable',0,2,'low_button'),('settings.mute.disable',1,2,'low_button'),
 ('settings.vibrate.enable',2,1,'mid_button'),('settings.vibrate.enable',0,1,'mid_button'),
 ('settings.vibrate.disable',1,2,'low_button'),('settings.vibrate.disable',0,2,'low_button')]

def modes(value=2,at=1000):
 tree=screen('소리 및 진동',[])
 for i,(resource,label) in enumerate([('low_button','소리'),('mid_button','진동'),('high_button','무음')]):
  x=i*350
  tree['nodes'].extend([
   dict(node_id=resource,parent_id='root',package_name='com.android.settings',window_id=42,
    class_name='android.widget.LinearLayout',role='radio',clickable=True,enabled=True,
    resource_id='com.android.settings:id/'+resource,bounds_in_screen=[x,300,x+330,700]),
   dict(node_id='label_'+resource,parent_id=resource,package_name='com.android.settings',window_id=42,
    text=label,class_name='android.widget.TextView',selected=(value==2-i),bounds_in_screen=[x+20,500,x+310,570])])
 for n in tree['nodes']:n['window_id']=42
 tree.update(active_window_id=42,captured_at_ms=at,observation_id=f'ringer-{at}')
 if value is not None:tree['applied_settings']={'ringer_mode':value,'ringer_mode_source':SOURCE}
 return tree

def approve(flow,before):
 return flow.send('respond_confirmation',user_confirmation='accepted',
  confirmation_id=flow.body['metadata']['confirmation']['id'],accessibility_tree=modes(before,1100))

@pytest.mark.parametrize('goal,before,after,target',CASES)
def test_each_mode_confirms_and_verifies_independent_state(goal,before,after,target):
 f,body=start(goal,modes(before));assert body['task_status']=='WAITING_CONFIRMATION',body
 assert body['guide_command']['target_node_id']==target
 approve(f,before)
 body=f.send('observe',accessibility_tree=modes(after,1800),user_action_event=f.event(action_type='tap_completed'))
 assert body['task_status']=='WAITING_RESULT',body
 proof=body['metadata']['task_outcome']['technical'];assert proof['status']=='CHANGED',proof
 assert (proof['baseline_setting_value'],proof['setting_value'])==(before,after)
 assert '소리 모드' in body['metadata']['result_prompt']['message']
 assert f.answer('satisfied')['metadata']['task_outcome']['final']=='ACHIEVED'

@pytest.mark.parametrize('goal,value',[(g,a) for g,_,a,_ in CASES[::2]])
def test_already_correct_mode_never_clicks_a_different_mode(goal,value):
 _,body=start(goal,modes(value));assert body['task_status']=='WAITING_RESULT',body
 assert body['guide_command']['action_type']=='observe_only'
 assert body['metadata']['task_outcome']['technical']['status']=='ALREADY_SET'

@pytest.mark.parametrize('problem',['missing_value','wrong_page','missing_option','duplicate','wrong_resource','overlap','disabled','vision'])
def test_incomplete_or_ambiguous_choices_cannot_authorize_a_change(problem):
 tree=modes()
 if problem=='missing_value':tree.pop('applied_settings')
 if problem=='wrong_page':tree['nodes'][1]['text']='디스플레이'
 if problem=='missing_option':tree['nodes']=[n for n in tree['nodes'] if n['node_id'] not in {'mid_button','label_mid_button'}]
 if problem=='duplicate':
  n=deepcopy(tree['nodes'][-2]);n.update(node_id='duplicate',text='무음',bounds_in_screen=[700,800,1030,1100]);tree['nodes'].append(n)
 if problem=='wrong_resource':tree['nodes'][-2]['resource_id']='com.android.settings:id/unknown'
 if problem=='overlap':tree['nodes'][-2]['bounds_in_screen']=[350,300,680,700]
 if problem=='disabled':tree['nodes'][-2]['enabled']=False
 if problem=='vision':tree['nodes'][-2]['node_id']='vision:high';tree['nodes'][-1]['parent_id']='vision:high'
 _,body=start('settings.mute.enable',tree)
 assert body['task_status'] not in {'WAITING_CONFIRMATION','WAITING_RESULT','COMPLETED'},body
 assert body['guide_command']['action_type']=='observe_only'

@pytest.mark.parametrize('problem',['selected_only','no_action','wrong_target','wrong_window','missing_value','stale','unchanged','third_mode','late_action'])
def test_completion_requires_original_action_and_fresh_exact_system_value(problem):
 f,_=start('settings.mute.enable',modes());approve(f,2)
 tree=modes(0,1800);event=f.event(action_type='tap_completed');kw={'user_action_event':event}
 if problem in {'selected_only','unchanged'}:tree['applied_settings']['ringer_mode']=2
 if problem=='no_action':kw={}
 if problem=='wrong_target':event['target_node_id']='mid_button'
 if problem=='wrong_window':
  tree['active_window_id']=55
  for n in tree['nodes']:n['window_id']=55
 if problem=='missing_value':tree.pop('applied_settings')
 if problem=='stale':tree['captured_at_ms']=1100
 if problem=='third_mode':tree['applied_settings']['ringer_mode']=1
 if problem=='late_action':
  f.send('observe',accessibility_tree=modes(0,1400),user_action_event=f.event(action_type='observe_only',occurred_at_ms=1300));event['occurred_at_ms']=1600
 body=f.send('observe',accessibility_tree=tree,**kw)
 assert body['metadata']['task_outcome']['technical']['status']!='CHANGED',body

@pytest.mark.parametrize('bad',[{'ringer_mode':0},{'ringer_mode_source':SOURCE},
 {'ringer_mode':True,'ringer_mode_source':SOURCE},{'ringer_mode':3,'ringer_mode_source':SOURCE},
 {'ringer_mode':1,'ringer_mode_source':'selected'}])
def test_invalid_mode_or_unpaired_provenance_is_rejected(bad):
 with pytest.raises(ValidationError):AppliedSettings(**bad)

def test_jev_abstention_never_becomes_a_rule_selected_mode(monkeypatch):
 from app.services.screen_jev import JevScreenResolver
 monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev');monkeypatch.setenv('TYPESAFE_API_KEY','test-only')
 def abstain(self,**kw):self.last_diagnostics={'provider':'jev','called':True,'status':'abstained'};return None
 monkeypatch.setattr(JevScreenResolver,'resolve',abstain)
 _,body=start('settings.mute.enable',modes());assert body['task_status']!='WAITING_CONFIRMATION'
 assert body['guide_command']['action_type']=='observe_only'


@pytest.mark.parametrize('setting,word',[('mute','무음 모드'),('vibrate','진동 모드')])
def test_unspecified_mode_direction_is_asked_instead_of_guessed(setting,word):
 from tests.test_task_outcomes import Flow
 from unittest.mock import Mock
 f=Flow();f.app.state.turn_service.goal_preparation.llm=Mock()
 f.app.state.turn_service.goal_preparation.llm.interpret_inputs.return_value=None
 body=f.resolve(word)
 assert body['preparation']['status']=='needs_input',body
 assert body['preparation']['question']['field']=='state'
 body=f.answer_input('off')
 assert body['goal']['goal_type']==f'settings.{setting}.disable',body
 assert body['preparation']['status']=='ready'


@pytest.mark.parametrize('goal',['settings.mute.enable','settings.mute.disable','settings.vibrate.enable','settings.vibrate.disable'])
def test_new_choice_release_preserves_the_pinned_legacy_assisted_graph(goal):
 import json
 from pathlib import Path
 from tests.test_settings_execution import prepare
 from app.models.task_graph import ApprovedTaskGraph
 legacy=json.loads((Path(__file__).parent/'fixtures/expanded_settings/legacy-sound-modes-1.0.json').read_text())[goal]
 f,_=prepare(goal)
 ref=f.app.state.turn_service.graphs.register(legacy,version='1.0.0',approved=True,priority=100)
 ready=f.resolve(f.source_text)
 assert ready['preparation']['status']=='ready',ready
 assert ready['preparation']['graph_reference']['version']=='1.1.0'
 with f.app.state.session_factory() as db:
  old=db.get(ApprovedTaskGraph,f"{ref['task_id']}:1.0.0")
  assert old.graph_hash==ref['sha256']
