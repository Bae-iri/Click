"""Authenticated graphless browsing, privacy, fencing and real result evidence."""
import json
import time
from uuid import uuid4
from unittest.mock import Mock
import pytest
from sqlalchemy import select
from fastapi.testclient import TestClient
from app.main import create_app
from app.models.runtime_event import RuntimeEvent
from app.models.agent_session import AgentSession
from app.services.browse_policy import query_hash
from tests.helpers import register
from tests.test_goal_preparation import Flow

PATH='/api/v1/agent/browse/commands'
PACKAGE='com.coupang.mobile'

class BrowseFlow(Flow):
    def payload(self, **kwargs):
        return super().payload(device_context={'platform':'android','locale':'ko-KR',
            'capabilities':{'coupang_browse':True}},**kwargs)
    def browse_payload(self, command, **kwargs):
        return {'request_id':str(uuid4()),'session_id':self.session,
            'expected_version':getattr(self,'version',0),'command':command,**kwargs}
    def browse(self, command, **kwargs):
        response=self.client.post(PATH,json=self.browse_payload(command,**kwargs))
        assert response.status_code==200,response.text
        body=response.json();self.version=body['session_version'];return body

def node(name,label='',clickable=False,scrollable=False):
    return {'node_id':name,'resource_id':PACKAGE+':id/'+name,'label':label,
        'bounds':[0,20,300,120],'clickable':clickable,'scrollable':scrollable}

def screen(*nodes,query=None,status='public'):
    value={'status':status,'package_name':PACKAGE,'observation_id':str(uuid4()),
        'captured_at_ms':int(time.time()*1000),'width':1080,'height':2400,'nodes':list(nodes)}
    if query:value['query_hash']=query_hash(query)
    return value

@pytest.fixture
def browse(monkeypatch):
    monkeypatch.setenv('CLICK_COUPANG_BROWSE_ENABLED','1')
    app=create_app()
    with TestClient(app) as client:
        register(client)
        f=BrowseFlow(client,app,'쿠팡 상품 검색해줘')
        selector=Mock()
        selector.choose.side_effect=lambda goal,view,candidates,history:(
            next(((k,n) for k,n in candidates if k==('product' if view.query_hash else 'search')),None),
            {'called':True,'candidate_count':len(candidates),'provider_latency_ms':23,'status':'selected'})
        app.state.browse_service.selector=selector
        yield f

def prepare(f,details=False):
    if details:f.text='쿠팡 상품 상세 보여줘'
    body=f.resolve();assert body['preparation']['question']['field']=='browse_query'
    body=f.answer('무선 이어폰')
    assert body['preparation']['execution_mode']=='browse'
    assert body['preparation']['status']=='ready' and body['goal'] is None
    assert body['preparation']['graph_reference'] is None
    return body['preparation']['id']

@pytest.mark.parametrize('source',['쿠팡에서 무선 이어폰 찾아줘','쿠팡 무선 이어폰을 검색해주세요'])
def test_literal_product_name_skips_redundant_question_but_still_needs_confirmation(browse,source):
    browse.text=source
    body=browse.resolve()
    assert body['resolution_status']=='confirmation_required'
    assert body['preparation']['browse_goal']['query']=='무선 이어폰'
    assert body['preparation']['question'] is None
    with browse.app.state.session_factory() as db:
        assert db.get(AgentSession,browse.session).version==0

def test_search_completes_without_any_graph(browse,monkeypatch):
    monkeypatch.setattr(browse.app.state.turn_service.graphs,'select_for_inputs',
        lambda *a:pytest.fail('No task graph'))
    first=browse.browse('start',goal_resolution_id=prepare(browse),
        screen=screen(node('search_bar',clickable=True)))
    assert first['action_kind']=='search' and first['manual_only']
    result=browse.browse('observe',screen=screen(node('search_results'),
        node('product_item','무선 이어폰',True),query='무선 이어폰'))
    assert result['outcome']=='verified_navigation' and result['status']=='COMPLETED'
    assert browse.app.state.browse_service.selector.choose.call_count==1
    with browse.app.state.session_factory() as db:
        events=list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.kind=='browse')))
        assert len(events)==2 and all(e.graph_hash is None for e in events)
        encoded=json.dumps([e.payload for e in events],ensure_ascii=False)
        assert '무선 이어폰' not in encoded and 'query_hash' not in encoded

@pytest.mark.parametrize('correlate',[False,True])
def test_detail_requires_selected_product_and_actual_tap(browse,correlate):
    resolution=prepare(browse,details=True)
    before=screen(node('search_results'),node('product_title','무선 이어폰',True),query='무선 이어폰')
    first=browse.browse('start',goal_resolution_id=resolution,screen=before)
    assert first['action_kind']=='product'
    kwargs={}
    if correlate:kwargs['action']={'guide_id':first['guide_id'],'observation_id':before['observation_id'],
        'node_id':'product_title','occurred_at_ms':before['captured_at_ms']}
    body=browse.browse('observe',screen=screen(node('product_detail'),
        node('product_detail_title','무선 이어폰')),**kwargs)
    assert (body['outcome']=='verified_navigation')==correlate

@pytest.mark.parametrize('kind',['purchase','checkout','package','private','stale','sensitive'])
def test_unsafe_screens_never_reach_jev(browse,kind):
    view=screen(node('search_bar',clickable=True))
    if kind=='purchase':view=screen(node('buy_now','바로 구매',True))
    if kind=='checkout':view=screen(node('checkout','결제',True))
    if kind=='package':view['package_name']='another.app'
    if kind=='private':view=screen(node('search_bar','person@example.com',True))
    if kind=='stale':view['captured_at_ms']=0
    if kind=='sensitive':view=screen(status='sensitive')
    body=browse.browse('start',goal_resolution_id=prepare(browse),screen=view)
    assert body['target_node_id'] is None and body['outcome']!='verified_navigation'
    browse.app.state.browse_service.selector.choose.assert_not_called()

def test_wrong_query_cannot_complete_or_open_product(browse):
    body=browse.browse('start',goal_resolution_id=prepare(browse),screen=screen(
        node('search_results'),node('product_title','다른 상품',True),query='다른 검색어'))
    assert body['outcome']!='verified_navigation' and body['target_node_id'] is None

def test_idempotency_and_cancel_fence_old_guidance(browse):
    payload=browse.browse_payload('start',goal_resolution_id=prepare(browse))
    first=browse.client.post(PATH,json=payload)
    assert first.status_code==200
    assert browse.client.post(PATH,json=payload).json()==first.json()
    browse.version=first.json()['session_version']
    assert browse.browse('cancel')['outcome']=='cancelled'
    assert browse.client.post(PATH,json=payload).status_code==409

def test_user_report_and_snapshot_are_not_verified_navigation(browse):
    browse.browse('start',goal_resolution_id=prepare(browse))
    assert browse.browse('snapshot')['target_node_id'] is None
    result=browse.browse('finish')
    assert result['outcome']=='user_reported' and result['status']=='COMPLETED'

def test_disabled_and_legacy_clients_keep_existing_graph_behavior(browse,monkeypatch):
    monkeypatch.setenv('CLICK_COUPANG_BROWSE_ENABLED','0')
    assert browse.resolve()['preparation']['execution_mode']=='graph'
    monkeypatch.setenv('CLICK_COUPANG_BROWSE_ENABLED','1')
    payload=browse.payload(preparation_reset=True)
    payload['device_context']={'platform':'android','locale':'ko-KR'}
    response=browse.client.post('/api/v1/agent/commands',json=payload)
    assert response.status_code==200 and response.json()['preparation']['execution_mode']=='graph'

def test_other_account_cannot_access_browse_session(browse):
    browse.browse('start',goal_resolution_id=prepare(browse))
    register(browse.client)
    assert browse.client.post(PATH,json=browse.browse_payload('snapshot')).status_code==403

def test_cancel_during_provider_call_discards_late_selection(browse):
    resolution=prepare(browse)
    def late(goal,view,candidates,history):
        with browse.app.state.session_factory() as db:
            row=db.get(AgentSession,browse.session)
            row.status='CANCELLED';row.version+=1;row.lease_token=None;db.commit()
        return candidates[0],{'called':True,'candidate_count':len(candidates),'provider_latency_ms':1}
    browse.app.state.browse_service.selector.choose.side_effect=late
    body=browse.browse_payload('start',goal_resolution_id=resolution,
        screen=screen(node('search_bar',clickable=True)))
    assert browse.client.post(PATH,json=body).status_code==409

def test_replaced_preparation_during_provider_call_cannot_start_old_goal(browse):
    from app.models.agent_session import AgentGoalResolution
    resolution=prepare(browse)
    def late(goal,view,candidates,history):
        with browse.app.state.session_factory() as db:
            row=db.get(AgentGoalResolution,resolution);row.status='SUPERSEDED';db.commit()
        return candidates[0],{'called':True,'candidate_count':len(candidates),'provider_latency_ms':1}
    browse.app.state.browse_service.selector.choose.side_effect=late
    response=browse.client.post(PATH,json=browse.browse_payload('start',goal_resolution_id=resolution,
        screen=screen(node('search_bar',clickable=True))))
    assert response.status_code==409
    with browse.app.state.session_factory() as db:
        assert db.get(AgentSession,browse.session).version==0

def test_other_app_cannot_be_silently_replaced_with_coupang_and_disabled_answer_is_fenced(browse,monkeypatch):
    browse.text='네이버에서 상품 상세 보여줘'
    result=browse.resolve()
    assert result['preparation']['execution_mode']=='graph' and result['goal'] is None
    browse.text='쿠팡 상품 검색해줘'
    browse.resolve(preparation_reset=True)
    payload=browse.answer_payload('이어폰')
    monkeypatch.setenv('CLICK_COUPANG_BROWSE_ENABLED','0')
    response=browse.client.post('/api/v1/agent/commands',json=payload)
    assert response.status_code==409

@pytest.mark.parametrize('choice,probability',[('c0',.99),('insufficient',.99),('c0',.6),('invented',.99)])
def test_jev_choice_protocol_rejects_unknown_or_uncertain_actions(browse,monkeypatch,choice,probability):
    import httpx
    from app.services.browse_jev import JevBrowseSelector
    from app.schemas.browse import BrowseScreen
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    observation=BrowseScreen.model_validate(screen(node('search_bar',clickable=True)))
    def send(payload,**kwargs):
        assert set(payload)=={'model','state','questions'}
        assert 'bounds' not in str(payload) and 'node_id' not in str(payload)
        keys=payload['questions']['browse']['criteria']
        selected=choice if choice in keys else 'c0'
        probabilities={k:probability if k==selected else (1-probability)/(len(keys)-1) for k in keys}
        return httpx.Response(200,json={'model':payload['model'],'answers':{'browse':{
            'type':'choice','choice':choice,'confidence':probability,'probabilities':probabilities}}})
    result,diagnostics=JevBrowseSelector(send).choose({'goal_type':'coupang.product.search','query':'이어폰'},
        observation,[('search',observation.nodes[0])],[])
    assert (result is not None)==(choice=='c0' and probability==.99)
    assert diagnostics['provider_latency_ms']>=0

def test_ai2_obeys_latest_consent_excludes_earlier_events_and_keeps_unknown_separate(browse):
    from scripts.browse_assessment import assess_browsing
    from app.repositories.users import UserRepository
    resolution=prepare(browse)
    browse.browse('start',goal_resolution_id=resolution)
    assert assess_browsing(browse.app.state.session_factory)['sessions']==0
    with browse.app.state.session_factory() as db:
        row=db.get(AgentSession,browse.session);user=row.user_id
        UserRepository(db).record_consent(user,'model_training',True,'browse-test')
    future=int(time.time())+3
    browse.app.state.turn_service.repository.clock=lambda:future
    browse.browse('finish')
    result=assess_browsing(browse.app.state.session_factory,now=future)
    assert result['sessions']==1 and result['steps']==1
    assert result['outcomes']=={'user_reported':1}
    assert result['independent_target_accuracy'] is None and not result['automatic_training']
    from scripts.agent2 import assess_jev,process_sessions
    assert process_sessions(browse.app.state.session_factory,now=future)['errors']==0
    assess_jev(browse.app.state.session_factory,now=future)
    with browse.app.state.session_factory() as db:
        UserRepository(db).record_consent(user,'model_training',False,'browse-test')
    assert assess_browsing(browse.app.state.session_factory,now=future)['sessions']==0
