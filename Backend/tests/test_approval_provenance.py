from copy import deepcopy
from time import time
import pytest
from sqlalchemy import select
from app.models.agent_session import AgentSession,AgentCheckpoint
from tests.test_task_outcomes import Flow,screen,PATH

def delegated(goal='settings.font_size.increase'):
    now=int(time()*1000)
    return {'source':'delegated_goal','goal_type':goal,'granted_at_ms':now,'expires_at_ms':now+300000,
            'max_actions':1,'max_delta_fraction':.2}

def approval(flow,provenance):
    return flow.client.post(PATH,json=flow.payload('respond_confirmation',user_confirmation='accepted',
        confirmation_id=flow.body['metadata']['confirmation']['id'],accessibility_tree=screen(2,1100,1.0),approval_provenance=provenance))

def test_direct_and_delegated_approval_remain_distinct_in_history_and_checkpoint():
    for provenance in ({'source':'user'},delegated()):
        f=Flow();f.start()
        response=approval(f,provenance)
        assert response.status_code==200,response.text
        assert response.json()['metadata']['approval_provenance']==provenance
        with f.app.state.session_factory() as db:
            row=db.get(AgentSession,f.session)
            state=db.get(AgentCheckpoint,f.session).state
            assert state['pending_attempt']['authorization']['provenance']==provenance
            assert (row.workflow or {}).get('delegated_actions',0)==(1 if provenance['source']=='delegated_goal' else 0)

@pytest.mark.parametrize('fault',['other_goal','expired','future','too_many','too_large','no_confirmation'])
def test_out_of_scope_or_unbound_delegation_cannot_authorize(fault):
    f=Flow();f.start();scope=delegated()
    if fault=='other_goal':scope['goal_type']='settings.volume.increase'
    if fault=='expired':scope['granted_at_ms']-=400000;scope['expires_at_ms']-=400000
    if fault=='future':scope['granted_at_ms']+=400000;scope['expires_at_ms']+=400000
    if fault=='too_many':scope['max_actions']=2
    if fault=='too_large':scope['max_delta_fraction']=1
    body=f.payload('respond_confirmation',user_confirmation='accepted',confirmation_id=f.body['metadata']['confirmation']['id'],
        accessibility_tree=screen(2,1100,1.0),approval_provenance=scope)
    if fault=='no_confirmation':body.pop('user_confirmation')
    response=f.client.post(PATH,json=body)
    assert response.status_code in (409,422),response.text
    assert f.client.get('/api/v1/agent/sessions/'+f.session).json()['version']==f.body['session_version']

def test_spent_delegation_is_rejected_before_runtime():
    f=Flow();f.start()
    with f.app.state.session_factory() as db:
        row=db.get(AgentSession,f.session);row.workflow={**(row.workflow or {}),'delegated_actions':1};db.commit()
    assert approval(f,delegated()).status_code==409

def test_transport_budget_does_not_change_idempotent_body():
    f=Flow()
    bad=f.client.post(PATH,json=f.payload('observe'),headers={'X-Click-Turn-Budget-Ms':'oops'})
    assert bad.status_code==422  # schema still rejects an incomplete command before any runtime
    ready=f.prepare();payload=f.payload('start_task',preparation_version='goal-inputs.v1',goal_resolution_id=ready['preparation']['id'],
        goal=ready['goal'],source_text=f.source_text,device_context={'platform':'android','locale':'ko-KR'},accessibility_tree=screen())
    invalid=f.client.post(PATH,json=payload,headers={'X-Click-Turn-Budget-Ms':'0'})
    assert invalid.status_code==400
    first=f.client.post(PATH,json=payload,headers={'X-Click-Turn-Budget-Ms':'5000'});assert first.status_code==200,first.text
    retry=f.client.post(PATH,json=payload,headers={'X-Click-Turn-Budget-Ms':'10000'})
    assert retry.json()==first.json()
