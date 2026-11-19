import json
import time
from unittest.mock import Mock
import httpx
import pytest
from pydantic import ValidationError
from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.api.v1.endpoints.app_guide import build_router
from app.schemas.app_guide import AppGuideRequest
from app.services.app_guide import AppGuideService


def request(package='com.example.anyapp', **screen_changes):
    screen = dict(status='public', package_name=package, observation_id='observation',
        captured_at_ms=int(time.time()*1000), width=1080, height=2400,
        nodes=[dict(node_id='search', resource_id=package+':id/search', label='검색',
            bounds=[10,20,400,120], clickable=True)])
    screen.update(screen_changes)
    return AppGuideRequest(request_id='r', goal='검색 화면 열어줘', screen=screen)


def sender(choice='c0', **extra):
    return Mock(return_value=httpx.Response(200, json={'candidates': [{'finishReason': 'STOP',
        'content': {'parts': [{'text': json.dumps({'choice': choice, **extra})}]}}]}))


@pytest.mark.parametrize('package', ['com.google.android.youtube','com.spotify.music','org.mozilla.firefox','com.example.brandnew'])
def test_any_app_can_receive_a_grounded_manual_guide(package):
    send = sender()
    result = AppGuideService(send).step(request(package))
    assert result.status == 'guide' and result.manual_only
    assert result.target_node_id == 'search'
    assert result.target_bounds == (10,20,400,120)
    assert package in send.call_args.args[0]['contents'][0]['parts'][0]['text']


@pytest.mark.parametrize('choice,extra', [('invented',{}), ('c0',{'coordinates':[100,200]}), ('c0',{'done':True})])
def test_model_cannot_invent_targets_coordinates_or_completion(choice,extra):
    result = AppGuideService(sender(choice,**extra)).step(request())
    assert result.status == 'unavailable' and result.target_node_id is None


def test_model_abstention_never_claims_the_task_is_completed():
    result = AppGuideService(sender('none')).step(request())
    assert result.status == 'needs_input' and result.target_node_id is None


@pytest.mark.parametrize('changes', [dict(status='sensitive',nodes=[]), dict(status='outside_app',nodes=[]),
    dict(captured_at_ms=1), dict(captured_at_ms=int(time.time()*1000)+60000)])
def test_private_or_stale_screens_do_not_reach_the_model(changes):
    send = sender()
    result = AppGuideService(send).step(request(**changes))
    assert result.target_node_id is None
    send.assert_not_called()


@pytest.mark.parametrize('label,resource', [('보내기','send'),('최종제출','submit'),('결제하기','payment'),
    ('홍길동','sender'),('010-1234-5678','title')])
def test_sensitive_labels_and_final_actions_are_not_model_candidates(label,resource):
    req=request();req.screen.nodes[0].label=label;req.screen.nodes[0].resource_id=resource
    send=sender();result=AppGuideService(send).step(req)
    send.assert_not_called();assert result.target_node_id is None


def test_outage_preserves_cause_without_echoing_private_provider_text():
    send=Mock(return_value=httpx.Response(503,json={'error':{'status':'UNAVAILABLE','message':'high demand PRIVATE'}}))
    result=AppGuideService(send).step(request())
    assert result.status=='unavailable' and result.diagnostic['http_status']==503
    assert result.diagnostic['provider_cause']=='model_high_demand'
    assert 'PRIVATE' not in str(result)


def test_history_is_bounded():
    body=request().model_dump();body['previous_targets']=['x'*81]
    with pytest.raises(ValidationError):AppGuideRequest.model_validate(body)


def test_route_is_discoverable_and_uses_the_authentication_dependency():
    principal=Mock(return_value=object());service=AppGuideService(sender())
    app=FastAPI();app.include_router(build_router(service,lambda:principal()),prefix='/api/v1')
    with TestClient(app) as client:
        assert '/api/v1/agent/app-guide/step' in client.get('/openapi.json').json()['paths']
        response=client.post('/api/v1/agent/app-guide/step',json=request().model_dump(mode='json'))
    assert response.status_code==200 and response.json()['manual_only']
    principal.assert_called_once()


def test_real_app_rejects_an_unauthenticated_guide_request():
    from app.main import create_app
    with TestClient(create_app('sqlite+pysqlite:///:memory:')) as client:
        response=client.post('/api/v1/agent/app-guide/step',json=request().model_dump(mode='json'))
    assert response.status_code==401
