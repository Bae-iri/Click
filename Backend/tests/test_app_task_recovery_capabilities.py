"""Recovery is bounded evidence; server capabilities remain the execution ceiling."""
import json
import time
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest
from pydantic import ValidationError

from app.schemas.app_task import AppTaskRequest, TaskAction, TaskCompletion, TaskExpectation, TaskSubgoal
from app.services.app_task import AppTaskService
from app.services.app_task_capabilities import ActionCapabilities, recovery_blocked_pairs
from app.services.app_task_provider import ProviderError


PACKAGE = 'com.android.settings'


@pytest.fixture(autouse=True)
def controlled_sender_only(monkeypatch):
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL', 'gemini-3.8-flash')
    monkeypatch.delenv('GEMINI_API_KEY', raising=False)
    def denied(*_args, **_kwargs):raise AssertionError('Live provider forbidden in recovery tests')
    monkeypatch.setattr('app.services.app_task.generate', denied)


def request():
    return AppTaskRequest.model_validate(dict(request_id='request-test', goal='디스플레이 화면 열어줘',
        state=dict(task_id='task-test'), apps=[dict(package=PACKAGE, label='Settings')],
        screen=dict(status='public', package=PACKAGE, observation_id='frame-test', fingerprint='a' * 64,
            captured_at_ms=int(time.time() * 1000), width=1080, height=2400, nodes=[
                dict(id='private-native-search-id', label='Search Settings', role='button', clickable=True,
                    bounds=[10, 20, 1000, 120]),
                dict(id='private-native-display-id', label='Display', role='button', clickable=True,
                    bounds=[10, 200, 1000, 300]) ])))


def reference(req, **changes):
    value=dict(tool='tap', package=req.screen.package, target_id=req.screen.nodes[0].id, fingerprint=req.screen.fingerprint)
    value.update(changes);return value


def recovering(req, *, blocked=None, last=None):
    body=req.model_dump(mode='json')
    body['recovery']=dict(policy='mc_v0', reason='accepted_action_unconfirmed', last_verdict='waiting',
        waited_ms=2400, attempt=1, observation_id=req.screen.observation_id,
        last_action=last or reference(req), blocked_actions=blocked if blocked is not None else [reference(req)])
    return AppTaskRequest.model_validate(body)


def sender(*, target='c1', tool='tap', app='none', compact_result=False):
    seen=[]
    def send(payload, **_kwargs):
        prompt=payload['contents'][0]['parts'][0]['text'];context=json.loads(prompt.rpartition('JSON only. ')[2])
        seen.append(dict(context=context, prompt=prompt, schema=payload['generationConfig']['responseJsonSchema']))
        answer=dict(subgoals=['디스플레이 열기'], tool=tool, target=target, app=app, expected_label='',
            expected_checked='unknown', expected_selected='unknown', advances_subgoal=False, completion_checks=[])
        if compact_result:answer=dict(completion_checks=[dict(target='c0', label='Display', app='none', checked='unknown', selected='unknown')])
        return httpx.Response(200, json={'candidates':[dict(finishReason='STOP', content=dict(parts=[dict(text=json.dumps(answer))]))]})
    mock=Mock(side_effect=send);mock.seen=seen;return mock


def test_legacy_request_without_recovery_or_trusted_cap_keeps_existing_context():
    req=request();send=sender();result=AppTaskService(sender=send).step(req)
    assert result.status=='proposed' and result.action.target_id==req.screen.nodes[1].id
    context=send.seen[0]['context'];assert 'recovery' not in context and 'allowed_actions' not in context
    assert all('allowed_tools' not in control for control in context['controls'])
    assert result.diagnostic['recovery_context_used'] is False and result.diagnostic['capabilities_applied'] is False
    assert result.diagnostic['allowed_actions_context_used'] is False and 'recovery_attempt' not in result.diagnostic


@pytest.mark.parametrize('case', ['wrong_observation','extra','policy','reason','verdict','negative_wait','float_wait',
    'bool_wait','long_wait','zero_attempt','large_attempt','too_many','bad_fp','raw_label'])
def test_recovery_schema_is_closed_bounded_and_current_observation_bound(case):
    req=recovering(request());body=req.model_dump(mode='json');recovery=body['recovery']
    if case=='wrong_observation':recovery['observation_id']='old-frame'
    elif case=='extra':recovery['allowed_actions']=[]
    elif case=='policy':recovery['policy']='other'
    elif case=='reason':recovery['reason']='other'
    elif case=='verdict':recovery['last_verdict']='verified'
    elif case=='negative_wait':recovery['waited_ms']=-1
    elif case=='float_wait':recovery['waited_ms']=2.5
    elif case=='bool_wait':recovery['waited_ms']=True
    elif case=='long_wait':recovery['waited_ms']=120001
    elif case=='zero_attempt':recovery['attempt']=0
    elif case=='large_attempt':recovery['attempt']=4
    elif case=='too_many':recovery['blocked_actions']*=17
    elif case=='bad_fp':recovery['last_action']['fingerprint']='not-a-fingerprint'
    else:recovery['last_action']['label']='PRIVATE LABEL'
    with pytest.raises(ValidationError):AppTaskRequest.model_validate(body)


def test_recovery_reaches_the_single_planner_call_as_aliases_and_removes_only_blocked_pair():
    req=recovering(request());before=req.model_dump(mode='json');send=sender()
    cap=ActionCapabilities(frozenset({('tap',n.id) for n in req.screen.nodes}|{('back','')}))
    result=AppTaskService(sender=send).step(req,action_capabilities=cap)
    send.assert_called_once();assert result.status=='proposed' and result.action.target_id==req.screen.nodes[1].id
    context=send.seen[0]['context'];recovery=context['recovery']
    assert recovery['mode']=='mc_v0' and recovery['reason']=='accepted_action_unconfirmed'
    assert recovery['last_verdict']=='waiting' and recovery['waited_ms']==2400 and recovery['attempt']==1
    assert recovery['last_action']==dict(tool='tap',target='c0',app='none',same_screen=True)
    assert recovery['blocked_actions']==[recovery['last_action']]
    assert context['controls'][0]['allowed_tools']==[] and context['controls'][1]['allowed_tools']==['tap']
    assert result.diagnostic['recovery_context_used'] is True and result.diagnostic['capabilities_applied'] is True
    assert result.diagnostic['allowed_actions_context_used'] is True
    assert result.diagnostic['allowed_action_count']==len(context['allowed_actions'])==2
    assert result.diagnostic['recovery_blocked_action_count']==1 and result.diagnostic['recovery_attempt']==1
    properties=send.seen[0]['schema']['properties']
    assert 'c0' not in properties['target']['enum'] and 'c1' in properties['target']['enum']
    variants = properties['completion_checks']['items']['anyOf']
    assert any('c0' in variant['properties']['target']['enum'] for variant in variants)
    assert 'Recovery mode mc_v0' in send.seen[0]['prompt']
    assert all(n.id not in send.seen[0]['prompt'] for n in req.screen.nodes)
    assert req.screen.fingerprint not in send.seen[0]['prompt'] and req.screen.observation_id not in send.seen[0]['prompt']
    assert req.model_dump(mode='json')==before


def test_disobedient_planner_cannot_repeat_current_blocked_pair_and_its_call_is_counted():
    req=recovering(request());send=sender(target='c0');result=AppTaskService(sender=send).step(req)
    send.assert_called_once();assert result.status=='unavailable' and result.action is None
    assert result.diagnostic['called'] is True and result.diagnostic['reason_code']=='recovery_action_blocked'
    assert result.diagnostic['failure_kind']=='plan'


def test_last_action_is_informational_and_unseen_ids_or_historical_frames_cannot_add_authority():
    req=request();old=reference(req,target_id='PRIVATE-HISTORICAL-NATIVE-ID',fingerprint='b'*64,package='org.old.app')
    req=recovering(req,blocked=[old],last=old);send=sender(target='c0')
    cap=ActionCapabilities(frozenset({('tap',req.screen.nodes[0].id)}))
    result=AppTaskService(sender=send).step(req,action_capabilities=cap)
    assert result.status=='proposed' and recovery_blocked_pairs(req)==frozenset()
    context=send.seen[0]['context'];assert context['recovery']['last_action']['target']=='not_current'
    assert context['recovery']['last_action']['same_screen'] is False
    assert 'PRIVATE-HISTORICAL' not in send.seen[0]['prompt'] and 'org.old.app' not in send.seen[0]['prompt']
    req=recovering(request(),blocked=[],last=reference(request(),target_id='PRIVATE-UNSEEN-NATIVE-ID'))
    send=sender(target='c1');result=AppTaskService(sender=send).step(req,action_capabilities=cap)
    assert result.status=='unavailable' and result.diagnostic['reason_code']=='action_not_allowed_by_capabilities'
    assert send.seen[0]['context']['recovery']['last_action']['target']=='not_visible'
    assert 'PRIVATE-UNSEEN' not in send.seen[0]['prompt']


def test_selected_open_app_block_is_scoped_to_original_frame_even_when_current_package_differs():
    req=request();body=req.model_dump(mode='json');body['screen']['package']='org.example.launcher'
    req=AppTaskRequest.model_validate(body);last=reference(req,tool='open_app',package=PACKAGE,target_id=None)
    req=recovering(req,last=last,blocked=[last]);send=sender(tool='open_app',target='none',app=PACKAGE)
    cap=ActionCapabilities(frozenset({('open_app',PACKAGE),('back','')}))
    result=AppTaskService(sender=send).step(req,action_capabilities=cap)
    assert result.status=='unavailable' and result.diagnostic['reason_code']=='recovery_action_blocked'
    assert send.seen[0]['context']['recovery']['last_action']==dict(tool='open_app',target='none',app=PACKAGE,same_screen=True)
    old=reference(req,tool='open_app',package='org.not.selected',target_id=None)
    assert not recovery_blocked_pairs(recovering(req,blocked=[old],last=old))


@pytest.mark.parametrize('tool', ['tap','back'])
def test_node_and_back_restrictions_require_both_exact_original_fingerprint_and_current_package(tool):
    req=request();matching=reference(req,tool=tool,target_id=None if tool=='back' else req.screen.nodes[0].id)
    assert recovery_blocked_pairs(recovering(req,blocked=[matching]))
    for changes in (dict(fingerprint='b'*64),dict(package='org.historical.app')):
        old=dict(matching,**changes)
        assert not recovery_blocked_pairs(recovering(req,blocked=[old]))


def test_empty_trusted_execution_capability_stops_unfinished_plan_without_provider_work():
    send=sender();result=AppTaskService(sender=send).step(request(),action_capabilities=ActionCapabilities(frozenset()))
    send.assert_not_called();assert result.status=='needs_input' and result.action is None
    assert result.diagnostic['called'] is False and result.diagnostic['reason_code']=='no_allowed_actions'
    assert result.diagnostic['recovery_context_used'] is False and result.diagnostic['allowed_actions_context_used'] is False
    assert result.diagnostic['capabilities_applied'] is True and result.diagnostic['allowed_action_count']==0
    assert 'recovery_attempt' not in result.diagnostic


def test_empty_execution_mask_preserves_result_only_readable_evidence_discovery():
    req=request();req.screen.nodes=[req.screen.nodes[1].model_copy(update=dict(id='heading',label='Display',role='text',clickable=False))]
    req.state.subgoals=[TaskSubgoal(id='g0',title='디스플레이 열기',status='verified')]
    send=sender(compact_result=True);result=AppTaskService(sender=send).step(req,action_capabilities=ActionCapabilities(frozenset()))
    send.assert_called_once();assert result.status=='confirm_result' and result.action is None and result.completion is not None
    assert result.completion.checks[0].label=='Display' and result.diagnostic['called'] is True
    assert send.seen[0]['context']['controls'][0]['allowed_tools']==[]
    assert result.diagnostic['recovery_context_used'] is False and result.diagnostic['allowed_actions_context_used'] is False
    assert result.diagnostic['capabilities_applied'] is True and result.diagnostic['allowed_action_count']==0
    assert send.seen[0]['schema']['properties']['completion_checks']['items']['properties']['target']['enum']==['c0','none']
    result=AppTaskService(sender=sender()).step(req.model_copy(update={'state':req.state.model_copy(update={'completion':result.completion})}),
        action_capabilities=ActionCapabilities(frozenset()))
    assert result.status=='confirm_result' and result.diagnostic['called'] is False
    assert result.diagnostic['recovery_context_used'] is False and result.diagnostic['allowed_actions_context_used'] is False


@pytest.mark.parametrize('restriction', ['trusted_cap','recovery'])
def test_recalled_fast_path_cannot_bypass_execution_ceiling_or_current_recovery_block(restriction):
    req=request();req.state.completion=TaskCompletion(checks=[dict(package=PACKAGE,label='Destination heading',role='text')])
    action=TaskAction(id='old',tool='tap',target_id=req.screen.nodes[0].id,bounds=req.screen.nodes[0].bounds,
        package=PACKAGE,expectation=TaskExpectation(kind='screen_changed'))
    memory=SimpleNamespace(recall=Mock(return_value=SimpleNamespace(action=action,evidence=[],tactics=[])))
    cap=ActionCapabilities(frozenset({('tap',req.screen.nodes[1].id)})) if restriction=='trusted_cap' else None
    if restriction=='recovery':req=recovering(req)
    send=sender();result=AppTaskService(sender=send,memory=memory).step(req,user_id='test-user',action_capabilities=cap)
    send.assert_called_once();assert result.status=='proposed' and result.action.target_id==req.screen.nodes[1].id
    assert result.diagnostic['called'] is True


def test_removed_count_excludes_duplicates_stale_refs_and_actions_already_outside_trusted_cap():
    base=request();blocked=[reference(base),reference(base),reference(base,tool='back',target_id=None),
        reference(base,target_id='private-native-display-id',fingerprint='b'*64)]
    req=recovering(base,blocked=blocked)
    cap=ActionCapabilities(frozenset({('tap',req.screen.nodes[1].id),('back','')}))
    send=sender();result=AppTaskService(sender=send).step(req,action_capabilities=cap)
    assert result.diagnostic['recovery_blocked_action_count']==1  # back only; Search was never authorized.
    assert result.diagnostic['allowed_action_count']==len(send.seen[0]['context']['allowed_actions'])==1


@pytest.mark.parametrize('state,called,used', [('not_started',False,False),('unknown',None,True),('observed',True,True)])
def test_provider_handshake_state_controls_interpretation_of_payload_boundary_flags(monkeypatch,state,called,used):
    monkeypatch.setenv('GEMINI_API_KEY','test-only-key-never-sent')
    def boundary(*_args,**_kwargs):raise ProviderError('provider_capacity_exhausted',state)
    monkeypatch.setattr('app.services.app_task.generate',boundary)
    req=recovering(request());cap=ActionCapabilities(frozenset({('tap',n.id) for n in req.screen.nodes}|{('back','')}))
    result=AppTaskService().step(req,action_capabilities=cap)
    assert result.diagnostic['called'] is called
    assert result.diagnostic['recovery_context_used'] is used and result.diagnostic['allowed_actions_context_used'] is used
    assert result.diagnostic['capabilities_applied'] is True
    assert result.diagnostic['allowed_action_count']==2 and result.diagnostic['recovery_blocked_action_count']==1
    if state=='not_started':assert 'recovery_attempt' not in result.diagnostic
    else:assert result.diagnostic['recovery_attempt']==1
    # Unknown provider state plus a prepared-payload flag is never observed forwarding evidence.
    forwarding_proven=result.diagnostic['called'] is True and result.diagnostic['recovery_context_used'] is True
    assert forwarding_proven is (state=='observed')
