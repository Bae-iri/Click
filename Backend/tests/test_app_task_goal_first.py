"""One-call goal assessment: current evidence can stop actions, plan bits cannot claim success."""
import json
from unittest.mock import Mock

import pytest
from pydantic import ValidationError

from app.schemas.app_task import AppTaskRequest, TaskAttempt, TaskCompletion, TaskResultCheck, TaskSubgoal
from app.schemas.app_task_learning import TaskMemoryResponse
from app.services.app_task import AppTaskService
from app.services.app_task_capabilities import ActionCapabilities
from tests.test_app_task import request, sender, compact_sender
from tests.test_app_task_recovery_capabilities import recovering


@pytest.fixture(autouse=True)
def no_live_provider(monkeypatch):
    monkeypatch.delenv('GEMINI_API_KEY', raising=False)
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL', 'gemini-3.8-flash')
    monkeypatch.setattr('app.services.app_task.generate', lambda *_a, **_k: pytest.fail('No live calls in goal-first tests'))


def current_goal(package='org.example.reader', *, planned=True):
    req=request(package);req.planning_mode='goal_first';req.goal='목적 화면을 열어줘'
    if planned:
        req.state.subgoals=[TaskSubgoal(id='g0',title='메뉴 열기',status='verified'),
            TaskSubgoal(id='g1',title='목적 화면 열기')]
    node=req.screen.nodes[0];node.label='목적 화면';node.role='text';node.clickable=False
    return req


def check(**changes):
    value=dict(target='c0',label='',app='none',checked='unknown',selected='unknown');value.update(changes);return value


def answer(req, status='satisfied', **changes):
    value=dict(subgoals=[s.title for s in req.state.subgoals],tool='confirm',target='none',expected_label='',
        app='none',expected_checked='unknown',expected_selected='unknown',advances_subgoal=False,
        completion_checks=[check()],goal_status=status)
    value.update(changes);return value


@pytest.mark.parametrize('package',['org.example.reader','org.example.utility','org.example.travel'])
@pytest.mark.parametrize('planned',[True,False])
def test_current_goal_stops_actions_with_pending_or_empty_plan_in_one_existing_call(package,planned):
    req=current_goal(package,planned=planned);before=req.model_dump(mode='json')
    send=compact_sender(answer(req,tool='tap',target='invented',advances_subgoal=True))
    result=AppTaskService(send).step(req,action_capabilities=ActionCapabilities(frozenset()))
    send.assert_called_once()
    assert result.status=='confirm_result' and result.action is None
    assert result.completion.checks[0].label=='목적 화면'
    assert result.subgoals==req.state.subgoals and result.active_subgoal==('g1' if planned else None)
    assert result.diagnostic['planning_mode']=='goal_first' and result.diagnostic['goal_assessment']=='satisfied'
    assert result.diagnostic['allowed_action_count']==0
    assert req.model_dump(mode='json')==before
    schema=send.call_args.args[0]['generationConfig']['responseJsonSchema']
    assert schema['properties']['goal_status']['enum']==['satisfied','not_satisfied','uncertain']
    assert 'goal_status' in schema['required']


@pytest.mark.parametrize('case',['missing','future','menu','duplicate','unselected','ocr','conjunction'])
def test_satisfied_requires_full_current_safe_unique_evidence(case):
    req=current_goal();changes={}
    if case=='missing':changes['completion_checks']=[]
    elif case=='future':changes['completion_checks']=[check(target='none',label='미관찰 목적')]
    elif case=='menu':req.screen.nodes[0].role='button';req.screen.nodes[0].clickable=True
    elif case=='duplicate':req.screen.nodes.append(req.screen.nodes[0].model_copy(update={'id':'other'}))
    elif case=='unselected':req.screen.nodes[0].role='tab';req.screen.nodes[0].selected=False;changes['completion_checks']=[check(selected='true')]
    elif case=='ocr':req.screen.nodes[0].source='ocr'
    else:changes['completion_checks']=[check(),check(target='none',label='미관찰 요구사항')]
    send=compact_sender(answer(req,tool='tap',target='c0',**changes));result=AppTaskService(send).step(req)
    assert result.status=='needs_input' and result.action is None and result.completion is None
    assert result.diagnostic['goal_assessment']=='uncertain' and result.diagnostic['reason_code']=='goal_evidence_unavailable'
    assert result.subgoals==req.state.subgoals;send.assert_called_once()


def test_two_clauses_cannot_reuse_one_native_target_as_distinct_proofs():
    req=current_goal();result=AppTaskService(compact_sender(answer(req,completion_checks=[check(),check(target='none',label='목적 화면')]))).step(req)
    assert result.status=='needs_input' and result.action is None and result.completion is None
    assert result.diagnostic['goal_assessment']=='uncertain'


def test_negative_assessment_with_current_final_proof_cannot_navigate_away():
    req=current_goal();send=compact_sender(answer(req,status='not_satisfied',tool='tap',target='c0'))
    result=AppTaskService(send).step(req)
    assert result.status=='needs_input' and result.action is None and result.completion is None
    assert result.diagnostic['goal_assessment']=='uncertain'
    assert result.diagnostic['completion_grounding']=='deferred_goal_not_satisfied'
    assert result.subgoals==req.state.subgoals;send.assert_called_once()


def test_not_satisfied_keeps_normal_action_and_reliable_future_condition_without_extra_call():
    req=request();req.planning_mode='goal_first';send=sender(goal_status='not_satisfied');result=AppTaskService(send).step(req)
    assert result.status=='proposed' and result.action is not None and result.completion.checks[0].label=='목록'
    assert result.diagnostic['goal_assessment']=='not_satisfied'
    assert all(s.status=='pending' for s in result.subgoals);send.assert_called_once()
    prompt=send.call_args.args[0]['contents'][0]['parts'][0]['text']
    assert 'FINAL requested result is already observable' in prompt
    assert 'never invent a label or treat a prediction as observed proof' in prompt
    assert 'Being on an intermediate page alone is not uncertainty' in prompt
    assert 'if the goal is not yet present and a grounded next step is clear, use not_satisfied' in prompt


def test_not_satisfied_without_action_capability_cannot_grant_one():
    req=current_goal();req.screen.nodes[0].label='현재 중간 화면'
    send=compact_sender(answer(req,status='not_satisfied',completion_checks=[],tool='tap',target='c0'))
    result=AppTaskService(send).step(req,action_capabilities=ActionCapabilities(frozenset()))
    assert result.status=='needs_input' and result.action is None and result.diagnostic['reason_code']=='no_allowed_actions'
    send.assert_called_once()


def test_uncertain_cannot_act_or_rewrite_progress():
    req=current_goal();send=compact_sender(answer(req,status='uncertain',tool='tap',target='c0',advances_subgoal=True))
    result=AppTaskService(send).step(req)
    assert result.status=='needs_input' and result.action is None and result.completion is None
    assert result.subgoals==req.state.subgoals and result.active_subgoal=='g1';send.assert_called_once()


@pytest.mark.parametrize('change',['bad_status','missing_status','changed_plan'])
def test_malformed_assessment_or_changed_plan_fails_closed(change):
    req=current_goal();value=answer(req)
    if change=='bad_status':value['goal_status']=['satisfied']
    elif change=='missing_status':value.pop('goal_status')
    else:value['subgoals']=['모델이 바꾼 계획']
    result=AppTaskService(compact_sender(value)).step(req)
    assert result.status=='unavailable' and result.action is None and result.completion is None
    assert result.subgoals==req.state.subgoals


@pytest.mark.parametrize('planned',[True,False])
def test_explicit_result_only_preserves_pending_or_empty_plan_without_memory_replay(planned):
    req=current_goal(planned=planned);req.planning_mode='result_only';before=req.model_dump(mode='json')
    send=compact_sender({'completion_checks':[check()]});memory=Mock()
    result=AppTaskService(send,memory).step(req,user_id=42,action_capabilities=ActionCapabilities(frozenset()))
    assert result.status=='confirm_result' and result.action is None
    assert result.subgoals==req.state.subgoals and result.active_subgoal==('g1' if planned else None)
    assert result.completion.checks[0].label=='목적 화면' and req.model_dump(mode='json')==before
    assert set(send.call_args.args[0]['generationConfig']['responseJsonSchema']['properties'])=={'completion_checks'}
    memory.recall.assert_not_called();send.assert_called_once()


def test_request_mode_defaults_compatibly_and_rejects_unknown_values():
    req=request();assert req.planning_mode=='next_action';body=req.model_dump(mode='json');body.pop('planning_mode')
    assert AppTaskRequest.model_validate(body).planning_mode=='next_action'
    body['planning_mode']='invented'
    with pytest.raises(ValidationError):AppTaskRequest.model_validate(body)


def test_menu_model_selects_one_gemini_call_with_identical_payload_and_action(monkeypatch):
    req = request()
    req.planning_mode, req.goal = 'goal_first', '검색 메뉴를 열어줘'
    monkeypatch.setenv('GEMINI_API_KEY', 'test-key')
    send = sender(goal_status='not_satisfied')
    gateway = Mock(side_effect=lambda model, key, payload, **kwargs: send(payload, **kwargs))
    monkeypatch.setattr('app.services.app_task.generate', gateway)
    answers = []
    for selected in ('', 'gemini-3.5-flash-lite'):
        monkeypatch.setenv('APP_TASK_MENU_MODEL', selected)
        answers.append(AppTaskService().step(req))
    assert [call.args[0] for call in gateway.call_args_list] == ['gemini-3.8-flash', 'gemini-3.5-flash-lite']
    assert gateway.call_args_list[0].args[2] == gateway.call_args_list[1].args[2]
    assert all(r.diagnostic['called'] is True and r.status == 'proposed' and r.requires_confirmation
               and r.action.target_id == req.screen.nodes[0].id for r in answers)


@pytest.mark.parametrize('case', ['heading', 'ocr', 'tab', 'toggle', 'edit', 'value', 'selected', 'keyboard',
                                  'deselect', 'result_only', 'verified_plan', 'next_action', 'bound', 'recovery',
                                  'pending_plan', 'revision', 'attempt', 'path_prefix'])
def test_potential_evidence_and_other_modes_use_main_gemini(monkeypatch, case):
    monkeypatch.setenv('APP_TASK_MENU_MODEL', 'gemini-3.5-flash-lite')
    req = request()
    req.planning_mode, req.goal = 'goal_first', '메뉴 화면을 열어줘'
    node = req.screen.nodes[0]
    node_fields = {'heading': {'role': 'text', 'clickable': False}, 'ocr': {'source': 'ocr'},
                   'tab': {'role': 'tab'}, 'toggle': {'role': 'toggle', 'checked': False},
                   'edit': {'editable': True}, 'value': {'has_value': True}, 'selected': {'selected': True}}
    if case in node_fields:
        req.screen.nodes.append(node.model_copy(update={'id': 'second', **node_fields[case]}))
    elif case == 'keyboard': req.screen.keyboard_visible = True
    elif case == 'deselect': req.goal = '선택을 해제하고 메뉴를 열어줘'
    elif case == 'verified_plan': req.state.subgoals = [TaskSubgoal(id='g0', title='메뉴 열기', status='verified')]
    elif case == 'pending_plan': req.state.subgoals = [TaskSubgoal(id='g0', title='메뉴 열기')]
    elif case == 'revision': req.state.revision = 1
    elif case == 'path_prefix': req.path_prefix = ['button']
    elif case == 'attempt': req.state.attempts = [TaskAttempt(fingerprint=req.screen.fingerprint, tool='tap',
                                                             target=node.id, outcome='failed')]
    elif case == 'bound': req.state.completion = TaskCompletion(checks=[TaskResultCheck(package=req.screen.package, label='목적 화면')])
    elif case == 'recovery': req = recovering(req)
    else: req.planning_mode = case
    send = sender(goal_status='not_satisfied')
    result = AppTaskService(send).step(req)
    send.assert_called_once()
    assert result.diagnostic['model'] == 'gemini-3.8-flash'


@pytest.mark.parametrize('with_evidence', [False, True])
def test_named_menu_model_with_authenticated_recall(monkeypatch, with_evidence):
    monkeypatch.setenv('APP_TASK_MENU_MODEL', 'gemini-3.5-flash-lite')
    req = request()
    req.planning_mode, req.goal = 'goal_first', '메뉴를 열어줘'
    memory = Mock()
    memory.recall.return_value = TaskMemoryResponse(request_id=req.request_id, observation_id=req.screen.observation_id,
        fingerprint=req.screen.fingerprint, evidence=[{'target_id': req.screen.nodes[0].id}] if with_evidence else [])
    send = sender(goal_status='not_satisfied')
    result = AppTaskService(send, memory).step(req, user_id='test-user')
    send.assert_called_once()
    assert result.diagnostic['model'] == ('gemini-3.8-flash' if with_evidence else 'gemini-3.5-flash-lite')
