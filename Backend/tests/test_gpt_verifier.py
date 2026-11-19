"""GPT advice cannot replace observed verification or leak private screen strings."""
import json
from dataclasses import replace
from unittest.mock import Mock
import pytest
import httpx

from app.services.gpt_verifier import GptVerifier
from ai_agent.runtime.types import AccessibilityNode, VerificationResult, VerificationStatus
from ai_agent.runtime.verification_conditions import summarize_legacy
from tests.test_screen_jev import scene


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('OPENAI_VERIFIER_ENABLED', 'true')
    monkeypatch.setenv('OPENAI_API_KEY', 'test-only-key')
    monkeypatch.setenv('OPENAI_VERIFIER_MODEL', 'gpt-6.2-sol')


def raw(identifier, text, **kwargs):
    return AccessibilityNode(identifier, text=text, package_name='com.android.settings',
        bounds_in_screen=(10,20,400,80), **kwargs)


def response(advice=None, **changes):
    body = {'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant', 'content': [
        {'type': 'output_text', 'text': json.dumps(advice or {
            'judgment': 'supported', 'reason_code': 'target_present', 'evidence_ids': ['n0']})}]}]}
    body.update(changes)
    return httpx.Response(200, json=body)


def run(sender, **kwargs):
    graph,*_=scene('font_size')
    return GptVerifier(sender).review_observation(graph=graph,
        raw_nodes=kwargs.pop('raw_nodes', (raw('private-id','글자 크기'),)),
        reason='target_value_unobservable', **kwargs)


def test_advice_is_minimized_structured_and_cannot_upgrade_rule_unknown():
    sent=[]
    def send(payload,**kwargs):
        sent.append(payload);return response()
    native=VerificationResult(VerificationStatus.UNKNOWN,'s',evidence='target_value_unobservable')
    before=summarize_legacy(native)
    result=run(send,raw_nodes=(raw('private-id','글자 크기'),raw('person','owner@example.com'),
        raw('account','김민수'),raw('summary','설정 비밀',resource_id='android:id/summary')))
    assert result['status']=='reviewed' and result['judgment']=='supported'
    assert result['advisory_only'] is True
    assert summarize_legacy(native)==before and before['status']=='UNKNOWN'
    wire=json.dumps(sent,ensure_ascii=False)
    for text in ('owner@example.com','김민수','설정 비밀','private-id','test-only-key'):
        assert text not in wire and text not in str(result)
    assert sent[0]['store'] is False
    assert sent[0]['text']['format']['strict'] is True
    assert sent[0]['model']=='gpt-6.2-sol'
    assert '글자 크기' in wire


@pytest.mark.parametrize('evidence', [
    (raw('input','1234',class_name='android.widget.EditText'),),
    (raw('title','삼성 계정'),),
    (raw('unknown','제조사의 새로운 메뉴'),),
    (),
])
def test_private_or_empty_observation_never_calls_gpt(evidence):
    sender=Mock(side_effect=AssertionError('must not call'))
    assert run(sender,raw_nodes=evidence)['called'] is False
    sender.assert_not_called()


@pytest.mark.parametrize('advice', [
    {'judgment':'supported','reason_code':'target_present','evidence_ids':['imaginary']},
    {'judgment':'supported','reason_code':'target_present','evidence_ids':[]},
    {'judgment':'supported','reason_code':'target_present','evidence_ids':['n0','n0']},
    {'judgment':'SUCCESS','reason_code':'target_present','evidence_ids':['n0']},
    {'judgment':'supported','reason_code':'owner@example.com','evidence_ids':['n0']},
    {'judgment':'supported','reason_code':'target_present','evidence_ids':['n0'],'task_completed':True},
])
def test_bad_or_hallucinated_model_evidence_is_rejected(advice):
    result=run(lambda *args,**kw:response(advice))
    assert result['status']=='invalid_response' and result['called']
    assert 'task_completed' not in result and 'owner@example.com' not in str(result)


@pytest.mark.parametrize('status', ['incomplete','failed'])
def test_incomplete_model_output_is_not_an_answer(status):
    assert run(lambda *a,**kw:response(status=status))['status']=='invalid_response'


def test_network_error_has_no_fallback_or_exception_text():
    def send(*args,**kwargs):raise httpx.ConnectError('secret key in upstream error')
    result=run(send)
    assert result=={'provider':'openai','status':'provider_error','called':True}


def test_disabled_or_missing_key_never_calls_provider(monkeypatch):
    sender=Mock(side_effect=AssertionError('no network'))
    monkeypatch.setenv('OPENAI_VERIFIER_ENABLED','false')
    assert run(sender)['status']=='disabled'
    monkeypatch.setenv('OPENAI_VERIFIER_ENABLED','true')
    monkeypatch.delenv('OPENAI_API_KEY')
    assert run(sender)['status']=='not_configured'
    sender.assert_not_called()


def test_model_refusal_does_not_become_success():
    body={'status':'completed','output':[{'type':'message','role':'assistant',
        'content':[{'type':'refusal','refusal':'private refusal body'}]}]}
    assert run(lambda *a,**kw:httpx.Response(200,json=body))['status']=='invalid_response'


def test_success_waiting_and_no_correlated_attempt_skip_gpt():
    graph,*_=scene('font_size')
    from types import SimpleNamespace
    review=GptVerifier(Mock(side_effect=AssertionError('must skip')))
    for state in ('SUCCESS','WAITING','FAILED','BLOCKED'):
        assert review.review_runtime(None,graph,None,assessment={'status':state})['called'] is False
    result=SimpleNamespace(verification_result=VerificationResult(VerificationStatus.UNKNOWN,'s',
        evidence='target_value_unobservable'))
    assert review.review_runtime(result,graph,None,assessment={'status':'UNKNOWN'})['called'] is False


def test_runtime_review_requires_new_correlated_event_and_cannot_consume_it_twice():
    from types import SimpleNamespace as NS
    graph,*_=scene('font_size')
    native=VerificationResult(VerificationStatus.UNKNOWN,'s',attempt_id='attempt',evidence='target_value_unobservable')
    result=NS(verification_result=native)
    request=NS(session_id='s',observation_id='after',captured_at_ms=2000,screen_bounds=None,active_window_id=None,
        accessibility_tree=(raw('title','글자 크기'),),user_action_event=NS(event_id='event',
            guide_command_id='guide',target_node_id='target',session_id='s',episode_id='episode',occurred_at_ms=1500))
    previous={'attempt_id':'attempt','guide_command_id':'guide','target_node_id':'target',
        'episode_id':'episode','baseline_observation_id':'before','baseline_captured_at_ms':1000}
    sender=Mock(return_value=response())
    verifier=GptVerifier(sender)
    assessment=summarize_legacy(native)
    assert verifier.review_runtime(result,graph,request,assessment=assessment,previous_attempt=previous)['status']=='reviewed'
    assert verifier.review_runtime(result,graph,request,assessment=assessment,
        previous_attempt={**previous,'event_key':'already-processed'})['called'] is False
    request.user_action_event.episode_id='other'
    assert verifier.review_runtime(result,graph,request,assessment=assessment,previous_attempt=previous)['called'] is False
    assert sender.call_count==1
    assert native.status==VerificationStatus.UNKNOWN
