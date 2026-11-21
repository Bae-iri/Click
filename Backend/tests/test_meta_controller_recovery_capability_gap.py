"""Regression for the independently reproduced planner/executor capability gap.

Uses the frozen native Settings catalogue and actual service/facade decisions,
with one in-process fake sender. It neither executes Android actions nor calls
a provider. This example does not identify any historical blocked proposal:
the old artifacts did not retain those target labels.
"""
import json
from pathlib import Path
import time
from types import SimpleNamespace

import httpx

import pytest

from app.schemas.app_task import AppTaskRequest, TaskAction, TaskExpectation
from app.services.app_task import AppTaskService
from scripts.serve_meta_controller_settings_experiment import (
    PACKAGE, allowed, load_contract, make_handler, navigation_action_allowed,
)


CATALOGUE = Path(__file__).resolve().parents[2] / 'experiments/metacontroller/native-settings-catalogue-v1.json'


@pytest.mark.parametrize('obeys_mask', [True, False], ids=['planner_honors_mask', 'planner_disobeys_mask'])
def test_frozen_execution_mask_reaches_planner_and_is_still_enforced_after_planning(tmp_path, monkeypatch, obeys_mask):
    """Readable Search remains evidence; only frozen Display navigation can execute."""
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL', 'gemini-3.8-flash')
    monkeypatch.delenv('GEMINI_API_KEY', raising=False)

    def no_live_provider(*_args, **_kwargs):
        raise AssertionError('This characterization must never invoke a live provider')

    monkeypatch.setattr('app.services.app_task.generate', no_live_provider)
    frozen = json.loads(CATALOGUE.read_text(encoding='utf-8'))
    contract = load_contract(CATALOGUE)
    assert len(contract.public_labels) == 178
    assert 'Search Settings' in contract.public_labels
    assert 'Search Settings' not in contract.navigation_labels
    assert ('Search Settings', PACKAGE + ':id/search_action_bar') not in contract.navigation_controls
    goal = next(task['goal'] for task in frozen['tasks'] if task['task_id'] == 'settings_display')
    request = AppTaskRequest.model_validate(dict(
        protocol_version='app-task.v2', request_id='controlled-capability-gap-request', goal=goal,
        state=dict(task_id='controlled-capability-gap-task', subgoals=[], attempts=[]),
        apps=[dict(package=PACKAGE, label='Settings')],
        screen=dict(status='public', package=PACKAGE, observation_id='controlled-capability-gap-frame',
            captured_at_ms=int(time.time() * 1000), fingerprint='a' * 64, width=1080, height=2400,
            nodes=[
                dict(id='search_control', label='Search Settings', resource=PACKAGE + ':id/search_action_bar',
                    role='button', clickable=True, bounds=[20, 80, 1060, 180]),
                dict(id='display_control', label='Display & touch', resource='',
                    role='button', clickable=True, bounds=[20, 220, 1060, 340]),
            ])))
    # A public readonly observation is admitted; it is not an action authorization.
    assert allowed(request, contract)
    captured = []

    def fake_sender(payload, *, timeout):
        assert 0 < timeout <= 8
        prompt = payload['contents'][0]['parts'][0]['text']
        _instructions, separator, serialized = prompt.rpartition('JSON only. ')
        assert separator
        context = json.loads(serialized)
        search = next(control for control in context['controls'] if control['label'] == 'Search Settings')
        display = next(control for control in context['controls'] if control['label'] == 'Display & touch')
        permitted = next(action for action in context['allowed_actions'] if action['tool'] == 'tap')
        assert permitted['target'] == display['id']
        captured.append(dict(context=context, prompt=prompt, payload=payload, search=search['id'], display=display['id']))
        answer = dict(subgoals=['디스플레이 화면 열기'], tool='tap', target=permitted['target'] if obeys_mask else search['id'],
            expected_label='', app='none', expected_checked='unknown', expected_selected='unknown',
            advances_subgoal=False, completion_checks=[])
        return httpx.Response(200, json={'candidates': [dict(finishReason='STOP',
            content=dict(parts=[dict(text=json.dumps(answer, ensure_ascii=False))]))]})

    actual_service = AppTaskService(sender=fake_sender)
    responses = []

    def step(actual_request, **kwargs):
        assert actual_request == request  # Native observation/clock/fingerprint were not filtered or rewritten.
        result = actual_service.step(actual_request, **kwargs)
        responses.append(result)
        return result

    # Invoke the real facade service boundary directly: no HTTP socket/provider.
    handler = make_handler(SimpleNamespace(step=step), contract, tmp_path / 'unused-terminal.jsonl')
    record = {}
    status, body = handler._invoke(None, request, record)
    assert len(captured) == len(responses) == 1
    result = responses[0]
    assert result.diagnostic['called'] is True and result.requires_confirmation is True

    context = captured[0]['context']
    exposed = next(control for control in context['controls'] if control['id'] == captured[0]['search'])
    assert exposed['label'] == 'Search Settings' and exposed['clickable'] is True
    assert exposed['editable'] is False and exposed['has_value'] is False
    assert exposed['allowed_tools'] == []
    assert result.diagnostic['recovery_context_used'] is False and result.diagnostic['capabilities_applied'] is True
    assert result.diagnostic['allowed_actions_context_used'] is True
    assert result.diagnostic['allowed_action_count'] == len(context['allowed_actions'])
    assert result.diagnostic['recovery_blocked_action_count'] == 0 and 'recovery_attempt' not in result.diagnostic
    assert record['recovery_context_used'] is False and record['capabilities_applied'] is True
    assert record['allowed_actions_context_used'] is True
    assert record['allowed_action_count'] == len(context['allowed_actions']) and record['recovery_blocked_action_count'] == 0
    display = next(control for control in context['controls'] if control['id'] == captured[0]['display'])
    assert display['allowed_tools'] == ['tap']
    schema = captured[0]['payload']['generationConfig']['responseJsonSchema']['properties']
    assert captured[0]['search'] not in schema['target']['enum']
    assert captured[0]['display'] in schema['target']['enum']
    item = schema['completion_checks']['items']
    proof_targets = {alias for variant in item.get('anyOf', [item]) for alias in variant['properties']['target']['enum']}
    assert captured[0]['search'] in proof_targets and captured[0]['display'] in proof_targets
    assert 'tap' in schema['tool']['enum'] and {'ask', 'confirm'}.issubset(schema['tool']['enum'])
    assert 'set_text' not in schema['tool']['enum']
    assert status == 200  # A rejected plan is a bound protocol response, never an executable proposal.
    if obeys_mask:
        assert result.status == 'proposed' and result.action.target_id == 'display_control'
        assert result.action.bounds == request.screen.nodes[1].bounds
        assert navigation_action_allowed(request, result, contract) is True
        assert record['plan_or_parse_error'] is False
    else:
        assert result.status == 'unavailable' and result.action is None
        assert result.diagnostic['reason_code'] == 'action_not_allowed_by_capabilities'
        assert record['plan_or_parse_error'] is True
        hypothetical = result.model_copy(update={'action': TaskAction(id='forbidden', tool='tap',
            target_id='search_control', bounds=request.screen.nodes[0].bounds, package=PACKAGE,
            expectation=TaskExpectation(kind='screen_changed'))})
        assert navigation_action_allowed(request, hypothetical, contract) is False
    assert record['provider_error'] is False
    # The injected sender was attempted once; post-model blocking must not claim zero work.
    assert record['model_call_state'] == 'observed' and record['model_calls'] == 1
    assert (body['action'] is not None) == obeys_mask
