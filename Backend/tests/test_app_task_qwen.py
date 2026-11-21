"""Primary Qwen uses one call and retains the grounding and recovery contracts."""
import json
from unittest.mock import Mock

import httpx
import pytest

from app.schemas.app_task import TaskAttempt, TaskSubgoal
from app.services.app_task import AppTaskService
from app.services.app_task_capabilities import ActionCapabilities
from app.services.openai_responses import QWEN_FLASH_MODEL
from tests.test_app_task_completion_triage import arrived
from tests.test_app_task_openai import message
from tests.test_app_task_recovery_capabilities import recovering, request


@pytest.fixture(autouse=True)
def primary(monkeypatch):
    monkeypatch.setenv('APP_TASK_PROVIDER', 'openrouter')
    monkeypatch.setenv('OPENROUTER_API_KEY', 'test-router')
    monkeypatch.setenv('APP_TASK_OPENROUTER_COMPACT', '1')
    monkeypatch.setenv('APP_TASK_OPENROUTER_SHORT_MENU', '0')


def run(monkeypatch, req, answer, **options):
    transport = Mock(return_value=httpx.Response(200, json={
        'status': 'completed', 'output': [message(json.dumps(answer))]}))
    monkeypatch.setattr('app.services.app_task.generate', transport)
    result = AppTaskService().step(req, **options)
    transport.assert_called_once()
    model, key, payload = transport.call_args.args
    assert (model, key, transport.call_args.kwargs['provider']) == (QWEN_FLASH_MODEL, 'test-router', 'openrouter')
    return result, payload


def test_followup_uses_choice_without_changing_existing_plan(monkeypatch):
    req = request()
    req.planning_mode = 'goal_first'
    req.state.subgoals = [TaskSubgoal(id='g0', title='하위 메뉴 열기')]
    req.state.attempts = [TaskAttempt(fingerprint='b'*64, tool='tap', target='old', outcome='verified')]
    result, payload = run(monkeypatch, req, {'choice': 'c1'})
    assert list(payload['text']['format']['schema']['properties']) == ['choice']
    assert req.state.subgoals[0].title in payload['input']
    assert result.status == 'proposed' and result.action.target_id == req.screen.nodes[1].id
    assert result.subgoals == req.state.subgoals and not result.action.advances_subgoal


def test_scroll_must_be_offered_for_the_same_node_and_direction(monkeypatch):
    req = request()
    req.planning_mode = 'goal_first'
    node = req.screen.nodes[0]
    node.role, node.scrollable, node.clickable = 'list', True, False
    cap = ActionCapabilities(frozenset({('scroll_forward', node.id), ('tap', req.screen.nodes[1].id)}))
    result, payload = run(monkeypatch, req, {'choice': 'scroll_forward:c0'}, action_capabilities=cap)
    assert set(payload['text']['format']['schema']['properties']['choice']['enum']) == {'none','c1','scroll_forward:c0'}
    assert result.status == 'proposed' and result.action.tool == 'scroll_forward' and result.action.target_id == node.id
    bad, _ = run(monkeypatch, req, {'choice': 'scroll_backward:c0'}, action_capabilities=cap)
    assert bad.action is None and bad.status == 'unavailable'


@pytest.mark.parametrize('action,flags,ok', [('tap:c1', [], True), ('tap:c0', [], False),
        ('tap:c1', ['selected_true','selected_false'], False), ('tap:c1', ['checked_true'], False)])
def test_general_recovery_action_is_limited_to_grounded_capabilities(monkeypatch, action, flags, ok):
    req = recovering(request())
    answer = dict(action=action, flags=flags, subgoals=['디스플레이 열기'], expected_label='', completion_checks=[])
    cap = ActionCapabilities(frozenset(('tap', n.id) for n in req.screen.nodes))
    result, payload = run(monkeypatch, req, answer, action_capabilities=cap)
    choices = payload['text']['format']['schema']['properties']['action']['enum']
    assert set(choices) == {'ask', 'confirm', 'tap:c1'}
    assert req.recovery.policy in payload['input'] and req.screen.nodes[0].id not in payload['input']
    assert (result.action is not None) == ok and result.diagnostic['recovery_context_used']
    if ok:
        assert result.action.target_id == req.screen.nodes[1].id


@pytest.mark.parametrize('proof,ok', [(['c0'], True), (['c0','c0'], False), (['c1'], False),
                                      (['c1.selected_false'], False), (['unknown'], False)])
def test_navigation_accepts_only_observed_proof_ids(monkeypatch, proof, ok):
    req = arrived(dict(label='디스플레이', role='text'), dict(label='밝기', role='button', clickable=True))
    req.planning_mode = 'result_only'
    result, payload = run(monkeypatch, req, {'proof': proof})
    assert payload['text']['format']['schema']['properties']['proof']['items']['enum'] == ['c0']
    assert result.action is None and result.subgoals == req.state.subgoals
    assert (result.completion is not None) == ok
    if ok:
        assert result.completion.checks[0].label == '디스플레이'


def test_model_cannot_override_actionable_destination_guard(monkeypatch):
    req = arrived(dict(label='알림', role='text'), dict(label='앱 알림', role='button', clickable=True),
                  goal='앱 알림 화면을 열어줘')
    req.planning_mode = 'result_only'
    result, _ = run(monkeypatch, req, {'proof': ['c0']}, completion_triage=True)
    assert result.action is None and result.completion is None
    assert result.diagnostic['completion_grounding'] == 'deferred_visible_destination_link'


def test_unchecked_native_state_is_preserved(monkeypatch):
    req = arrived(dict(label='알림', role='toggle', clickable=True, checked=False), goal='알림을 꺼줘')
    req.planning_mode = 'result_only'
    result, payload = run(monkeypatch, req, {'proof': ['c0.checked_false']})
    choices = payload['text']['format']['schema']['properties']['proof']['items']['enum']
    assert 'c0.checked_false' in choices and 'c0.checked_true' not in choices
    assert result.completion.checks[0].checked is False


def test_recovery_completion_keeps_current_screen_only_contract(monkeypatch):
    req = recovering(request())
    req.planning_mode = 'result_only'
    result, payload = run(monkeypatch, req, {'completion_checks': []})
    assert 'Recovery mc_v0' in payload['input']
    assert list(payload['text']['format']['schema']['properties']) == ['completion_checks']
    assert result.completion is None and result.action is None


@pytest.mark.parametrize('decision,status,action,checked', [
    ('final_tab:c1', 'proposed', 'tap', True),
    ('tap:c1', 'proposed', 'tap', False),
    ('proof:c0.selected_true', 'confirm_result', None, True),
    ('proof:c1.selected_true', 'unavailable', None, False),
    ('unsure', 'needs_input', None, False),
])
def test_navigation_distinguishes_observation_from_a_future_tab(monkeypatch, decision, status, action, checked):
    req = request()
    req.planning_mode = 'goal_first'
    req.goal = 'Open the display tab.'
    for node, label, selected in zip(req.screen.nodes, ['Menu', 'Display'], [True, False]):
        node.role, node.label, node.selected = 'tab', label, selected
    if decision == 'proof:c0.selected_true':
        req.goal = 'Open the menu tab.'
    result, payload = run(monkeypatch, req, {'decision': decision})
    assert list(payload['text']['format']['schema']['properties']) == ['decision']
    choices = payload['text']['format']['schema']['properties']['decision']['enum']
    assert all(not c.startswith('open_app:') for c in choices)
    assert result.status == status and (result.action.tool if result.action else None) == action
    assert (result.completion is not None) == checked
    if result.completion:
        assert result.completion.checks[0].selected is True
    if result.action:
        assert result.subgoals[0].title == req.goal and result.action.expectation.selected


def test_complex_goal_still_uses_full_multistep_plan(monkeypatch):
    req = request()
    req.planning_mode = 'goal_first'
    req.goal = 'Open Display and change the setting.'
    answer = dict(action='tap:c1', flags=[], goal_status='not_satisfied', expected_label='',
                  completion_checks=[], subgoals=['Open Display', 'Change setting'])
    result, payload = run(monkeypatch, req, answer)
    assert list(s.title for s in result.subgoals) == answer['subgoals']
    assert 'completion_checks' in payload['text']['format']['schema']['properties']
    assert result.status == 'proposed'


def test_row_text_cannot_reenter_through_model_after_local_triage(monkeypatch):
    req = arrived(dict(label='디스플레이', role='text'), dict(label='열기', role='button', clickable=True))
    req.screen.nodes[1].bounds = list(req.screen.nodes[0].bounds)
    req.planning_mode = 'result_only'
    result, payload = run(monkeypatch, req, {'proof': ['c0']}, completion_triage=True)
    assert payload['text']['format']['schema']['properties']['proof']['maxItems'] == 0
    assert result.completion is None and result.action is None


def test_unrestricted_collection_with_filters_uses_the_original_qwen_contract(monkeypatch):
    req = request()
    req.goal, req.planning_mode = 'Open the recent files screen.', 'goal_first'
    req.screen.nodes[0].role = 'toggle'
    req.screen.nodes.append(req.screen.nodes[1].model_copy(update={
        'id': 'scroll-list', 'role': 'list', 'scrollable': True, 'clickable': False}))
    value = dict(goal_status='not_satisfied', subgoals=['Open menu', 'Select location'],
                 tool='tap', target='c1', app='none', expected_label='', expected_checked='unknown',
                 expected_selected='unknown', advances_subgoal=False, completion_checks=[])
    result, payload = run(monkeypatch, req, value)
    monkeypatch.setenv('APP_TASK_OPENROUTER_COMPACT', '0')
    baseline, original = run(monkeypatch, req, value)
    assert payload == original
    assert baseline.action.target_id == result.action.target_id == req.screen.nodes[1].id
