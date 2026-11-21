"""Observed transitions guide planning; they grant neither tools nor completion."""
import pytest
from pydantic import ValidationError

from app.schemas.app_task import AppTaskRequest
from app.services.app_task import AppTaskService
from app.services.app_task_capabilities import ActionCapabilities
from tests.test_app_task_recovery_capabilities import request, recovering, sender, reference


@pytest.mark.parametrize('policy,reason,attempt,valid', [
    ('mc_v0','accepted_action_unconfirmed',3,True),
    ('mc_v1','accepted_action_unconfirmed',3,True),
    ('mc_v2','accepted_action_unconfirmed',3,True),
    ('mc_v2','observed_transition_unconfirmed',2,True),
    ('mc_v2','observed_transition_unconfirmed',3,False),
    ('mc_v1','observed_transition_unconfirmed',1,True),
    ('mc_v1','observed_transition_unconfirmed',2,True),
    ('mc_v0','observed_transition_unconfirmed',1,False),
    ('mc_v1','observed_transition_unconfirmed',3,False),
])
def test_versioned_coordination_contract(policy,reason,attempt,valid):
    body=recovering(request()).model_dump(mode='json')
    body['recovery'].update(policy=policy,reason=reason,attempt=attempt)
    if valid:
        assert AppTaskRequest.model_validate(body).recovery.policy==policy
    else:
        with pytest.raises(ValidationError):AppTaskRequest.model_validate(body)


@pytest.mark.parametrize('package', ['org.example.reader','org.example.travel','org.example.utility'])
def test_observed_transition_context_reaches_actual_payload_without_claiming_progress_or_blocking_last_action(monkeypatch,package):
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL','gemini-3.8-flash')
    monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    monkeypatch.setattr('app.services.app_task.generate',lambda *_a,**_k:pytest.fail('Live provider forbidden'))
    body=request().model_dump(mode='json');body['screen']['package']=package
    body['apps'][0]['package']=package
    req=AppTaskRequest.model_validate(body)
    body=recovering(req,blocked=[],last=reference(req,fingerprint='b'*64)).model_dump(mode='json')
    body['recovery'].update(policy='mc_v1',reason='observed_transition_unconfirmed',attempt=1)
    req=AppTaskRequest.model_validate(body);before=req.model_dump(mode='json');send=sender()
    caps=ActionCapabilities(frozenset(('tap',node.id) for node in req.screen.nodes))
    result=AppTaskService(sender=send).step(req,action_capabilities=caps)
    send.assert_called_once();context=send.seen[0]['context']
    assert context['recovery']['mode']=='mc_v1'
    assert context['recovery']['reason']=='observed_transition_unconfirmed'
    assert context['recovery']['last_action']['target']=='not_current'
    assert context['recovery']['blocked_actions']==[]
    assert len(context['allowed_actions'])==2
    assert result.diagnostic['recovery_context_used'] is True
    assert result.diagnostic['recovery_policy']=='mc_v1'
    assert result.diagnostic['recovery_reason']=='observed_transition_unconfirmed'
    assert result.diagnostic['recovery_blocked_action_count']==0
    assert result.status=='proposed' and result.completion is None
    assert req.model_dump(mode='json')==before
    assert 'not verified step or goal completion' in send.seen[0]['prompt']
    assert 'Recovery mode mc_v1' in send.seen[0]['prompt']
