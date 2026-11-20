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
                expected_label='검색어 입력', app='none')
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


def test_verified_plan_asks_user_for_final_result_without_model():
    req=request(); req.state.subgoals=[TaskSubgoal(id='g0',title='검색 열기',status='verified')]
    send=sender(); result=AppTaskService(send).step(req)
    send.assert_not_called(); assert result.status=='confirm_result' and result.action is None


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
    assert result.action.advances_subgoal==(kind!='screen_changed')


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
