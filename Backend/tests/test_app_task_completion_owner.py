"""Qwen plans the next action; T then Jev propose the current completion evidence; the device verifies.

Device evidence: docs/reviews/2026-11-21-qwen-jev-completion (Jev ran once in 24 episodes
because goal_first let the planner judge completion itself).
"""
import json
from unittest.mock import Mock

import httpx
import pytest

from app.schemas.app_task import TaskSubgoal
from app.services.app_task import AppTaskService
from tests.test_app_task import compact_sender
from tests.test_app_task_completion_triage import MENU, TITLE, arrived
from tests.test_app_task_jev_completion import jev_sender


@pytest.fixture(autouse=True)
def jev(monkeypatch):
    monkeypatch.setenv('APP_TASK_COMPLETION_PROVIDER', 'jev')
    monkeypatch.setenv('TYPESAFE_API_KEY', 'local-unit-test-token')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL', 'gemini-3.8-flash')
    monkeypatch.setenv('GEMINI_API_KEY', 'test-gemini')


def goal_first(*nodes, goal='디스플레이 설정 화면을 열어줘'):
    req = arrived(*nodes, goal=goal)
    req.state.subgoals = [TaskSubgoal(id='g0', title='설정 열기', status='verified'), TaskSubgoal(id='g1', title='디스플레이 열기')]
    req.planning_mode = 'goal_first'
    return req


def plan(**changes):
    value = dict(tool='tap', target='c1', expected_label='', app='none', expected_checked='unknown',
                 expected_selected='unknown', advances_subgoal=False, goal_status='not_satisfied',
                 completion_checks=[dict(target='c0', label='', app='none', checked='unknown', selected='unknown')])
    value.update(changes)
    return compact_sender(value)


def test_result_check_runs_the_rule_check_before_jev():
    req = arrived(TITLE, MENU)  # every step verified -> result_only
    send, planner = jev_sender(), Mock(side_effect=AssertionError('planner'))
    result = AppTaskService(planner, jev_sender=send).step(req, completion_triage=True)
    send.assert_not_called()
    assert result.diagnostic['completion_provider'] == 'local_triage' and result.status == 'confirm_result'


def test_goal_first_asks_jev_for_current_evidence_before_planning():
    req = goal_first(dict(label='디스플레이 모드', role='text'), MENU)
    send, planner = jev_sender(), Mock(side_effect=AssertionError('planner'))
    result = AppTaskService(planner, jev_sender=send).step(req, completion_triage=True)
    send.assert_called_once()
    assert result.status == 'confirm_result' and result.action is None and result.completion is not None
    assert result.diagnostic['completion_provider'] == 'jev' and result.diagnostic['goal_assessment'] == 'satisfied'
    assert result.diagnostic['planning_mode'] == 'goal_first'
    assert result.subgoals == req.state.subgoals and result.active_subgoal == 'g1'


def test_without_current_evidence_the_planner_only_chooses_the_next_action():
    # Parent page: Jev abstains, so guidance continues instead of stopping.
    req = goal_first(dict(label='알림', role='text'), dict(label='앱 알림', role='button', clickable=True),
                     goal='앱 알림 화면을 열어줘')
    send, planner = jev_sender('insufficient'), plan()
    result = AppTaskService(planner, jev_sender=send).step(req, completion_triage=True)
    send.assert_called_once()
    planner.assert_called_once()
    payload = planner.call_args.args[0]
    schema = payload['generationConfig']['responseJsonSchema']
    assert schema['properties']['goal_status']['enum'] == ['not_satisfied', 'uncertain']
    assert 'never return satisfied' in payload['contents'][0]['parts'][0]['text']
    assert result.status == 'proposed' and result.action.target_id == 'n1'
    # The planner's own evidence, current or predicted, is never bound.
    assert result.completion is None and result.deferred_completion is None
    assert result.diagnostic['goal_assessment'] == 'not_satisfied'
    assert result.diagnostic['jev_reason_code'] and result.diagnostic['jev_called'] is True


def test_goals_jev_cannot_check_keep_the_planner_evidence():
    req = goal_first(TITLE, MENU, goal='블루투스 켜줘')
    send = jev_sender()
    planner = plan(tool='confirm', target='none', goal_status='satisfied')
    result = AppTaskService(planner, jev_sender=send).step(req, completion_triage=True)
    send.assert_not_called()
    schema = planner.call_args.args[0]['generationConfig']['responseJsonSchema']
    assert 'satisfied' in schema['properties']['goal_status']['enum']


def test_local_model_runs_never_call_jev(monkeypatch):
    monkeypatch.setenv('APP_TASK_PROVIDER', 'local')
    req = goal_first(dict(label='디스플레이 모드', role='text'), MENU)
    send = jev_sender()
    monkeypatch.setattr('app.services.app_task.generate', Mock(return_value=httpx.Response(500)))
    AppTaskService(jev_sender=send).step(req)
    send.assert_not_called()
