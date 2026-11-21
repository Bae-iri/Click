"""Authenticated Android-task evidence -> real Agent2 analysis -> fresh grounded recall."""
from copy import deepcopy
from dataclasses import dataclass
import json
import time
from uuid import uuid4
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select, func, delete, update

from app.main import create_app
from app.models.agent_session import AgentSession
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.graph_proposal import GraphProposal
from app.repositories.users import UserRepository
from app.services.app_task import AppTaskService
from app.services.browse_graph_jobs import AppTaskLearningJobs
from app.services.app_task_learning_policy import CONSUMER, SEEN, digest, screen_key, target_key
from app.services.app_task_learning_policy import step_key
from app.schemas.app_task import AppTaskRequest, TaskSubgoal
from scripts.app_task_learning import AppTaskLearningService
from tests.helpers import register
from tests.test_app_task import sender

PATH='/api/v1/agent/app-guide/task'
GOAL='검색 화면 열어줘'


def request(package='org.example.reader',label='검색',version='12'):
    return AppTaskRequest(request_id=str(uuid4()),goal=GOAL,state={'task_id':str(uuid4())},
        environment={'app_version':version,'android_api':33},screen={'status':'public','package':package,
        'observation_id':str(uuid4()),'captured_at_ms':int(time.time()*1000),'fingerprint':'a'*64,
        'width':1080,'height':2400,'nodes':[{'id':'node_0_1','label':label,'resource':package+':id/search',
        'role':'button','clickable':True,'bounds':[10,20,400,120]}]})


def events(req=None,outcome='verified',task=None):
    req=req or request();n=req.screen.nodes[0];task=task or str(uuid4())
    after=request(req.screen.package,'검색어 입력').screen
    action={'tool':'tap','target_key':target_key(n),'role':'button','expectation':'screen_changed',
            'expected_label_key':'','expected_package':'','advances_subgoal':True}
    base={'protocol_version':'app-task-experience.v1','task_id':task,'goal_key':digest(req.goal),
        'subgoal_key':digest(req.goal),'environment':{'package':req.screen.package,**req.environment},
        'screen_key':screen_key(req.screen),'after_key':screen_key(after) if outcome=='verified' else screen_key(req.screen),
        'after_package':req.screen.package,'observed_at_ms':int(time.time()*1000)+3000,
        'action_observed':outcome!='rejected','condition_met':outcome=='verified'}
    attempt={**base,'event_id':str(uuid4()),'sequence':1,'kind':'attempt','starts_task':True,'action':action,'outcome':outcome}
    finish={**base,'event_id':str(uuid4()),'sequence':2,'kind':'result','starts_task':False,'action':None,
        'screen_key':base['after_key'],'outcome':'user_confirmed','action_observed':False,'condition_met':False,
        'observed_at_ms':base['observed_at_ms']+1}
    return [attempt,finish]


@dataclass
class Flow:
    app: object
    client: TestClient
    user: str
    consent: str
    learner: AppTaskLearningService

    @property
    def factory(self):return self.app.state.session_factory

    def host(self):
        queued=self.client.post('/api/v1/voice-requests/enqueue',json={'id':str(uuid4()),'raw_goal':GOAL})
        assert queued.status_code==200,queued.text
        claimed=self.client.post('/api/v1/voice-requests/next/claim')
        assert claimed.status_code==200,claimed.text
        return claimed.json()['running']

    def submit(self,bodies,*,host=None,finish=True):
        host=host or self.host()
        r=self.client.post(PATH+'/events',json={'protocol_version':'app-task-experience-batch.v1',
            'session_id':host['agent_session_id'],'consent_id':self.consent,'events':bodies})
        if finish:
            result=self.client.post(PATH+'/result',json={'request_id':str(uuid4()),'session_id':host['agent_session_id'],
                'task_id':bodies[0]['task_id'],'outcome':'user_confirmed'})
            assert result.status_code==200,result.text
            complete=self.client.post('/api/v1/voice-requests/'+host['id']+'/complete')
            assert complete.status_code==200,complete.text
        return r,host

    def recall(self,req=None):
        r=self.client.post(PATH+'/memory',json=(req or request()).model_dump(mode='json'))
        assert r.status_code==200,r.text
        return r.json()


@pytest.fixture
def flow(tmp_path):
    app=create_app('sqlite+pysqlite:///'+str(tmp_path/'experience.db'))
    with TestClient(app) as client:
        user=register(client)['user_id']
        with app.state.session_factory() as db:
            consent=UserRepository(db).record_consent(user,'model_training',True,'graph-improvement.v1').id
        learner=AppTaskLearningService(app.state.session_factory)
        app.state.app_task_learning_jobs.runner=lambda job:(learner.process(job['limit']) if 'limit' in job else learner.learn_session(job['session_id'],job['user_id']))
        yield Flow(app,client,user,consent,learner)


@pytest.mark.parametrize('package',['org.example.reader','com.spotify.music','org.mozilla.firefox'])
def test_two_distinct_tasks_survive_restart_and_ground_a_fresh_app_step(flow,package):
    req=request(package)
    first,_=flow.submit(events(req));assert first.status_code==200,first.text
    assert flow.recall(req)['action'] is None
    second,_=flow.submit(events(req));assert second.status_code==200,second.text
    recalled=flow.recall(req)
    assert recalled['status']=='remembered' and recalled['requires_confirmation']
    assert recalled['action']['target_id']=='node_0_1' and recalled['action']['bounds']==[10,20,400,120]
    assert recalled['action']['expectation']['screen_key']==screen_key(request(package,'검색어 입력').screen)
    assert recalled['action']['advances_subgoal'] is False
    # A new service has no in-process chat/session memory; durable DB experience still works.
    from app.services.app_task_experience import AppTaskExperienceService
    fresh=AppTaskExperienceService(flow.factory)
    assert fresh.recall(req,flow.user).status=='remembered'
    # A remembered action must not replace defining the current goal's final result.
    planned=AppTaskService(sender()).step(req);req.state.completion=planned.completion
    send=sender();result=AppTaskService(send,memory=fresh).step(req,user_id=flow.user)
    send.assert_not_called();assert result.status=='proposed' and result.diagnostic['model']=='agent2-experience'


def test_actual_agent2_worker_is_a_separate_process(flow):
    flow.app.state.app_task_learning_jobs.runner=lambda job:{'status':'DEFERRED'}
    _,host=flow.submit(events())
    result=AppTaskLearningJobs(flow.factory).run_safely(host['agent_session_id'],flow.user)
    assert result['processed']==1 and result['statuses']==['ANALYZED']
    with flow.factory() as db:
        receipt=db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==CONSUMER))
        assert receipt.result['analysis']['schema_version']=='ai2-app-task-analysis.v1'
        assert receipt.result['analysis']['independent_device_validation']=='NOT_ASSESSED'


def test_new_failure_invalidates_old_destination_and_reaches_the_planner(flow):
    flow.submit(events());flow.submit(events());assert flow.recall()['action']
    adverse=events(outcome='unconfirmed');adverse[0]['observed_at_ms']+=100;adverse[1]['observed_at_ms']+=101
    flow.submit(adverse);memory=flow.recall();assert memory['action'] is None
    send=sender(tool='ask',target='none')
    AppTaskService(send,memory=flow.app.state.app_task_experience).step(request(),user_id=flow.user)
    prompt=send.call_args.args[0]['contents'][0]['parts'][0]['text']
    assert 'prior_experience' in prompt and 'unconfirmed' in prompt and 'node_0_1' not in prompt


def test_verified_capabilities_can_inform_a_new_app_without_replaying_its_selectors(flow):
    flow.submit(events());flow.submit(events())
    recall=flow.recall(request('org.other.brandnew'))
    assert recall['action'] is None
    assert any(t['tool']=='tap' and t['role']=='button' and t['supporting_tasks']==2 for t in recall['tactics'])


def test_duplicates_are_idempotent_not_independent_support(flow):
    host=flow.host();bodies=events()
    r,_=flow.submit(bodies,host=host,finish=False);assert r.status_code==200
    r,_=flow.submit(bodies,host=host,finish=False);assert r.status_code==200
    with flow.factory() as db:assert db.scalar(select(func.count()).select_from(RuntimeEvent).where(RuntimeEvent.kind=='app_task'))==2
    assert flow.recall()['action'] is None
    changed=deepcopy(bodies);changed[0]['condition_met']=False;changed[0]['outcome']='unconfirmed'
    r,_=flow.submit(changed,host=host,finish=False);assert r.status_code==409


def test_training_withdrawal_and_regrant_do_not_resurrect_memory(flow):
    flow.submit(events());flow.submit(events());assert flow.recall()['action']
    old_epoch=flow.consent
    with flow.factory() as db:UserRepository(db).record_consent(flow.user,'model_training',False,'graph-improvement.v1')
    assert flow.recall()['status']=='disabled'
    with flow.factory() as db:
        assert db.scalar(select(func.count()).select_from(GraphProposal).where(GraphProposal.proposal_type=='app_task_experience'))==0
        new=UserRepository(db).record_consent(flow.user,'model_training',True,'graph-improvement.v1')
        flow.consent=new.id
    assert flow.recall()['action'] is None
    host=flow.host();payload={'session_id':host['agent_session_id'],'consent_id':old_epoch,'events':events()}
    r=flow.client.post(PATH+'/events',json=payload)
    assert r.status_code==200 and r.json()['status']=='ineligible'
    assert flow.recall()['action'] is None


def test_app_version_and_current_targets_must_match_and_user_scope_never_leaks(flow):
    flow.submit(events());flow.submit(events())
    assert flow.recall(request(version='13'))['action'] is None
    assert flow.recall(request(label='다른 항목'))['action'] is None
    register(flow.client)
    assert flow.recall()['action'] is None


def test_history_deletion_and_source_expiry_invalidate_memories(flow):
    _,first=flow.submit(events());flow.submit(events());assert flow.recall()['action']
    with flow.factory() as db:
        db.get(AgentSession,first['agent_session_id']).expires_at=int(time.time())-1;db.commit()
    r=flow.client.delete('/api/v1/agent/sessions/'+first['agent_session_id']);assert r.status_code==204,r.text
    assert flow.recall()['action'] is None
    flow.submit(events());assert flow.recall()['action']
    with flow.factory() as db:
        db.execute(update(RuntimeEvent).where(RuntimeEvent.kind=='app_task').values(expires_at=int(time.time())-1));db.commit()
    assert flow.recall()['action'] is None


def test_plaintext_labels_inputs_images_and_coordinates_are_rejected_and_db_is_encrypted(flow):
    bodies=events();host=flow.host()
    for field in ('input_value','screenshot','label','bounds'):
        bad=deepcopy(bodies);bad[0][field]='PRIVATE'
        r,_=flow.submit(bad,host=host,finish=False);assert r.status_code==422
    r,_=flow.submit(bodies,host=host,finish=False);assert r.status_code==200
    with flow.factory() as db:
        from sqlalchemy import text
        raw=db.execute(text("select payload from runtime_events where kind='app_task'")).scalar()
        assert 'goal_key' not in raw and 'screen_key' not in raw
        rows=list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.kind=='app_task')))
        payload=json.dumps([r.payload for r in rows],ensure_ascii=False)
        assert GOAL not in payload and '검색' not in payload and 'bounds' not in payload


def test_app_experience_does_not_change_running_session_version_or_legacy_ai2_reconstruction(flow):
    host=flow.host()
    with flow.factory() as db:before=db.get(AgentSession,host['agent_session_id']).version
    flow.submit(events(),host=host,finish=False)
    with flow.factory() as db:assert db.get(AgentSession,host['agent_session_id']).version==before
    from scripts.agent2 import process_sessions,assess_jev
    assert process_sessions(flow.factory)['errors']==0
    # No app_task payload is misinterpreted as an old immutable task graph.
    result=assess_jev(flow.factory)
    assert result['execution']['verified_attempts']==0


def test_partial_or_missing_prefix_cannot_become_a_complete_route(flow):
    body=events();host=flow.host()
    r,_=flow.submit([body[1]],host=host,finish=False);assert r.status_code==200
    with flow.factory() as db:
        result=db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==CONSUMER)).result
        assert result['analysis']['status']=='NEEDS_DATA' and result['analysis']['route']==[]
    r,_=flow.submit([body[0]],host=host,finish=False);assert r.status_code==200
    with flow.factory() as db:
        result=db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==CONSUMER)).result
        assert len(result['analysis']['route'])==1


def test_new_endpoints_require_authentication_and_session_ownership(flow):
    host=flow.host();register(flow.client)
    r=flow.client.post(PATH+'/events',json={'session_id':host['agent_session_id'],'consent_id':flow.consent,'events':events()})
    assert r.status_code==404
    flow.client.headers.pop('Authorization')
    assert flow.client.post(PATH+'/memory',json=request().model_dump(mode='json')).status_code==401


def test_periodic_job_catches_a_deferred_upload(flow):
    flow.app.state.app_task_learning_jobs.runner=lambda job:{'status':'DEFERRED'}
    flow.submit(events());assert flow.learner.process()['processed']==1
    assert flow.learner.process()['processed']==0


def test_user_result_is_idempotent_and_completes_the_queue_without_training_consent(flow):
    with flow.factory() as db:UserRepository(db).record_consent(flow.user,'model_training',False,'graph-improvement.v1')
    host=flow.host();body={'request_id':str(uuid4()),'session_id':host['agent_session_id'],'task_id':str(uuid4()),'outcome':'user_confirmed'}
    for _ in range(2):
        r=flow.client.post(PATH+'/result',json=body);assert r.status_code==200,r.text
        assert r.json()['technical_source']=='user_reported'
    with flow.factory() as db:
        row=db.get(AgentSession,host['agent_session_id']);assert row.version==1
        assert row.status=='COMPLETED' and row.task_outcome['technical']['status']=='UNVERIFIABLE'
        assert db.scalar(select(func.count()).select_from(RuntimeEvent).where(RuntimeEvent.kind=='app_task'))==0
    assert flow.client.post('/api/v1/voice-requests/'+host['id']+'/complete').status_code==200
    assert flow.client.get('/api/v1/agent/sessions').status_code==200
    assert flow.host()['agent_session_id']!=host['agent_session_id']


@pytest.mark.parametrize('field,value',[('graph_hash','a'*64),('episode_id','another-episode'),('status','CANCELLED'),('lease_token','another-owner')])
def test_app_result_never_overwrites_another_runtime_or_revives_a_stopped_task(flow,field,value):
    host=flow.host()
    with flow.factory() as db:
        row=db.get(AgentSession,host['agent_session_id']);setattr(row,field,value)
        if field=='lease_token':row.lease_until=int(time.time())+30
        db.commit()
    r=flow.client.post(PATH+'/result',json={'request_id':str(uuid4()),'session_id':host['agent_session_id'],'task_id':str(uuid4()),'outcome':'user_confirmed'})
    assert r.status_code==409


def test_remembered_steps_match_execution_prefix_or_the_current_subgoal(flow):
    flow.submit(events());flow.submit(events())
    req=request();req.path_prefix=['b'*64];assert flow.recall(req)['action'] is None
    req=request();req.state.subgoals=[TaskSubgoal(id='g0',title='다른 단계')]
    assert flow.recall(req)['action'] is None
    req.state.subgoals[0].title=GOAL;assert flow.recall(req)['action']


def test_two_step_experience_is_recalled_only_after_the_matching_first_action(flow):
    a,b,c=request(),request(label='검색어 입력'),request(label='목록 확인')
    for _ in range(2):
        trace=events(a);second=deepcopy(trace[0]);second.update(event_id=str(uuid4()),sequence=2,starts_task=False,
            screen_key=screen_key(b.screen),after_key=screen_key(c.screen),observed_at_ms=trace[0]['observed_at_ms']+10)
        second['action']['target_key']=target_key(b.screen.nodes[0])
        end=deepcopy(trace[1]);end.update(sequence=3,screen_key=screen_key(c.screen),after_key=screen_key(c.screen),observed_at_ms=second['observed_at_ms']+1)
        flow.submit([trace[0],second,end])
    assert flow.recall(b)['action'] is None
    b.path_prefix=[step_key(screen_key(a.screen),'tap',target_key(a.screen.nodes[0]),screen_key(b.screen))]
    recalled=flow.recall(b);assert recalled['action'] and recalled['action']['expectation']['screen_key']==screen_key(c.screen)


def test_deleting_a_prefix_from_another_host_invalidates_the_surviving_receipt(flow):
    trace=events();_,first=flow.submit([trace[0]]);flow.submit([trace[1]]);flow.submit(events());assert flow.recall()['action']
    with flow.factory() as db:before=db.scalar(select(func.count()).select_from(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==CONSUMER))
    r=flow.client.delete('/api/v1/agent/sessions/'+first['agent_session_id']);assert r.status_code==204,r.text
    with flow.factory() as db:assert db.scalar(select(func.count()).select_from(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==CONSUMER))==before
    assert flow.recall()['action'] is None


def test_native_changes_without_confirmed_goal_results_are_not_auto_reuse_support(flow):
    for _ in range(2):flow.submit(events()[:1])
    recalled=flow.recall();assert recalled['action'] is None
    assert recalled['evidence'][0]['successes']==2 and recalled['evidence'][0]['supporting_tasks_after_adverse']==0


def test_account_deletion_removes_source_and_derived_experience(flow):
    flow.submit(events());flow.submit(events());assert flow.recall()['action']
    with flow.factory() as db:UserRepository(db).delete_user(flow.user)
    with flow.factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEvent))==0
        assert db.scalar(select(func.count()).select_from(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer.in_([CONSUMER,SEEN])))==0
        assert db.scalar(select(func.count()).select_from(GraphProposal).where(GraphProposal.proposal_type=='app_task_experience'))==0


def test_late_offline_success_does_not_erase_a_newer_failure_but_fresh_confirmed_tasks_can_recover(flow):
    old1,old2=events(),events();bad=events(outcome='rejected')
    for e in bad:e['observed_at_ms']+=200
    flow.submit(bad);flow.submit(old1);flow.submit(old2)
    assert flow.recall()['action'] is None
    for _ in range(2):
        fresh=events()
        for e in fresh:e['observed_at_ms']+=500
        flow.submit(fresh)
    assert flow.recall()['action']


def test_private_screen_can_check_consent_without_reading_or_recalling_controls(flow):
    req=request();req.screen.status='protected';req.screen.nodes=[]
    result=flow.recall(req);assert result['collection_enabled'] and result['consent_id']==flow.consent
    assert result['consent_recorded_at_ms']>0 and result['action'] is None and result['evidence']==[]


def test_reusing_a_memory_does_not_split_its_own_support(flow):
    """Real 2026-11-21 C-arm notification sequence: 4 planner runs recorded target_visible,
    then memory replays (served as screen_changed). Before the fix the replays formed a second
    candidate and recall fell back to needs_data once both reached two supporting tasks."""
    req=request()
    def run(kind,offset):
        bodies=events(req);bodies[0]['action']['expectation']=kind
        if kind=='target_visible':bodies[0]['action']['expected_label_key']=digest('검색어 입력')
        bodies[0]['observed_at_ms']+=offset;bodies[1]['observed_at_ms']+=offset+1
        r,_=flow.submit(bodies);assert r.status_code==200,r.text
    for i in range(4):run('target_visible',10*i)
    first=flow.recall(req);assert first['status']=='remembered'
    for i in range(3):
        run(first['action']['expectation']['kind'],100+10*i)
        again=flow.recall(req)
        assert again['status']=='remembered',again['evidence']
        assert len(again['evidence'])==1 and again['evidence'][0]['supporting_tasks_after_adverse']==5+i
