import pytest
from ai_agent.runtime.execution_contract import policy,project_phase
from tests.test_task_outcomes import Flow, screen, PATH

class ContractFlow(Flow):
    def payload(self,command,**kwargs):
        value=super().payload(command,**kwargs)
        value['execution_context']={'protocol_version':policy()['version'],'session_id':value['session_id'],
            'session_version':value['expected_version'],'request_id':value['request_id'],
            'episode_id':value.get('episode_id'),'observation_id':(value.get('accessibility_tree') or {}).get('observation_id')}
        return value
    def send(self,command,**kwargs):
        value=super().send(command,**kwargs)
        state=value['execution_state']
        assert state['session_id']==value['session_id'] and state['session_version']==value['session_version']
        assert state['episode_id']==value.get('episode_id') and state['task_status']==value['task_status']
        assert state['phase']==project_phase(value['task_status'],command,'turn',value.get('guide_command'),(value.get('metadata',{}).get('verification') or {}).get('result'))
        assert state['request_id']==value['request_id'] and state['command_type']==command
        assert state['source']=='turn'
        return value

@pytest.mark.process_runtime
def test_negotiated_state_survives_runtime_process_approval_verification_and_user_result():
    f=ContractFlow()
    try:
        result=f.change();state=result['execution_state']
        assert state['verification_status']=='SUCCESS' and state['verification_attempt_id']
        assert state['graph_hash']==result['graph_reference']['sha256']
        assert f.answer('satisfied')['execution_state']['phase']=='COMPLETED'
        snapshot=f.client.get('/api/v1/agent/sessions/'+f.session).json()
        assert snapshot['execution_state']['source']=='snapshot'
        assert snapshot['execution_state']['request_id'] is None
        assert snapshot['execution_state']['session_version']==snapshot['version']
    finally:f.client.close();f.app.state.engine.dispose()

def test_controls_results_and_new_episode_share_one_envelope():
    f=ContractFlow();f.start();f.send('pause_task');f.send('pause_task');f.send('resume_task',accessibility_tree=screen())
    f.send('request_result');f.answer('needs_help');old=f.body['episode_id']
    result=f.send('adjust_task',adjustment_reason='too_small')
    assert result['episode_id']!=old
    f.send('cancel_task')
    assert f.client.get('/api/v1/agent/sessions/'+f.session).json()['execution_state']['phase']=='STOPPED'

@pytest.mark.parametrize('key,value',[('session_id','other'),('episode_id','other'),('request_id','other'),
    ('session_version',999),('observation_id','old'),('protocol_version','unknown')])
def test_mixed_context_is_rejected_before_runtime_or_state_mutation(key,value):
    f=ContractFlow();f.start();before=f.body['session_version']
    request=f.payload('observe',accessibility_tree=screen());request['execution_context'][key]=value
    response=f.client.post(PATH,json=request)
    assert response.status_code==422,response.text
    assert f.client.get('/api/v1/agent/sessions/'+f.session).json()['version']==before

def test_runtime_itself_rejects_a_mixed_identity_before_observation():
    from ai_agent.runtime import ClickAgentEngine,AgentTurnRequest
    request=AgentTurnRequest(request_id='r',session_id='s',raw_goal=None,accessibility_tree=(),
        execution_context={'protocol_version':policy()['version'],'session_id':'other','request_id':'r',
                           'session_version':0,'episode_id':None,'observation_id':None})
    with pytest.raises(ValueError,match='EXECUTION_CONTEXT_MISMATCH'):ClickAgentEngine().handle_turn(request)

def test_response_uses_committed_episode_and_graph_without_a_second_database_read(monkeypatch):
    from app.schemas.agent_turn import AgentCommandRequest,AgentTurnResponse
    f=ContractFlow();f.start();service=f.app.state.turn_service
    result=AgentTurnResponse.model_validate({**f.body,'total_steps':4,'state_confidence':1})
    request=AgentCommandRequest.model_validate(f.payload('observe',accessibility_tree=screen()))
    monkeypatch.setattr(service,'handle_turn',lambda *a,**kw:result)
    def refuse(*a,**kw):raise AssertionError('Later DB version must not be mixed into committed response')
    monkeypatch.setattr(service.repository,'typed_details',refuse)
    response=service.handle_command(request)
    assert response.episode_id==result.episode_id
    assert response.graph_reference.sha256==result.metadata['execution_graph_reference']['sha256']

def test_phase_projection_matches_the_shared_android_cases():
    import json
    from pathlib import Path
    cases=json.loads((Path(__file__).resolve().parents[2]/'AI-Agent/tests/fixtures/execution_phase_cases.json').read_text())
    for case in cases:
        assert project_phase(case['status'],case['command'],'turn',case.get('guide'),case.get('verification'))==case['phase']

@pytest.mark.parametrize('mode',['header','production'])
def test_contract_cannot_be_silently_downgraded(mode,monkeypatch):
    f=ContractFlow();f.start()
    request=f.payload('observe',accessibility_tree=screen());request.pop('execution_context')
    headers={'X-Click-Execution-State':policy()['version']} if mode=='header' else {}
    if mode=='production':monkeypatch.setenv('CLICK_REQUIRE_EXECUTION_CONTEXT','1')
    response=f.client.post(PATH,json=request,headers=headers)
    assert response.status_code==409 and response.headers['X-Conflict-Code']=='CONTRACT_MISMATCH'
    assert f.client.get('/api/v1/agent/sessions/'+f.session).json()['version']==f.body['session_version']

@pytest.mark.parametrize('fault',['response_session','response_request','checkpoint_session','checkpoint_task','wrong_graph_scope'])
def test_parent_rejects_mixed_worker_result_before_committing(monkeypatch,fault):
    f=ContractFlow();f.start();before=f.body['session_version']
    def mixed(operation,timeout):
        result=operation()
        if fault=='response_session':result['response']['session_id']='other'
        if fault=='response_request':result['response']['request_id']='other'
        if fault=='checkpoint_session':result['checkpoint']['session_id']='other'
        if fault=='checkpoint_task':result['checkpoint']['task_id']='other'
        if fault=='wrong_graph_scope':result['response']['task_id']=result['checkpoint']['task_id']='other'
        return result
    monkeypatch.setattr('app.services.agent_turn.run_runtime',mixed)
    response=f.client.post(PATH,json=f.payload('observe',accessibility_tree=screen()))
    assert response.status_code==502,response.text
    assert f.client.get('/api/v1/agent/sessions/'+f.session).json()['version']==before
