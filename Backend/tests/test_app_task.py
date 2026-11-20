"""Multi-app tasks: grounded proposals, immutable progress, and bounded recovery."""
import json
import time
from unittest.mock import Mock

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.v1.endpoints.app_guide import build_router
from app.schemas.app_task import AppTaskRequest, TaskSubgoal, TaskAttempt, TaskApp
from app.services.app_task import AppTaskService


def request(package='com.example.reader', **changes):
    screen = dict(status='public', package=package, observation_id='o1',
                  captured_at_ms=int(time.time()*1000), fingerprint='a'*64,
                  width=1080, height=2400, nodes=[dict(id='node_0_1', label='검색',
                  resource=package+':id/search', role='button', clickable=True,
                  bounds=[10, 20, 400, 120])])
    screen.update(changes)
    return AppTaskRequest(request_id='r1', goal='검색을 열고 목록을 확인해 줘',
                          state=dict(task_id='t1'), screen=screen)


def sender(**changes):
    answer=dict(subgoals=['검색 열기', '목록 확인'], tool='tap', target='c0',
                expected_label='검색어 입력', app='none',expected_checked='unknown',expected_selected='unknown',
                advances_subgoal=False,completion_checks=[dict(target='none',label='목록',app='none',checked='unknown',selected='unknown')])
    answer.update(changes)
    return Mock(return_value=httpx.Response(200, json={'candidates':[
        dict(finishReason='STOP', content=dict(parts=[dict(text=json.dumps(answer))]))]}))


@pytest.mark.parametrize('package', ['com.spotify.music', 'org.mozilla.firefox',
                                   'com.google.android.youtube', 'org.example.newapp'])
def test_same_protocol_grounds_actions_in_any_app(package):
    send=sender(); result=AppTaskService(send).step(request(package))
    assert result.status=='proposed' and result.requires_confirmation
    assert result.action.target_id=='node_0_1' and result.action.bounds==(10,20,400,120)
    assert result.action.package==package
    assert [g.status for g in result.subgoals]==['pending', 'pending']
    prompt=send.call_args.args[0]['contents'][0]['parts'][0]['text']
    assert package in prompt and 'bounds' not in prompt
    assert 'node_0_1' not in prompt
    assert send.call_args.kwargs['timeout']==8


def test_model_cannot_rewrite_existing_plan_or_progress():
    req=request(); req.state.subgoals=[TaskSubgoal(id='g0',title='검색 열기',status='verified'),
                                      TaskSubgoal(id='g1',title='목록 확인')]
    good=AppTaskService(sender()).step(req)
    assert good.active_subgoal=='g1' and good.subgoals[0].status=='verified'
    bad=AppTaskService(sender(subgoals=['다른 계획'])).step(req)
    assert bad.status=='unavailable' and bad.action is None
    assert bad.subgoals==req.state.subgoals


def test_verified_plan_with_supported_completion_asks_user_without_another_model_call():
    req=request(); req.state.subgoals=[TaskSubgoal(id='g0',title='검색 열기',status='verified')]
    req.state.completion=AppTaskService(sender()).step(request()).completion
    send=sender(); result=AppTaskService(send).step(req)
    send.assert_not_called(); assert result.status=='confirm_result' and result.action is None
    assert result.completion==req.state.completion and result.diagnostic['called'] is False


@pytest.mark.parametrize('tool,target,extra', [
    ('tap','invented',{}), ('set_text','c0',{}), ('scroll_forward','c0',{}),
    ('shell','c0',{}), ('open_app','none',{'app':'com.other.app'}),
    ('tap','c0',{'coordinates':[1,2]}), ('tap','c0',{'completed':True})])
def test_unobserved_targets_tools_coordinates_and_completion_are_rejected(tool,target,extra):
    result=AppTaskService(sender(tool=tool,target=target,**extra)).step(request())
    assert result.status=='unavailable' and result.action is None


@pytest.mark.parametrize('tool,capability,role,kind', [
    ('set_text','editable','text_field','input_present'),
    ('scroll_forward','scrollable','list','screen_changed'),
    ('scroll_backward','scrollable','list','screen_changed'),
    ('tap','clickable','toggle','selection_changed')])
def test_common_tools_require_native_capability(tool,capability,role,kind):
    req=request(); n=req.screen.nodes[0]; n.role=role; setattr(n,capability,True)
    result=AppTaskService(sender(tool=tool,expected_label='')).step(req)
    assert result.status=='proposed' and result.action.expectation.kind==kind
    assert result.action.advances_subgoal is False


def test_app_open_only_uses_one_explicitly_selected_package():
    req=request(); req.apps=[TaskApp(package='org.example.selected',label='선택한 앱')]
    result=AppTaskService(sender(tool='open_app',target='none',app='org.example.selected')).step(req)
    assert result.action.package=='org.example.selected'
    assert result.action.target_id is None and result.action.bounds is None


def test_recovery_history_uses_model_ids_and_blocks_two_failed_repeats():
    req=request(); req.state.attempts=[TaskAttempt(fingerprint='a'*64,tool='tap',
                    target='node_0_1',outcome='failed')]*2
    send=sender(); result=AppTaskService(send).step(req)
    assert result.status=='unavailable' and result.action is None
    prompt=send.call_args.args[0]['contents'][0]['parts'][0]['text']
    assert 'node_0_1' not in prompt and '"target": "c0"' in prompt


@pytest.mark.parametrize('changes', [dict(status='protected',nodes=[]),
    dict(status='empty',nodes=[]), dict(captured_at_ms=1),
    dict(captured_at_ms=int(time.time()*1000)+60000)])
def test_protected_and_stale_observations_never_call_model(changes):
    send=sender(); assert AppTaskService(send).step(request(**changes)).action is None
    send.assert_not_called()


@pytest.mark.parametrize('label,resource', [('비밀번호','password'),('보내기','send'),
    ('동의','agree'), ('허용','permission'), ('승인','approve'),
    ('010-1234-5678','title'), ('홍길동','sender'), ('','icon')])
def test_private_and_risky_controls_are_not_candidates(label,resource):
    req=request(); req.screen.nodes[0].label=label; req.screen.nodes[0].resource=resource
    send=sender(); assert AppTaskService(send).step(req).action is None
    send.assert_not_called()


def test_outage_and_timeout_diagnostics_do_not_echo_provider_content():
    send=Mock(return_value=httpx.Response(503,json={'error':{'status':'UNAVAILABLE',
             'message':'high demand PRIVATE'}}))
    result=AppTaskService(send).step(request())
    assert result.status=='unavailable' and result.diagnostic['http_status']==503
    assert 'PRIVATE' not in str(result)
    result=AppTaskService(Mock(side_effect=httpx.ReadTimeout('PRIVATE'))).step(request())
    assert result.status=='unavailable' and 'PRIVATE' not in str(result)


@pytest.mark.parametrize('changes', [dict(nodes=[dict(id='x',bounds=[-1,2,3,4])]),
    dict(status='protected'), dict(nodes=[dict(id='x',bounds=[1,2,3,4],input_value='secret')])])
def test_screen_contract_rejects_unbounded_or_private_payloads(changes):
    with pytest.raises(ValidationError):request(**changes)


def test_new_route_is_authenticated_and_preserves_v1():
    principal=Mock(return_value=object()); app=FastAPI()
    app.include_router(build_router(Mock(),lambda:principal(),AppTaskService(sender())),prefix='/api/v1')
    with TestClient(app) as client:
        paths=client.get('/openapi.json').json()['paths']
        assert '/api/v1/agent/app-guide/task' in paths and '/api/v1/agent/app-guide/step' in paths
        response=client.post('/api/v1/agent/app-guide/task',json=request().model_dump(mode='json'))
    assert response.status_code==200 and response.json()['requires_confirmation']
    principal.assert_called_once()


def test_real_app_rejects_unauthenticated_task():
    from app.main import create_app
    with TestClient(create_app('sqlite+pysqlite:///:memory:')) as client:
        response=client.post('/api/v1/agent/app-guide/task',json=request().model_dump(mode='json'))
    assert response.status_code==401


@pytest.mark.parametrize('package',['org.example.reader','com.spotify.music','org.mozilla.firefox'])
def test_final_result_is_a_separate_observable_contract_in_every_app(package):
    req=request(package);result=AppTaskService(sender()).step(req)
    assert result.completion.checks[0].package==package
    assert result.completion.checks[0].label=='목록'
    assert result.action.expectation.label=='검색어 입력'
    assert result.action.advances_subgoal is False


def test_intermediate_effect_never_implicitly_completes_a_subgoal():
    req=request()
    for tool in ['tap','scroll_forward']:
        req.screen.nodes[0].scrollable=True
        result=AppTaskService(sender(tool=tool)).step(req)
        assert result.action.advances_subgoal is False
    result=AppTaskService(sender(advances_subgoal=True)).step(req)
    assert result.action.advances_subgoal is True
    result=AppTaskService(sender(expected_label='',advances_subgoal=True)).step(req)
    assert result.action.advances_subgoal is False


def test_desired_native_state_is_explicit_and_bound_to_the_observed_control():
    req=request();req.screen.nodes[0].role='toggle';req.screen.nodes[0].checked=True
    send=sender(expected_label='',expected_checked='false',advances_subgoal=True,
        completion_checks=[dict(target='c0',label='',app='none',checked='false',selected='unknown')])
    result=AppTaskService(send).step(req)
    assert result.action.expectation.checked is False and result.action.advances_subgoal
    check=result.completion.checks[0]
    assert check.resource==req.screen.nodes[0].resource and check.checked is False
    req.screen.nodes[0].role='button'
    result=AppTaskService(send).step(req)
    assert result.status=='unavailable' and result.completion is None and result.action is None


def test_bound_result_survives_planning_and_cannot_be_rewritten():
    req=request();initial=AppTaskService(sender()).step(req)
    req.state.subgoals=initial.subgoals;req.state.completion=initial.completion
    result=AppTaskService(sender(completion_checks=[])).step(req)
    assert result.completion==initial.completion and result.status=='proposed'
    changed=AppTaskService(sender()).step(req)
    assert changed.status=='unavailable' and changed.completion==initial.completion


@pytest.mark.parametrize('check',[
    dict(target='none',label='',app='none',checked='unknown',selected='unknown'),
    dict(target='c9',label='목록',app='none',checked='unknown',selected='unknown'),
    dict(target='none',label='목록',app='org.unselected.app',checked='unknown',selected='unknown'),
    dict(target='none',label='PRIVATE@example.com',app='none',checked='unknown',selected='unknown'),
    dict(target='none',label='목록',app='none',checked='true',selected='unknown')])
def test_unobservable_unbound_or_private_final_results_are_rejected(check):
    result=AppTaskService(sender(completion_checks=[check])).step(request())
    assert result.status=='unavailable' and result.completion is None and result.action is None


def test_unobservable_result_remains_manual_and_confirm_is_not_technical_success():
    result=AppTaskService(sender(tool='confirm',completion_checks=[])).step(request())
    assert result.status=='confirm_result' and result.completion is None and result.action is None


def test_completion_needing_a_destination_label_or_state_change_is_kept():
    req=request()
    future=AppTaskService(sender(completion_checks=[dict(target='none',label='목록',app='none',checked='unknown',selected='unknown')])).step(req)
    assert future.completion.checks[0].label=='목록' and 'completion_rejected' not in future.diagnostic
    req.screen.nodes[0].role='tab';req.screen.nodes[0].selected=False
    change=AppTaskService(sender(expected_selected='true',completion_checks=[dict(target='c0',label='검색',app='none',checked='unknown',selected='true')])).step(req)
    assert change.completion.checks[0].selected is True and 'completion_rejected' not in change.diagnostic


@pytest.mark.parametrize('target',['c0','none'])
def test_visible_navigation_menu_is_not_final_result_but_next_action_survives(target):
    req=request();req.goal='검색 열어줘'
    result=AppTaskService(sender(completion_checks=[dict(target=target,label='검색',app='none',checked='unknown',selected='unknown')])).step(req)
    assert result.status=='proposed' and result.action.target_id=='node_0_1'
    assert result.completion is None
    assert result.diagnostic['completion_grounding']=='deferred_actionable_proof'


def test_one_unsafe_goal_clause_defers_whole_conjunction_instead_of_weakening_it():
    checks=[dict(target='none',label='목록',app='none',checked='unknown',selected='unknown'),
            dict(target='c0',label='검색',app='none',checked='unknown',selected='unknown')]
    result=AppTaskService(sender(completion_checks=checks)).step(request())
    assert result.status=='proposed' and result.action is not None and result.completion is None


def test_not_yet_observed_destination_can_bind_and_already_open_destination_text_is_valid():
    check=dict(target='none',label='디스플레이 화면',app='none',checked='unknown',selected='unknown')
    req=request();req.goal='디스플레이 화면 열어줘'
    result=AppTaskService(sender(completion_checks=[check])).step(req)
    assert result.completion.checks[0].label=='디스플레이 화면'
    req.screen.nodes[0].label='디스플레이 화면';req.screen.nodes[0].role='text';req.screen.nodes[0].clickable=False
    check['target']='c0'
    result=AppTaskService(sender(tool='confirm',completion_checks=[check])).step(req)
    assert result.status=='confirm_result' and result.completion.checks[0].role=='text'


@pytest.mark.parametrize('selected',['true','false'])
def test_navigation_tab_requires_selected_state_in_the_requested_direction(selected):
    req=request();req.goal='기록 탭 열어줘';req.screen.nodes[0].label='기록';req.screen.nodes[0].role='tab'
    check=dict(target='c0',label='기록',app='none',checked='unknown',selected=selected)
    result=AppTaskService(sender(completion_checks=[check])).step(req)
    assert result.status=='proposed' and result.action is not None
    assert (result.completion is not None)==(selected=='true')


def test_unselected_tab_cannot_prove_a_selection_paraphrase_without_open_verb():
    req=request();req.goal='기록 탭 선택해줘';req.screen.nodes[0].label='기록';req.screen.nodes[0].role='tab'
    check=dict(target='c0',label='기록',app='none',checked='unknown',selected='false')
    result=AppTaskService(sender(completion_checks=[check])).step(req)
    assert result.status=='proposed' and result.action is not None and result.completion is None


def test_turning_off_a_native_toggle_is_valid_even_with_navigation_in_the_goal():
    req=request();req.goal='알림 화면 열고 알림 꺼줘';req.screen.nodes[0].label='알림';req.screen.nodes[0].role='toggle'
    check=dict(target='c0',label='알림',app='none',checked='false',selected='false')
    result=AppTaskService(sender(completion_checks=[check])).step(req)
    assert result.status=='proposed' and result.completion.checks[0].checked is False


@pytest.mark.parametrize('goal,allowed',[
    ('검색창 열어줘',True),('검색창 보여줘',True),('검색창 띄워줘',True),('show search',True),
    ('검색 결과 열어줘',False),('검색어 입력해줘',False),('검색 열고 목록 확인',False)])
@pytest.mark.parametrize('target',['c0','none'])
def test_search_input_exposure_only_proves_explicit_search_open_goal(goal,allowed,target):
    req=request();req.goal=goal;req.screen.nodes[0].label='검색어 입력';req.screen.nodes[0].role='text_field';req.screen.nodes[0].editable=True
    check=dict(target=target,label='검색어 입력',app='none',checked='unknown',selected='unknown')
    result=AppTaskService(sender(tool='set_text',completion_checks=[check])).step(req)
    assert result.status=='proposed' and result.action is not None
    assert (result.completion is not None)==allowed


def finished_plan_request():
    req=request();req.goal='목록 화면 열어줘'
    req.state.subgoals=[TaskSubgoal(id='g0',title='목록 열기',status='verified')]
    req.screen.nodes[0].label='목록';req.screen.nodes[0].role='text';req.screen.nodes[0].clickable=False
    return req


def result_sender(**changes):
    return sender(subgoals=['목록 열기'],tool='confirm',target='none',advances_subgoal=False,**changes)


def test_verified_steps_without_completion_discover_current_final_evidence_without_an_action():
    req=finished_plan_request();send=result_sender()
    result=AppTaskService(send).step(req)
    assert result.status=='confirm_result' and result.action is None and result.active_subgoal is None
    assert result.subgoals==req.state.subgoals
    assert result.completion.checks[0].label=='목록'
    diagnostic=result.diagnostic
    assert diagnostic['called'] and diagnostic['planning_mode']=='result_only'
    assert diagnostic['reason_code']=='result_evidence_candidate'
    payload=send.call_args.args[0]
    assert payload['generationConfig']['responseJsonSchema']['required']==['completion_checks']
    assert set(payload['generationConfig']['responseJsonSchema']['properties'])=={'completion_checks'}
    assert payload['generationConfig']['maxOutputTokens']==2048
    assert 'RESULT EVIDENCE DISCOVERY ONLY' in payload['contents'][0]['parts'][0]['text']


@pytest.mark.parametrize('case',['unseen','ambiguous','actionable','unsatisfied_state'])
def test_result_discovery_never_binds_unseen_ambiguous_or_unsatisfied_evidence(case):
    req=finished_plan_request()
    checks=[dict(target='none',label='목록',app='none',checked='unknown',selected='unknown')]
    if case=='unseen':req.screen.nodes[0].label='이전 화면'
    elif case=='ambiguous':req.screen.nodes.append(req.screen.nodes[0].model_copy(update={'id':'other'}))
    elif case=='actionable':req.screen.nodes[0].role='button';req.screen.nodes[0].clickable=True
    else:
        req.goal='목록 탭 열어줘';req.screen.nodes[0].role='tab';req.screen.nodes[0].selected=False
        checks[0].update(target='c0',selected='true')
    result=AppTaskService(result_sender(completion_checks=checks)).step(req)
    assert result.status=='confirm_result' and result.action is None and result.completion is None
    assert result.subgoals==req.state.subgoals
    assert result.diagnostic['reason_code']=='result_evidence_unavailable'


def test_result_discovery_keeps_the_whole_final_conjunction_or_none_of_it():
    req=finished_plan_request()
    checks=[dict(target='c0',label='목록',app='none',checked='unknown',selected='unknown'),
        dict(target='none',label='미관찰 결과',app='none',checked='unknown',selected='unknown')]
    result=AppTaskService(result_sender(completion_checks=checks)).step(req)
    assert result.completion is None and result.action is None
    assert result.diagnostic['completion_grounding']=='deferred_unobserved_result'


@pytest.mark.parametrize('tool',['tap','back','open_app','set_text'])
def test_result_discovery_locally_rejects_actions_even_if_the_model_ignores_its_schema(tool):
    req=finished_plan_request();send=sender(subgoals=['목록 열기'],tool=tool)
    result=AppTaskService(send).step(req)
    assert result.status=='confirm_result' and result.action is None and result.completion is None
    assert result.subgoals==req.state.subgoals
    assert result.diagnostic['reason_code']=='result_only_action_forbidden'
    assert result.diagnostic['failure_stage']=='plan_validation'


@pytest.mark.parametrize('changes,code',[
    (dict(subgoals=['다른 목표']), 'changed_existing_plan'),
    (dict(advances_subgoal=True), 'result_only_progress_forbidden')])
def test_result_discovery_cannot_replace_the_goal_plan_or_advance_progress(changes,code):
    req=finished_plan_request();options=dict(subgoals=['목록 열기'],tool='confirm');options.update(changes)
    result=AppTaskService(sender(**options)).step(req)
    assert result.status=='confirm_result' and result.action is None and result.completion is None
    assert result.subgoals==req.state.subgoals and result.diagnostic['reason_code']==code


def test_result_discovery_provider_failure_retains_manual_result_check_and_truthful_call_metadata():
    req=finished_plan_request()
    result=AppTaskService(Mock(side_effect=httpx.ReadTimeout('PRIVATE@example.com API_SECRET'))).step(req)
    assert result.status=='confirm_result' and result.action is None and result.completion is None
    assert result.diagnostic['called'] is True
    assert result.diagnostic['failure_kind']=='provider' and result.diagnostic['reason_code']=='provider_timeout'
    assert 'PRIVATE' not in str(result) and 'API_SECRET' not in str(result)


def test_contract_error_diagnostic_has_only_bounded_stage_field_and_type_metadata():
    req=request();req.screen.nodes[0].role='button'
    check=dict(target='c0',label='',app='none',checked='true',selected='unknown')
    result=AppTaskService(sender(completion_checks=[check])).step(req)
    assert result.status=='proposed' and result.action is not None and result.completion is None
    diagnostic=result.diagnostic
    assert diagnostic['called'] and diagnostic['completion_deferred_reason']=='completion_contract_invalid'
    assert diagnostic['completion_grounding']=='deferred_invalid_contract'
    assert 'failure_kind' not in diagnostic
    assert diagnostic['validation_errors']==[{'field':'checks.index.checked','type':'value_error'}]
    assert 'input' not in json.dumps(diagnostic) and 'ctx' not in json.dumps(diagnostic)


@pytest.mark.parametrize('body,stage',[
    ('NOT JSON PRIVATE@example.com','provider_response'),
    (json.dumps({'candidates':[dict(finishReason='STOP',content=dict(parts=[dict(text='PRIVATE NOT JSON')]))]}),'model_output')])
def test_invalid_json_diagnostic_reports_stage_without_raw_model_or_provider_text(body,stage):
    result=AppTaskService(Mock(return_value=httpx.Response(200,content=body))).step(request())
    assert result.status=='unavailable' and result.diagnostic['called']
    assert result.diagnostic['failure_kind']=='parse' and result.diagnostic['failure_stage']==stage
    assert result.diagnostic['reason_code']=='invalid_json'
    assert 'PRIVATE' not in str(result)


def test_unknown_exception_text_is_never_used_as_a_diagnostic_code():
    result=AppTaskService(Mock(side_effect=ValueError('PRIVATE@example.com SECRET'))).step(request())
    assert result.status=='unavailable' and result.diagnostic['called']
    assert result.diagnostic['reason_code']=='plan_validation_failed'
    assert 'PRIVATE' not in str(result) and 'SECRET' not in str(result)


def compact_sender(answer):
    return Mock(return_value=httpx.Response(200,json={'candidates':[
        dict(finishReason='STOP',content=dict(parts=[dict(text=json.dumps(answer))]))]}))


def test_result_only_compact_response_reuses_server_plan_without_action_or_extra_call():
    req=finished_plan_request();send=compact_sender({'completion_checks':[
        dict(target='c0',label='',app='none',checked='unknown',selected='unknown')]})
    result=AppTaskService(send).step(req)
    assert result.status=='confirm_result' and result.action is None and result.subgoals==req.state.subgoals
    assert result.completion.checks[0].label=='목록';send.assert_called_once()


def test_retained_plan_schema_and_response_do_not_repeat_or_replace_subgoals():
    req=request();req.state.subgoals=[TaskSubgoal(id='g0',title='검색 열기')]
    answer=dict(tool='tap',target='c0',expected_label='검색어 입력',app='none',expected_checked='unknown',
        expected_selected='unknown',advances_subgoal=False,completion_checks=[])
    send=compact_sender(answer);result=AppTaskService(send).step(req)
    schema=send.call_args.args[0]['generationConfig']['responseJsonSchema']
    assert 'subgoals' not in schema['properties'] and 'subgoals' not in schema['required']
    assert result.status=='proposed' and result.subgoals==req.state.subgoals;send.assert_called_once()


def test_one_invalid_native_clause_defers_the_entire_candidate_but_keeps_valid_action():
    checks=[dict(target='none',label='목록',app='none',checked='unknown',selected='unknown'),
        dict(target='c0',label='',app='none',checked='true',selected='unknown')]
    send=sender(completion_checks=checks);result=AppTaskService(send).step(request())
    assert result.status=='proposed' and result.action is not None and result.completion is None
    assert result.diagnostic['completion_grounding']=='deferred_invalid_contract';send.assert_called_once()


def test_deferred_completion_never_repairs_invalid_action_expectation():
    send=sender(expected_checked='true',completion_checks=[dict(target='c0',label='',app='none',checked='true',selected='unknown')])
    result=AppTaskService(send).step(request())
    assert result.status=='unavailable' and result.action is None and result.completion is None
    assert result.diagnostic['reason_code']=='ungrounded_expected_state';send.assert_called_once()


@pytest.mark.parametrize('state,called',[('observed',True),('not_started',False),('unknown',None)])
def test_production_boundary_failure_keeps_truthful_attempt_state_without_retry(monkeypatch,state,called):
    from app.services.app_task_provider import ProviderError
    import app.services.app_task as module
    monkeypatch.setenv('GEMINI_API_KEY','local-test-key')
    generate=Mock(side_effect=ProviderError('provider_deadline_exceeded',state,9000));monkeypatch.setattr(module,'generate',generate)
    result=AppTaskService().step(request())
    assert result.status=='unavailable' and result.action is None
    assert result.diagnostic['called'] is called and result.diagnostic['reason_code']=='provider_deadline_exceeded'
    assert result.diagnostic['provider_attempt_limit']==1;generate.assert_called_once()
    assert 0<generate.call_args.kwargs['timeout']<=9


def test_expired_enclosing_budget_never_starts_production_provider(monkeypatch):
    from ai_agent.runtime.budget import deadline
    import app.services.app_task as module
    monkeypatch.setenv('GEMINI_API_KEY','local-test-key');generate=Mock();monkeypatch.setattr(module,'generate',generate)
    token=deadline.set(time.monotonic()-1)
    try:result=AppTaskService().step(request())
    finally:deadline.reset(token)
    assert result.diagnostic['called'] is False and result.diagnostic['reason_code']=='provider_deadline_exceeded'
    generate.assert_not_called()
