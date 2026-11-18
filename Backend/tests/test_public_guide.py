"""Public login choices and credential-free handoff, with an authenticated session."""
from unittest.mock import Mock
from uuid import uuid4
import time,json,httpx,pytest
from fastapi.testclient import TestClient
from app.main import create_app
from app.services.public_guide_policy import OPTIONS
from app.services.public_guide_jev import JevPublicGuideSelector
from tests.helpers import register
from tests.test_goal_preparation import Flow
PATH='/api/v1/agent/public-guide/commands'
def screen(*keys,status='public',reason=None):
    d={'status':status,'package_name':'kr.go.minwon.m','observation_id':uuid4().hex,'captured_at_ms':int(time.time()*1000),'width':1080,'height':2400,
       'nodes':[{'node_id':'native_'+k,'option_id':k,'resource_id':'','label':OPTIONS[k]['label'],'bounds':[10,i*100+10,500,i*100+90],'clickable':True}for i,k in enumerate(keys)]}
    if reason:d['handoff_reason']=reason
    return d
class LoginFlow(Flow):
    def payload(self,**kw):return super().payload(device_context={'platform':'android','locale':'ko-KR','capabilities':{'gov24_login':True}},**kw)
    def command(self,command,**kw):
        r=self.client.post(PATH,json={'request_id':uuid4().hex,'session_id':self.session,'expected_version':getattr(self,'version',0),'command':command,**kw})
        assert r.status_code==200,r.text
        self.version=r.json()['session_version'];return r.json()
@pytest.fixture
def login(monkeypatch):
    monkeypatch.setenv('CLICK_GOV24_GUIDE_ENABLED','1')
    app=create_app()
    with TestClient(app) as client:
        register(client);f=LoginFlow(client,app,'정부24 로그인 도와줘');f.prepared=f.resolve()['preparation']
        assert f.prepared['execution_mode']=='public_guide' and f.prepared['status']=='ready'
        app.state.public_guide_service.selector=Mock();yield f
METHODS=('method_mobile_id','method_simple','method_joint','method_financial')
def start(f,view=None):return f.command('start',goal_resolution_id=f.prepared['id'],screen=view or screen(*METHODS))
def test_visible_methods_ask_without_a_graph_or_model_call(login):
    r=start(login);assert r['subgoal']=='choose_method'
    assert {c['value']for c in r['question']['choices']}==set(METHODS)
    login.app.state.public_guide_service.selector.choose.assert_not_called()
    assert login.prepared['graph_reference'] is None
    assert login.client.get('/api/v1/agent/sessions/'+login.session).json()['public_guide']
@pytest.mark.parametrize('method',METHODS)
def test_button_targets_only_the_current_selected_method(login,method):
    q=start(login)['question'];r=login.command('observe',screen=screen(*METHODS),question_id=q['id'],choice=method)
    assert r['target_node_id']=='native_'+method and r['manual_only'] and r['question'] is None
    login.app.state.public_guide_service.selector.choose.assert_not_called()
def test_natural_answer_is_resolved_or_clarified(login):
    q=start(login)['question'];selector=login.app.state.public_guide_service.selector;selector.choose.return_value=None
    r=login.command('observe',screen=screen(*METHODS),question_id=q['id'],answer='모르겠어')
    assert r['question'] and r['target_node_id'] is None
    selector.choose.return_value='method_simple'
    r=login.command('observe',screen=screen(*METHODS),question_id=r['question']['id'],answer='카카오로 할래')
    assert r['target_node_id']=='native_method_simple'
    assert selector.choose.call_args.args==('카카오로 할래',list(METHODS),'choose_method')
def test_provider_modal_excludes_underlying_methods(login):
    r=start(login,screen('method_simple','provider_kakao','provider_naver','provider_pass'))
    assert r['subgoal']=='choose_provider'
    assert {c['value']for c in r['question']['choices']}=={'provider_kakao','provider_naver','provider_pass'}
@pytest.mark.parametrize('reason',['credential_input','certificate_selection','external_auth','consent_required'])
def test_private_handoff_never_claims_login_success(login,reason):
    start(login);r=login.command('observe',screen=screen(status='handoff',reason=reason))
    assert r['status']=='COMPLETED' and r['outcome']=='handoff' and r['target_node_id'] is None and r['question'] is None
    login.app.state.public_guide_service.selector.choose.assert_not_called()
def test_stale_answer_cannot_select_on_replaced_screen(login):
    q=start(login)['question'];r=login.command('observe',screen=screen('provider_kakao'),question_id=q['id'],choice='method_simple')
    assert r['target_node_id'] is None and r['question']['choices'][0]['value']=='provider_kakao'
    login.app.state.public_guide_service.selector.choose.assert_not_called()
def test_untrusted_label_never_reaches_selector(login):
    view=screen('method_simple');view['nodes'][0]['label']='010-1234-5678'
    assert start(login,view)['target_node_id'] is None
    login.app.state.public_guide_service.selector.choose.assert_not_called()
def test_finish_and_snapshot_do_not_verify_authentication(login):
    start(login);assert login.command('finish')['outcome']=='user_reported'
    assert login.command('snapshot')['target_node_id'] is None
def test_disabled_client_cannot_start(login,monkeypatch):
    monkeypatch.setenv('CLICK_GOV24_GUIDE_ENABLED','0')
    assert login.resolve(preparation_reset=True)['preparation']['execution_mode']=='graph'
def login_choice_response(payload, choice):
    criteria=payload['questions']['login_choice']['criteria']
    return httpx.Response(200,json={'model':payload['model'],'answers':{'login_choice':{
        'type':'choice','choice':choice,'confidence':.99,
        'probabilities':{key:.99 if key==choice else .01/(len(criteria)-1) for key in criteria}}}})


def test_jev_payload_keeps_minimized_sentence_and_only_offered_options(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only');monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    def send(payload,**kw):
        encoded=json.dumps(payload,ensure_ascii=False)
        for forbidden in ('010-1234-5678','홍길동','node_id','bounds','resource_id','screen','history'):assert forbidden not in encoded
        if payload['state']['answer']=='모르겠어':
            return login_choice_response(payload,'insufficient')
        assert payload['state']['answer'].endswith('카카오로 할래')
        assert '[REDACTED]' in payload['state']['answer']
        return login_choice_response(payload,'c0')
    selector=JevPublicGuideSelector(send)
    assert selector.choose('제 이름은 홍길동이고 010-1234-5678, 카카오로 할래',['method_simple','method_joint'],'choose_method')=='method_simple'
    assert selector.choose('모르겠어',['method_simple','method_joint'],'choose_method') is None

def test_real_device_filtered_methods_providers_and_handoff_interoperate(login):
    from pathlib import Path
    samples=json.loads((Path(__file__).parent/'fixtures/gov24_public_login.json').read_text())['screens']
    def fresh(name):
        from copy import deepcopy
        d=deepcopy(samples[name]);d['captured_at_ms']=int(time.time()*1000);d['observation_id']=uuid4().hex;return d
    first=start(login,fresh('methods'))
    chosen=login.command('observe',screen=fresh('methods'),question_id=first['question']['id'],choice='method_simple')
    assert chosen['target_node_id'] in {n['node_id']for n in samples['methods']['nodes']}
    providers=login.command('observe',screen=fresh('providers'))
    assert len(providers['question']['choices'])==13
    selected=login.command('observe',screen=fresh('providers'),question_id=providers['question']['id'],choice='provider_kakao')
    assert selected['target_node_id']==next(n['node_id']for n in samples['providers']['nodes']if n['option_id']=='provider_kakao')
    assert login.command('observe',screen=fresh('handoff'))['outcome']=='handoff'

def test_old_clients_and_negated_login_do_not_start_the_pilot(login):
    body=login.payload(preparation_reset=True)
    body['device_context']['capabilities']={}
    assert login.client.post('/api/v1/agent/commands',json=body).json()['preparation']['execution_mode']=='graph'
    login.text='정부24 로그인 하지마'
    assert login.resolve(preparation_reset=True)['preparation']['execution_mode']=='graph'

def test_missing_answers_and_unknown_private_payload_are_rejected_by_contract(login):
    start(login)
    base={'request_id':uuid4().hex,'session_id':login.session,'expected_version':login.version,'command':'observe','screen':screen('method_simple')}
    assert login.client.post(PATH,json={**base,'answer':'카카오'}).status_code==422
    assert login.client.post(PATH,json={**base,'password':'secret'}).status_code==422
    private=screen('method_simple',status='handoff',reason='credential_input')
    assert login.client.post(PATH,json={**base,'screen':private}).status_code==422


def test_public_provider_preference_is_reused_only_after_that_provider_is_observed(login):
    q=start(login)['question']
    chooser=login.app.state.public_guide_service.selector
    chooser.choose.side_effect=['method_simple','provider_kakao']
    result=login.command('observe',screen=screen(*METHODS),question_id=q['id'],answer='카카오로 할래')
    assert result['target_node_id']=='native_method_simple'
    result=login.command('observe',screen=screen('provider_kakao','provider_naver'))
    assert result['target_node_id']=='native_provider_kakao' and result['question'] is None
    assert chooser.choose.call_count==2
    assert chooser.choose.call_args.args[0]=='카카오로 할래'


@pytest.mark.parametrize('source,selected',[
    ('카카오는 하지 말고 네이버로 할래','provider_naver'),
    ('카카오는 잘 모르겠고 네이버 인증서는 있어','provider_naver'),
    ('어 시발 카카오 말고 네이버로 할래','provider_naver'),
    ('둘 다 말고 PASS로 할래','provider_pass'),
    ('어 시발 그런 거',None),
    ('카카오로 하지 마',None),
])
def test_negative_uncertain_and_rough_language_reaches_jev_whole(monkeypatch,source,selected):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    options=['provider_kakao','provider_naver','provider_pass']
    calls=[]
    def send(payload,**kwargs):
        calls.append(payload)
        assert payload['state']['answer']==source
        criteria=payload['questions']['login_choice']['criteria']
        assert {value['label'] for value in criteria.values() if isinstance(value,dict)}=={OPTIONS[key]['label'] for key in options}
        choice='c'+str(options.index(selected)) if selected else 'insufficient'
        return login_choice_response(payload,choice)
    assert JevPublicGuideSelector(send).choose(source,options,'choose_provider')==selected
    assert len(calls)==1


def test_negative_preference_survives_method_to_provider_transition(login):
    source='카카오는 하지 말고 네이버로 할래'
    q=start(login)['question']
    chooser=login.app.state.public_guide_service.selector
    chooser.choose.side_effect=['method_simple','provider_naver']
    result=login.command('observe',screen=screen(*METHODS),question_id=q['id'],answer=source)
    assert result['target_node_id']=='native_method_simple'
    assert chooser.choose.call_args.args[0]==source
    result=login.command('observe',screen=screen('provider_kakao','provider_naver'))
    assert result['target_node_id']=='native_provider_naver'
    assert chooser.choose.call_args.args[0]==source

def test_runtime_rollout_is_enabled_by_default_and_explicit_disable_is_observable(login,monkeypatch):
    monkeypatch.delenv('CLICK_GOV24_GUIDE_ENABLED',raising=False)
    assert login.app.state.public_guide_service.policy.enabled()
    assert login.client.get('/version').json()['public_guidance']['gov24_login_enabled'] is True
    assert login.resolve(preparation_reset=True)['preparation']['execution_mode']=='public_guide'
    monkeypatch.setenv('CLICK_GOV24_GUIDE_ENABLED','0')
    assert login.client.get('/version').json()['public_guidance']['gov24_login_enabled'] is False
    assert login.resolve(preparation_reset=True)['preparation']['execution_mode']=='graph'
