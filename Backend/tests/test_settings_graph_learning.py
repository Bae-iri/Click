"""Controlled traces test evidence fences; these are not real device successes."""
from copy import deepcopy
from datetime import datetime,timezone
import time
from uuid import uuid4
import pytest
from sqlalchemy import select,func
from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.proposals.settings_route import generate_route,IncompleteSettingsTrace
from app.models.agent_session import AgentSession
from app.models.graph_proposal import GraphProposal
from app.models.runtime_event import RuntimeEvent,RuntimeEventConsumption
from app.models.user import User
from app.repositories.users import UserRepository
from scripts.settings_graph_learning import SettingsGraphLearningService
from app.task_graphs import approved_graphs


def controlled_records():
    base=deepcopy(approved_graphs()['settings.font_size.increase'].to_dict())
    import json
    base=json.loads(json.dumps(base))
    current='S1_SETTINGS_HOME'
    path=[]
    while current!='S_DONE':
        edge=next(e for e in base['transitions'] if e['from_state']==current and
            (e['completes_task'] or e['to_state'] not in {p['from_state'] for p in path}))
        path.append(edge)
        current=edge['to_state']
        if edge['completes_task']:break
    records=[]
    def trace(index,state):
        return dict(arm='llm',run_ref='trial',trace_turn=index,first_observation=index==1,
            goal_type=base['definition']['goal_type'],base_graph_hash=fingerprint(base),
            scope_hash='a'*64,runtime_code_hash='b'*64,jev_model='jev-1.14.0',
            observation={'state_id':state},planner={'called':index==1,'status':'SUCCESS' if index==1 else 'UNKNOWN'},
            verification={k:{'status':'UNKNOWN'} for k in ('action','subgoal','final')})
    records.append(dict(event_id='first',trace=trace(1,'S1_SETTINGS_HOME'),runtime={}))
    for index,edge in enumerate(path,2):
        t=trace(index,edge['to_state'])
        t.update(verified_transition=edge['transition_id'],attempt_ref=str(index).zfill(16))
        for k in ('action','subgoal'):t['verification'][k]['status']='SUCCESS'
        if edge['completes_task']:t['verification']['final']['status']='SUCCESS'
        records.append(dict(event_id=str(index),trace=t,runtime=dict(verification_status='SUCCESS',count_attempt=True,
            attempt_hash=t['attempt_ref']+'0'*48,before_observation_hash=f'before{index}',
            after_observation_hash=f'after{index}',task_completed=edge['completes_task'])))
    return base,records


def test_actual_path_is_extracted_without_accepting_the_planners_claim_of_success():
    base,records=controlled_records()
    graph=generate_route(records,base)
    assert graph['transitions'][-1]['completes_task']
    assert graph['start_state']=='S1_SETTINGS_HOME'
    assert graph['compatibility']['base_graph_hash']==fingerprint(base)
    assert graph['independent_device_validation']=='NOT_ASSESSED'
    assert 'attempt_ref' not in str(graph) and 'event_id' not in str(graph)


@pytest.mark.parametrize('problem',['prefix','gap','unknown','no_action','wrong_attempt','no_final',
    'native_final','scope','base','blocked','disconnected','unlinked_screen','not_llm'])
def test_incomplete_or_incompatible_evidence_cannot_become_a_route(problem):
    base,records=controlled_records()
    if problem=='prefix':records=records[1:]
    elif problem=='gap':records[1]['trace']['trace_turn']=99
    elif problem=='unknown':records[1]['trace']['verification']['subgoal']['status']='UNKNOWN'
    elif problem=='no_action':records[1]['runtime']['count_attempt']=False
    elif problem=='wrong_attempt':records[1]['runtime']['attempt_hash']='x'*64
    elif problem=='no_final':records[-1]['trace']['verification']['final']['status']='UNKNOWN'
    elif problem=='native_final':records[-1]['runtime']['task_completed']=False
    elif problem=='scope':records[-1]['trace']['scope_hash']='changed'
    elif problem=='base':base['definition']['name']='changed'
    elif problem=='blocked':records[1]['trace']['verification']['subgoal']['status']='BLOCKED'
    elif problem=='disconnected':records[1]['trace']['verified_transition']=records[-1]['trace']['verified_transition']
    elif problem=='unlinked_screen':records[1]['trace'].pop('verified_transition')
    elif problem=='not_llm':records[0]['trace']['arm']='graph'
    with pytest.raises(IncompleteSettingsTrace):generate_route(records,base)


@pytest.fixture
def evidence_db(tmp_path,monkeypatch):
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED','0')
    monkeypatch.setenv('CLICK_AI2_SETTINGS_GRAPH_ENABLED','0')
    from app.main import create_app
    from tests.helpers import authenticated_client
    app=create_app('sqlite+pysqlite:///'+(tmp_path/'settings.db').as_posix())
    with authenticated_client(app) as client:
        factory=app.state.session_factory
        base,records=controlled_records()
        now=int(time.time())+2
        with factory() as db:
            user=db.scalar(select(User.id))
            UserRepository(db).record_consent(user,'model_training',True,'test')
            db.add(AgentSession(id='route-session',user_id=user,status='COMPLETED',version=len(records),
                episode_id='episode',accepted_goal_type=base['definition']['goal_type'],
                created_at=now,updated_at=now,expires_at=now+600))
            db.flush()
            for sequence,record in enumerate(records,1):
                db.add(RuntimeEvent(id=str(uuid4()),session_id='route-session',user_id=user,episode_id='episode',
                    sequence=sequence,request_id=f'r{sequence}',graph_hash=fingerprint(base),kind='turn',
                    payload=dict(source_kind='runtime',settings_experiment=record['trace'],runtime=record['runtime'],
                        graph_reference={'sha256':fingerprint(base),'version':'1.0.0'}),
                    created_at=now,expires_at=now+600))
            db.commit()
        yield factory,user,now


def test_learning_persists_encrypted_draft_and_is_idempotent(evidence_db):
    factory,user,now=evidence_db
    learner=SettingsGraphLearningService(factory)
    result=learner.learn_episode('route-session',user,'episode',now=now)
    assert result['status']=='DRAFT_CREATED'
    assert learner.learn_episode('route-session',user,'episode',now=now)['status']=='UNCHANGED'
    with factory() as db:
        draft=db.get(GraphProposal,result['proposal_id'])
        assert draft.requires_human_review
        assert draft.evidence['automatic_release'] is False
        assert db.scalar(select(func.count()).select_from(GraphProposal))==1


@pytest.mark.parametrize('problem',['withdraw','regrant','expired_prefix','pregrant','different_owner'])
def test_consent_retention_and_ownership_fences(evidence_db,problem):
    factory,user,now=evidence_db
    learner=SettingsGraphLearningService(factory)
    learner.learn_episode('route-session',user,'episode',now=now)
    with factory() as db:
        if problem in {'withdraw','regrant'}:
            UserRepository(db).record_consent(user,'model_training',problem=='regrant','new')
            assert db.scalar(select(func.count()).select_from(GraphProposal))==0
            if problem=='regrant':
                consent=UserRepository(db).latest_consent(user,'model_training')
                consent.recorded_at=datetime.fromtimestamp(now+1,timezone.utc)
                db.commit()
        elif problem=='expired_prefix':
            event=db.scalar(select(RuntimeEvent).order_by(RuntimeEvent.sequence))
            event.expires_at=now-1
            db.commit()
        elif problem=='pregrant':
            event=db.scalar(select(RuntimeEvent).order_by(RuntimeEvent.sequence))
            event.created_at=0
            db.commit()
    result=learner.learn_episode('route-session',user,'other' if problem=='different_owner' else 'episode',now=now)
    assert result['status']=='INELIGIBLE'


def test_periodic_worker_connects_retained_settings_evidence(evidence_db):
    factory,_,now=evidence_db
    assert SettingsGraphLearningService(factory).process(now=now)['created']==1
    assert SettingsGraphLearningService(factory).process(now=now)['created']==0


def test_real_runtime_trace_connects_actions_and_native_effect_to_ai2(monkeypatch):
    from dataclasses import replace
    from ai_agent.runtime.types import UserActionEvent,UserActionType,CommandType
    from ai_agent.telemetry.execution_events import produce
    from app.services.settings_experiment import SettingsExperimentResolver,trace
    from tests.test_settings_stability_matrix import Run,node
    from tests.test_settings_planning_experiment import Planner
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER','jev')
    run=Run('brightness','increase')
    wrapper=SettingsExperimentResolver(run.resolver,Planner())
    wrapper.prepare(None,'llm','e')
    wrapper.state['scope_hash']='c'*64
    run.engine.screen_resolver=wrapper
    original=run.engine.handle_turn
    records=[]
    def observed(request):
        before=deepcopy(run.engine.session_store.get('s'))
        result=original(request)
        after=run.engine.session_store.get('s')
        evidence=trace(wrapper,request,result,before,after,run.graph,10)
        records.append(dict(event_id=request.request_id,trace=deepcopy(evidence),runtime=produce(result,before,after)))
        return result
    run.engine.handle_turn=observed
    run.edge=run.home_edge
    first=run.observe(run.home)
    tap=UserActionEvent(UserActionType.TAP_COMPLETED,first.guide_command.guide_command_id,'menu',
        occurred_at_ms=run.at+500,event_id='tap',episode_id='e',session_id='s')
    run.edge=next(t for t in run.graph.transitions if t.from_state==run.home_edge.to_state)
    category=(node('title','디스플레이'),node('submenu','밝기'))
    second=run.observe(category,event=tap)
    tap=UserActionEvent(UserActionType.TAP_COMPLETED,second.guide_command.guide_command_id,'submenu',
        occurred_at_ms=run.at+500,event_id='tap-control',episode_id='e',session_id='s')
    run.edge=next(t for t in run.graph.transitions if t.target_role=='slider')
    control=(node('title','밝기'),node('target','밝기',slider=True))
    result=run.observe(control,event=tap)
    for _ in range(3):
        if result.guide_command.command_type==CommandType.CONFIRMATION:break
        result=run.observe(control)
    assert result.guide_command.command_type==CommandType.CONFIRMATION
    guide=run.observe(control,confirmation='accepted')
    drag=UserActionEvent(UserActionType.DRAG_COMPLETED,guide.guide_command.guide_command_id,'target',
        occurred_at_ms=run.at+500,event_id='drag',episode_id='e',session_id='s')
    changed=(control[0],replace(control[1],extras={'progress':4,'min':0,'max':7}))
    result=run.observe(changed,event=drag,applied={**run.applied,'screen_brightness':101})
    assert result.verification_result.task_completed
    for r in records:
        if r['trace'].get('verified_transition'):
            assert r['runtime'].get('count_attempt') is True, (r['trace']['verified_transition'],r['runtime'])
            assert r['runtime']['attempt_hash'][:16]==r['trace']['attempt_ref'], (r['trace']['attempt_ref'],r['runtime'])
    route=generate_route(records,run.graph.to_dict())
    assert len(route['transitions'])==3
