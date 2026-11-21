"""Closed-choice Jev terminal proof has no action authority or alternate-provider fallback."""
from copy import deepcopy
import json
from unittest.mock import Mock

import httpx
import pytest

from app.services.app_task import AppTaskService
from app.services.app_task_jev_completion import pure_navigation_goal, select_completion
from app.services.app_task_capabilities import ActionCapabilities
from app.services.app_task_learning_policy import digest, target_key
from app.schemas.app_task import TaskApp, TaskAttempt
from tests.test_app_task import compact_sender
from tests.test_app_task_goal_first import current_goal, check


@pytest.fixture(autouse=True)
def isolated(monkeypatch):
    monkeypatch.setenv('APP_TASK_COMPLETION_PROVIDER','jev')
    monkeypatch.setenv('TYPESAFE_API_KEY','local-unit-test-token')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE','0.8')
    monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    monkeypatch.setattr('app.services.goal_jev.JevGoalClassifier._post',lambda *_a,**_k:pytest.fail('No live Jev'))
    monkeypatch.setattr('app.services.app_task.generate',lambda *_a,**_k:pytest.fail('No live Gemini'))


def req(package='org.example.reader'):
    value=current_goal(package);value.planning_mode='result_only';return value


def jev_sender(choice='e0',change=None):
    def post(payload,**_kw):
        options=payload['questions']['completion']['criteria'];prob={key:0.0 for key in options}
        prob[choice]=.98;prob['insufficient' if choice!='insufficient' else 'e0']=.02
        body=dict(model='jev-1.14.0',answers={'completion':dict(type='choice',choice=choice,confidence=.98,probabilities=prob)})
        if change:change(body,payload)
        return httpx.Response(200,json=body)
    return Mock(side_effect=post)


@pytest.mark.parametrize('package',['org.example.reader','org.example.utility','org.example.travel'])
def test_opt_in_terminal_choice_uses_one_jev_call_and_only_current_contract(package):
    value=req(package);before=value.model_dump(mode='json');send=jev_sender();gemini=Mock(side_effect=AssertionError('Gemini fallback'))
    result=AppTaskService(gemini,jev_sender=send).step(value,action_capabilities=ActionCapabilities(frozenset()))
    assert result.status=='confirm_result' and result.action is None and result.completion.checks[0].label=='목적 화면'
    assert result.subgoals==value.state.subgoals and result.active_subgoal=='g1'
    assert result.diagnostic['completion_provider']=='jev' and result.diagnostic['model']=='jev-1.14.0'
    assert result.diagnostic['called'] is True and result.diagnostic['reason_code']=='jev_completion_candidate'
    assert result.diagnostic['allowed_actions_context_used'] is False
    assert value.model_dump(mode='json')==before;send.assert_called_once();gemini.assert_not_called()
    payload=send.call_args.args[0];serialized=json.dumps(payload)
    for forbidden in ['node_0_1','bounds',':id/search','local-unit-test-token','advances_subgoal']:assert forbidden not in serialized
    assert set(payload['questions'])=={'completion'}
    assert 0<send.call_args.kwargs['timeout']<=2


@pytest.mark.parametrize('goal',['파일 앱에서 최근 파일 화면을 열어줘','시계 앱에서 타이머 탭을 열어줘','Open the history page','Show the document category','Navigate to the display page'])
def test_only_whole_single_positive_navigation_is_eligible(goal):assert pure_navigation_goal(goal)


@pytest.mark.parametrize('goal',['블루투스 켜줘','알림 화면 열고 알림 꺼줘','검색창 열고 검색어 입력해줘','Open History then delete records','Open History and start timer','문서 탭 열지 마','문서 화면 닫아줘','볼륨을 올려줘','검색 결과 보여줘','문서 입력 화면에서 전송해줘'])
def test_mutation_compound_query_or_negated_goals_are_ineligible(goal):assert pure_navigation_goal(goal) is None


def test_ineligible_goal_keeps_existing_gemini_single_call():
    value=req();value.goal='목적 화면을 열고 내용을 입력해줘'
    jev=Mock(side_effect=AssertionError('Jev must not start'));gemini=compact_sender({'completion_checks':[check()]})
    result=AppTaskService(gemini,jev_sender=jev).step(value)
    assert result.diagnostic['completion_provider']=='gemini' and result.status=='confirm_result'
    gemini.assert_called_once();jev.assert_not_called()


def test_default_provider_remains_gemini(monkeypatch):
    monkeypatch.delenv('APP_TASK_COMPLETION_PROVIDER');value=req();jev=Mock();gemini=compact_sender({'completion_checks':[check()]})
    result=AppTaskService(gemini,jev_sender=jev).step(value)
    assert result.diagnostic['completion_provider']=='gemini';gemini.assert_called_once();jev.assert_not_called()


@pytest.mark.parametrize('before,actual,goal,expected', [
    (False, True, 'Turn on Dark theme', True), (False, False, 'Turn Dark theme on', False),
    (True, False, 'Disable Dark theme', True), (True, True, 'Turn off Dark theme', False)])
def test_switch_choice_receives_correlated_baseline_but_cannot_verify_the_wrong_value(before,actual,goal,expected):
    value=req()
    value.goal=goal
    node=value.screen.nodes[0]
    node.role='toggle';node.label='Dark theme';node.checked=actual;node.clickable=True
    value.apps=[TaskApp(package=value.screen.package,label='Settings')]
    key=digest(value.screen.package+'\n'+target_key(node))
    value.state.attempts=[TaskAttempt(fingerprint='b'*64,tool='tap',target=node.id,outcome='verified',
                                     switch_before={'key':key,'checked':before})]
    send=jev_sender()
    result=AppTaskService(Mock(side_effect=AssertionError('Unexpected planner')),jev_sender=send).step(value)
    assert bool(result.completion) == expected
    observed=send.call_args.args[0]['state']['switch']
    assert observed==dict(target='Dark theme',requested_checked='on' in goal.lower(),observed_checked=actual,
                          previous_tap=dict(before_checked=before))
    if not expected:assert result.diagnostic['reason_code']=='jev_completion_state_mismatch'
    value.state.attempts[0].switch_before.key='a'*64
    send=jev_sender()
    select_completion(value,value.screen.nodes,sender=send)
    assert 'previous_tap' not in send.call_args.args[0]['state']['switch']


def test_unrelated_or_ambiguous_switch_cannot_be_selected():
    value=req();value.goal='Enable alerts'
    value.apps=[TaskApp(package=value.screen.package,label='Viewer')]
    node=value.screen.nodes[0]
    node.role='toggle';node.label='Sound';node.checked=True
    send=jev_sender()
    result=select_completion(value,value.screen.nodes,sender=send)
    assert result.completion is None
    node.label='alerts'
    value.screen.nodes.append(node.model_copy(update={'id':'other'}))
    assert select_completion(value,value.screen.nodes,sender=send).completion is None
    send.assert_not_called()


@pytest.mark.parametrize('change',[
    lambda b,p:b.update(model='jev-1.13.0'),
    lambda b,p:b['answers'].update(extra=b['answers']['completion']),
    lambda b,p:b['answers']['completion'].update(type='noul'),
    lambda b,p:b['answers']['completion'].update(choice='invented'),
    lambda b,p:b['answers']['completion'].update(confidence=True),
    lambda b,p:b['answers']['completion'].update(confidence=float('nan')),
    lambda b,p:b['answers']['completion']['probabilities'].update(e0=.6),
    lambda b,p:b['answers']['completion']['probabilities'].update(extra=.0),
    lambda b,p:b['answers']['completion'].update(action={'tool':'tap'}),
])
def test_malformed_provider_evidence_cannot_finish_or_start_gemini(change):
    value=req();send=jev_sender(change=change);gemini=Mock();result=AppTaskService(gemini,jev_sender=send).step(value)
    assert result.status=='needs_input' and result.action is None and result.completion is None
    assert result.diagnostic['called'] is True and result.diagnostic['reason_code']=='jev_completion_invalid_response'
    gemini.assert_not_called();send.assert_called_once()


@pytest.mark.parametrize('case',['insufficient','low_confidence','raw_confidence'])
def test_abstention_or_raw_low_confidence_cannot_be_sharpened_into_completion(case,monkeypatch):
    monkeypatch.setenv('JEV_MIN_CONFIDENCE','0.1')
    def change(body,_):
        answer=body['answers']['completion']
        if case=='low_confidence':answer['confidence']=.7
        elif case=='raw_confidence':answer['probabilities']={'e0':.88,'insufficient':.12}
    send=jev_sender(choice='insufficient' if case=='insufficient' else 'e0',change=change)
    result=select_completion(req(),req().screen.nodes,sender=send)
    assert result.completion is None and result.diagnostic['reason_code']=='jev_completion_abstained'


@pytest.mark.parametrize('case',['menu','ocr','unselected','duplicate','input','toggle'])
def test_nonproof_controls_are_never_candidate_choices_or_provider_calls(case):
    value=req();node=value.screen.nodes[0]
    if case=='menu':node.role='button';node.clickable=True
    elif case=='ocr':node.source='ocr'
    elif case=='unselected':node.role='tab';node.selected=False
    elif case=='duplicate':value.screen.nodes.append(node.model_copy(update={'id':'other'}))
    elif case=='input':node.role='text_field';node.editable=True
    else:node.role='toggle';node.checked=True
    send=Mock();result=select_completion(value,value.screen.nodes,sender=send)
    assert result.completion is None and result.diagnostic['called'] is False
    assert result.diagnostic['reason_code']=='jev_completion_no_evidence';send.assert_not_called()


def test_selected_read_only_tab_can_be_proof_without_gaining_action_flags():
    value=req();value.goal='목적 탭을 열어줘';node=value.screen.nodes[0];node.role='tab';node.selected=True
    result=select_completion(value,value.screen.nodes,sender=jev_sender())
    assert result.completion.checks[0].selected is True and result.completion.checks[0].role=='tab'
    assert not node.clickable and not node.editable and not node.scrollable


@pytest.mark.parametrize('goal',['타이머 및 스톱워치 탭을 열어줘','타이머랑 스톱워치 탭을 열어줘','타이머와 스톱워치 탭을 열어줘','A와B 화면을 열어줘','A과 B 화면을 열어줘','언어 및 지역 화면을 열어줘','Open Language & region',
    'Open Timer, Stopwatch','타이머, 스톱워치 화면을 열어줘','Open Timer + Stopwatch','Open Timer/Stopwatch'])
def test_coordinated_destinations_use_existing_planner_instead_of_single_clause_jev(goal):
    assert pure_navigation_goal(goal) is None


@pytest.mark.parametrize('goal',['Open alarm at 6AM','밝기50% 화면을 열어줘','Open alarm at six am',
    'Show enabled connections','Open checked items','Show selected items','알림 활성 상태 화면을 열어줘',
    '선택된 항목 화면을 열어줘','최대 밝기 화면을 열어줘','Open notifications off page',
    '여섯 시 알람 화면을 열어줘','삼십 분 타이머 화면을 열어줘','Open brighter display page',
    '연락처 010-1234-5678 화면을 열어줘'])
def test_value_or_specific_state_qualified_goals_cannot_use_generic_navigation_proof(goal):
    assert pure_navigation_goal(goal) is None


def test_value_goal_returns_to_existing_gemini_before_any_jev_attempt():
    value=req();value.goal='밝기50% 화면을 열어줘'
    jev=Mock(side_effect=AssertionError('No Jev attempt for qualified value goal'))
    gemini=compact_sender({'completion_checks':[]})
    result=AppTaskService(gemini,jev_sender=jev).step(value)
    assert result.diagnostic['completion_provider']=='gemini'
    assert result.completion is None and result.action is None
    gemini.assert_called_once();jev.assert_not_called()


def test_oem_selected_clickable_tab_is_evidence_only_and_never_an_action():
    value=req();value.goal='목적 탭을 열어줘';node=value.screen.nodes[0]
    node.role='tab';node.selected=True;node.clickable=True
    jev=jev_sender();gemini=Mock()
    result=AppTaskService(gemini,jev_sender=jev).step(value,
        action_capabilities=ActionCapabilities(frozenset({('tap',node.id)})))
    assert result.status=='confirm_result' and result.action is None
    assert result.completion.checks[0].role=='tab' and result.completion.checks[0].selected is True
    assert node.clickable is True
    jev.assert_called_once();gemini.assert_not_called()


def test_clickable_text_is_still_not_a_passive_destination_candidate():
    value=req();value.screen.nodes[0].clickable=True;send=Mock()
    result=select_completion(value,value.screen.nodes,sender=send)
    assert result.completion is None and result.diagnostic['reason_code']=='jev_completion_no_evidence'
    send.assert_not_called()


def test_explicit_tab_goal_cannot_use_a_passive_heading_as_tab_selection_proof():
    value=req();value.goal='목적 탭을 열어줘';send=Mock()
    result=select_completion(value,value.screen.nodes,sender=send)
    assert result.completion is None and result.diagnostic['called'] is False
    assert result.diagnostic['reason_code']=='jev_completion_no_evidence';send.assert_not_called()


def test_low_confidence_terminal_never_starts_gemini_fallback():
    def lower(body,_):body['answers']['completion']['confidence']=.7
    jev=jev_sender(change=lower);gemini=Mock()
    result=AppTaskService(gemini,jev_sender=jev).step(req())
    assert result.status=='needs_input' and result.completion is None
    assert result.diagnostic['reason_code']=='jev_completion_abstained'
    assert result.diagnostic['provider_attempt_limit']==1
    jev.assert_called_once();gemini.assert_not_called()


def test_missing_jev_key_is_manual_with_no_provider_fallback(monkeypatch):
    monkeypatch.delenv('TYPESAFE_API_KEY');jev=Mock();gemini=Mock();result=AppTaskService(gemini,jev_sender=jev).step(req())
    assert result.status=='needs_input' and result.action is None and result.diagnostic['called'] is False
    assert result.diagnostic['reason_code']=='jev_completion_unconfigured';jev.assert_not_called();gemini.assert_not_called()


def test_timeout_is_one_attempt_without_fallback_or_completion():
    send=Mock(side_effect=httpx.ReadTimeout('PRIVATE token'));gemini=Mock();result=AppTaskService(gemini,jev_sender=send).step(req())
    assert result.status=='needs_input' and result.completion is None and result.diagnostic['called'] is True
    assert result.diagnostic['reason_code']=='jev_completion_timeout'
    assert 'PRIVATE' not in str(result);send.assert_called_once();gemini.assert_not_called()


def test_expired_outer_budget_never_starts_jev(monkeypatch):
    monkeypatch.setattr('app.services.app_task_jev_completion.remaining',Mock(side_effect=TimeoutError()))
    send=Mock();result=select_completion(req(),req().screen.nodes,sender=send)
    assert result.completion is None and result.diagnostic['called'] is False
    assert result.diagnostic['reason_code']=='jev_completion_timeout';send.assert_not_called()
