from types import SimpleNamespace
from time import monotonic
from unittest.mock import Mock

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from ai_agent.runtime.budget import deadline, remaining
from app.api.v1.endpoints.app_guide import build_router, task_request_budget
from tests.test_app_task import request, sender
from app.services.app_task import AppTaskService


def test_shorter_client_budget_is_inherited_and_reset_without_extending_outer_budget():
    original=deadline.set(monotonic()+.3)
    try:
        outer=deadline.get()
        with task_request_budget(SimpleNamespace(headers={'X-Click-App-Task-Budget-Ms':'9000'})):
            assert deadline.get()==outer
        assert deadline.get()==outer
    finally:deadline.reset(original)
    assert deadline.get() is None
    with task_request_budget(SimpleNamespace(headers={'X-Click-App-Task-Budget-Ms':'500'})):
        assert 0<remaining(9)<=.5
    assert deadline.get() is None


@pytest.mark.parametrize('value',['0','99','10001','PRIVATE','1.5'])
def test_invalid_task_budget_is_rejected_before_service_work(value):
    tasks=Mock();app=FastAPI();app.include_router(build_router(Mock(),lambda:object(),tasks),prefix='/api/v1')
    with TestClient(app) as client:
        response=client.post('/api/v1/agent/app-guide/task',json=request().model_dump(mode='json'),
            headers={'X-Click-App-Task-Budget-Ms':value})
    assert response.status_code==400 and response.json()['detail']=='INVALID_APP_TASK_BUDGET'
    tasks.step.assert_not_called();assert value not in response.text or value in {'0','99','10001'}


def test_route_passes_short_remaining_budget_and_keeps_old_clients_compatible():
    observed=[];send=sender()
    def provider(payload,timeout):
        observed.append((timeout,remaining(9)))
        return send(payload,timeout=timeout)
    app=FastAPI();app.include_router(build_router(Mock(),lambda:object(),AppTaskService(provider)),prefix='/api/v1')
    with TestClient(app) as client:
        for headers in ({'X-Click-App-Task-Budget-Ms':'1500'},{}):
            assert client.post('/api/v1/agent/app-guide/task',json=request().model_dump(mode='json'),headers=headers).status_code==200
    assert 0<observed[0][0]<=1.5 and 0<observed[0][1]<=1.5
    assert observed[1][0]==8
