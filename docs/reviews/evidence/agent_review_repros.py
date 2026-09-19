"""Reproduce review findings at commit 565c11f (2026-09-12).

From the repository root, with the project's Python dependencies installed:
    python docs/reviews/evidence/agent_review_repros.py

Prints one JSON document. Uses synthetic accessibility snapshots, an explicit
keyword scorer, in-memory SQLite, and FastAPI TestClient. No device or deployed
service is contacted. Assertions intentionally confirm the reviewed defects;
convert cases to regression tests with correct expectations when fixing them.
"""

from pathlib import Path
import json, sys

root = Path(__file__).resolve().parents[3]
sys.path.insert(0,str(root/'AI-Agent/src'))
sys.path.insert(0,str(root/'Backend'))
from ai_agent.runtime import AccessibilityNode, ClickAgentEngine, DeviceContext, RawGoalInput
from ai_agent.runtime.types import AgentTurnRequest
from ai_agent.runtime.tasks import TASK_GRAPHS
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.similarity import KeywordFallbackSimilarityScorer
from ai_agent.telemetry.trace_logger import TraceLogger
from app.schemas.agent_turn import AgentTurnRequest as HttpRequest
from app.clients.runtime_agent import RuntimeAgentAdapter
from pydantic import ValidationError

PACKAGE='com.android.settings'
GOAL=RawGoalInput(source_text='increase font size',locale='ko-KR',device_context=DeviceContext(platform='android',app_package=PACKAGE,screen_bounds=(0,0,1080,2400)))
def node(node_id,text,bounds,clickable=False,cls='android.widget.TextView',value=None):
    return AccessibilityNode(node_id=node_id,package_name=PACKAGE,text=text,bounds_in_screen=bounds,clickable=clickable,class_name=cls,extras={} if value is None else {'progress':value})
def engine(): return ClickAgentEngine(similarity_scorer=KeywordFallbackSimilarityScorer())
def run(eng,nodes,index,session='review'):
    return eng.handle_turn(AgentTurnRequest(request_id=f'req-{index}',session_id=session,raw_goal=GOAL if index==0 else None,accessibility_tree=tuple(nodes),screen_bounds=(0,0,1080,2400)))
def summarize(result):
    return {'state':result.current_state_id,'guide':result.guide_command.command_type.value,'target':result.guide_command.target_node_id,'verification':result.verification_result.status.value if result.verification_result else None,'attempts':result.debug.get('transition_attempts'), 'excluded':result.debug.get('failed_node_ids')}

out={}
# R1: No font-size control exists in either snapshot; only brightness increases.
eng=engine()
display=node('display-title','디스플레이',(24,40,1056,96))
brightness=node('brightness','밝기',(100,300,900,350),True,'android.widget.SeekBar',3)
a=run(eng,[display,brightness],0)
b=run(eng,[display,node('brightness','밝기',(100,300,900,350),True,'android.widget.SeekBar',4)],1)
out['R1_unrelated_slider_completion']={'before':summarize(a),'after':summarize(b),'font_size_control_present':False,'user_action_sent':False}
assert b.guide_command.command_type.value=='done'
# Verify the same failure reaches the HTTP adapter's COMPLETED field.
adapter=RuntimeAgentAdapter();adapter.runtime=engine()
for i,progress in enumerate([3,4]):
    tree={'package_name':PACKAGE,'screen_width':1080,'screen_height':2400,'nodes':[
        {'node_id':'display-title','text':'디스플레이','bounds_in_screen':[24,40,1056,96]},
        {'node_id':'brightness','text':'밝기','class_name':'android.widget.SeekBar','clickable':True,'bounds_in_screen':[100,300,900,350],'extras':{'progress':progress}}]}
    payload={'request_id':f'http-{i}','session_id':'adapter-review','accessibility_tree':tree}
    if i==0:payload['raw_goal']='increase font size'
    response=adapter.handle_turn(HttpRequest.model_validate(payload))
out['R1_unrelated_slider_completion']['adapter_task_status']=response.task_status
assert response.task_status=='COMPLETED'
# R2: Three snapshots, zero user actions.
eng=engine();home=[node('settings-title','설정',(24,40,1056,96)),node('display-row','디스플레이',(24,320,1056,408),True)]
turns=[summarize(run(eng,home,i)) for i in range(3)]
out['R2_idle_observations_count_as_failures']={'turns':turns,'user_actions_sent':0}
assert 'display-row' in turns[-1]['excluded']
# R3: Equal evidence and confidence, target chosen by node order.
graph=TASK_GRAPHS['MVP-01'];scorer=KeywordFallbackSimilarityScorer()
rows=[node('row-a','디스플레이',(24,320,1056,408),True),node('row-b','디스플레이',(24,520,1056,608),True)]
matches=[]
for order in (rows,list(reversed(rows))):
    matched=match_next_ui('S1_SETTINGS_HOME',normalize_accessibility_tree(tuple(order)),graph,similarity_scorer=scorer)
    matches.append({'target':matched.target_node_id,'confidence':matched.confidence,'fallback_reason':matched.fallback_reason})
out['R3_equal_candidates_depend_on_order']=matches
assert matches[0]['target']!=matches[1]['target'] and all(m['confidence']==1.0 for m in matches)
# R4: Slider role alone clears the action threshold.
matched=match_next_ui('S4_FONT_SIZE_CONTROL',normalize_accessibility_tree((brightness,)),graph,similarity_scorer=scorer)
out['R4_unrelated_slider_matches_font_transition']={'target':matched.target_node_id,'confidence':matched.confidence,'transition':matched.transition_id}
assert matched.target_node_id=='brightness' and matched.confidence>=0.7
# R5: Diagnostics omit the IDs needed for cross-turn reconstruction.
log=TraceLogger();log.record({'request_id':'r','session_id':'s','step_id':'step','guide':{'guide_command_id':'g','command_type':'highlight','target_node_id':'n'},'verification':{'status':'SUCCESS'}})
out['R5_trace_output']=log.traces[0]
assert 'session_id' not in log.traces[0] and 'guide_command_id' not in log.traces[0]['guide']
# R6: New FE proposal is not accepted by the current HTTP model.
payload={'contract_version':'agent-fe-contract.v0.2','request_id':'r','session_id':'s','expected_version':0,'command_type':'start_task','goal':{'catalog_version':'1.0.0','goal_type':'settings.font_size.increase','target_app':'android_settings','slots':{'setting':'font_size','direction':'increase'}},'accessibility_tree':{'package_name':PACKAGE,'screen_width':1080,'screen_height':2400,'nodes':[]}}
try:HttpRequest.model_validate(payload)
except ValidationError as error:
    out['R6_typed_contract_rejected']=[{'field':'.'.join(map(str,item['loc'])),'error':item['type']} for item in error.errors()]
else:raise AssertionError('Expected proposed contract to be rejected by current schema')

# R7: Exercise the normal HTTP route, including auth, session persistence and output safety.
from fastapi.testclient import TestClient
from app.main import create_app
class ReviewAdapter(RuntimeAgentAdapter):
    def __init__(self, session_store=None):
        super().__init__(session_store=session_store)
        self.runtime.similarity_scorer=KeywordFallbackSimilarityScorer()
app=create_app('sqlite+pysqlite:///:memory:')
app.state.turn_service.engine_factory=ReviewAdapter
with TestClient(app) as client:
    auth=client.post('/api/v1/auth/register',json={'email':'review@example.com','password':'test-password-1234','age_group':'60_plus','occupation_category':'retired'})
    assert auth.status_code==201
    token=auth.json()['access_token']
    http_results=[]
    for i,progress in enumerate([3,4]):
        payload={'request_id':f'api-review-{i}','session_id':'http-review','accessibility_tree':{'package_name':PACKAGE,'screen_width':1080,'screen_height':2400,'nodes':[
            {'node_id':'display-title','text':'디스플레이','bounds_in_screen':[24,40,1056,96]},
            {'node_id':'brightness','text':'밝기','class_name':'android.widget.SeekBar','clickable':True,'bounds_in_screen':[100,300,900,350],'extras':{'progress':progress}}]}}
        if i==0:payload['raw_goal']='increase font size'
        else:payload['expected_version']=http_results[-1]['session_version']
        if i==0:
            unauth=client.post('/api/v1/agent/turn',json=payload)
            assert unauth.status_code==401
            out['R8_missing_authorization_status']=unauth.status_code
        response=client.post('/api/v1/agent/turn',json=payload,headers={'Authorization':f'Bearer {token}'})
        assert response.status_code==200,response.text
        body=response.json()
        http_results.append({'http_status':response.status_code,'task_status':body['task_status'],'session_version':body['session_version'],'command_type':body['guide_command']['command_type']})
    assert http_results[-1]['task_status']=='COMPLETED'
    out['R7_full_http_false_completion']=http_results
print(json.dumps(out, ensure_ascii=False, indent=2))
