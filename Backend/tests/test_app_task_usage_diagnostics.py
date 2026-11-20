"""Usage is measured provider metadata, never a guessed bill or zero on timeout."""
import json

import httpx
import pytest

from app.services.app_task import AppTaskService
from app.services.app_task_provider import ProviderError
from tests.test_app_task_recovery_capabilities import request, sender


@pytest.fixture(autouse=True)
def controlled(monkeypatch):
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL','gemini-3.8-flash')
    monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    monkeypatch.setattr('app.services.app_task.generate',lambda *_a,**_k:pytest.fail('Live provider forbidden'))


def with_usage(usage,*,bad_plan=False):
    base=sender()
    def send(payload,**kwargs):
        body=base(payload,**kwargs).json();body['usageMetadata']=usage
        if bad_plan:body['candidates'][0]['content']['parts'][0]['text']='invalid model JSON'
        return httpx.Response(200,json=body)
    return send


def test_usage_is_recorded_for_observed_response_including_failed_plan():
    usage=dict(promptTokenCount=21,candidatesTokenCount=8,thoughtsTokenCount=0,totalTokenCount=29)
    for bad in (False,True):
        result=AppTaskService(sender=with_usage(usage,bad_plan=bad)).step(request())
        assert result.diagnostic['called'] is True
        assert {key:result.diagnostic[key] for key in ('input_tokens','output_tokens','thinking_tokens','total_tokens')}==dict(
            input_tokens=21,output_tokens=8,thinking_tokens=0,total_tokens=29)
        assert (result.action is None)==bad


@pytest.mark.parametrize('value',[True,False,-1,1000001,1.5,'20',None,{}])
def test_malformed_optional_usage_never_changes_the_valid_plan(value):
    result=AppTaskService(sender=with_usage(dict(promptTokenCount=value,candidatesTokenCount=8))).step(request())
    assert result.status=='proposed' and 'input_tokens' not in result.diagnostic
    assert result.diagnostic['output_tokens']==8
    assert 'thinking_tokens' not in result.diagnostic and 'total_tokens' not in result.diagnostic


@pytest.mark.parametrize('usage',[None,[],{},'unknown'])
def test_missing_or_unshaped_usage_is_unknown_not_zero(usage):
    result=AppTaskService(sender=with_usage(usage)).step(request())
    assert result.status=='proposed'
    assert not any(key in result.diagnostic for key in ('input_tokens','output_tokens','thinking_tokens','total_tokens'))


def test_provider_timeout_has_no_invented_token_usage():
    def timeout(*_a,**_k):raise ProviderError('provider_deadline_exceeded','observed')
    result=AppTaskService(sender=timeout).step(request())
    assert result.diagnostic['called'] is True and result.status=='unavailable'
    assert not any(key in result.diagnostic for key in ('input_tokens','output_tokens','thinking_tokens','total_tokens'))
