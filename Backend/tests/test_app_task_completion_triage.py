"""Completion triage: a unique on-screen destination proof settles completion without a model call.

Device evidence: docs/reviews/2026-11-21-triage-only/report.md.
"""
from unittest.mock import Mock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.v1.endpoints.app_guide import build_router
from app.schemas.app_task import TaskApp, TaskAttempt, TaskSubgoal
from app.services.app_task import AppTaskService
from tests.test_app_task import request, sender


@pytest.fixture(autouse=True)
def model(monkeypatch):
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL', 'gemini-3.8-flash')


def arrived(*nodes, goal='디스플레이 설정 화면을 열어줘', package='com.android.settings', verified=True):
    req = request(package, nodes=[dict(id=f'n{i}', bounds=[0, 100 * i, 500, 100 * i + 80], **node)
                                  for i, node in enumerate(nodes)])
    req.goal = goal
    req.apps = [TaskApp(package=package, label='설정')]
    req.state.subgoals = [TaskSubgoal(id='g0', title='디스플레이 열기', status='verified')]
    if verified:
        req.state.attempts = [TaskAttempt(fingerprint='b' * 64, tool='tap', target='x', outcome='verified')]
    return req


TITLE = dict(label='디스플레이', role='text')
MENU = dict(label='밝기', role='button', clickable=True)


def test_unique_page_title_settles_completion_without_model_call():
    send = sender()
    result = AppTaskService(send).step(arrived(TITLE, MENU), completion_triage=True)
    send.assert_not_called()
    assert result.status == 'confirm_result' and result.action is None
    assert [(c.label, c.role) for c in result.completion.checks] == [('디스플레이', 'text')]
    assert result.diagnostic['called'] is False and result.diagnostic['reason_code'] == 'completion_triage'
    assert result.diagnostic['completion_provider'] == 'local_triage'


def test_service_default_keeps_the_model_path():
    send = sender(subgoals=['디스플레이 열기'], completion_checks=[])
    result = AppTaskService(send).step(arrived(TITLE, MENU))
    send.assert_called_once()
    assert result.diagnostic.get('reason_code') != 'completion_triage'


def test_requested_selected_tab_settles_completion():
    tab = dict(label='타이머', role='tab', clickable=True, selected=True)
    req = arrived(tab, dict(label='알람', role='tab', clickable=True), goal='타이머 탭으로 이동해줘',
                  package='com.sec.android.app.clockpackage')
    send = sender()
    result = AppTaskService(send).step(req, completion_triage=True)
    send.assert_not_called()
    assert [(c.label, c.role, c.selected) for c in result.completion.checks] == [('타이머', 'tab', True)]


@pytest.mark.parametrize('case', ['no_move', 'generic_app_title', 'menu_only', 'unselected_tab', 'two_titles', 'duplicate'])
def test_falls_back_to_model_unless_evidence_is_one_current_destination(case):
    nodes, kwargs = {
        'no_move': ([TITLE], dict(verified=False)),
        'generic_app_title': ([dict(label='설정', role='text')], {}),
        'menu_only': ([dict(label='디스플레이', role='button', clickable=True)], {}),
        'unselected_tab': ([dict(label='타이머', role='tab', clickable=True)], dict(goal='타이머 탭으로 이동해줘')),
        'two_titles': ([TITLE, dict(label='화면 모드', role='text')], dict(goal='디스플레이 화면 모드 설정 화면을 열어줘')),
        'duplicate': ([TITLE, dict(label='디스플레이', role='text')], {}),
    }[case]
    send = sender(subgoals=['디스플레이 열기'], completion_checks=[])
    result = AppTaskService(send).step(arrived(*nodes, **kwargs), completion_triage=True)
    send.assert_called_once()
    assert result.diagnostic.get('reason_code') != 'completion_triage'


@pytest.mark.parametrize('mode', ['goal_first', 'next_action'])
def test_answers_with_the_read_only_contract_the_device_validates(mode):
    # The device rejects a goal_first answer without goal_assessment (AppGuideGateway.validatePlanningMode).
    req = arrived(TITLE, MENU)
    req.state.subgoals = [TaskSubgoal(id='g0', title='설정 열기', status='verified'), TaskSubgoal(id='g1', title='디스플레이 열기')]
    req.planning_mode = mode
    result = AppTaskService(sender()).step(req, completion_triage=True)
    assert result.status == 'confirm_result' and result.action is None and result.completion is not None
    assert result.subgoals == req.state.subgoals and result.active_subgoal == 'g1'
    assert result.diagnostic.get('goal_assessment') == ('satisfied' if mode == 'goal_first' else None)
    assert result.diagnostic['planning_mode'] == mode


@pytest.mark.parametrize('env,called', [(None, False), ('1', False), ('0', True)])
def test_task_api_enables_triage_unless_switched_off(monkeypatch, env, called):
    if env is None:
        monkeypatch.delenv('APP_TASK_COMPLETION_TRIAGE', raising=False)
    else:
        monkeypatch.setenv('APP_TASK_COMPLETION_TRIAGE', env)
    send = sender(subgoals=['디스플레이 열기'], completion_checks=[])
    app = FastAPI()
    app.include_router(build_router(Mock(), lambda: object(), AppTaskService(send)), prefix='/api/v1')
    with TestClient(app) as client:
        response = client.post('/api/v1/agent/app-guide/task', json=arrived(TITLE, MENU).model_dump(mode='json'))
    assert response.status_code == 200
    assert send.called is called
    assert (response.json()['diagnostic'].get('reason_code') == 'completion_triage') is (not called)


# Device round 2 (docs/reviews/2026-11-21-device-round2): "앱 알림 화면을 열어줘" was
# settled on the 알림 page by its title "알림", one step short, 12/12 times.
APP_NOTIFICATIONS = '앱 알림 화면을 열어줘'


def test_title_inside_a_longer_goal_phrase_never_settles_completion():
    notifications_page = arrived(dict(label='알림', role='text'), dict(label='앱 알림', role='button', clickable=True),
                                 dict(label='잠금화면 알림', role='button', clickable=True), goal=APP_NOTIFICATIONS)
    send = sender(subgoals=['알림 열기', '앱 알림 열기'], completion_checks=[])
    result = AppTaskService(send).step(notifications_page, completion_triage=True)
    send.assert_called_once()
    assert result.diagnostic.get('reason_code') != 'completion_triage'


def test_title_equal_to_the_whole_goal_phrase_settles_completion():
    app_notifications_page = arrived(dict(label='앱 알림', role='text'), dict(label='전체', role='button', clickable=True),
                                     goal=APP_NOTIFICATIONS)
    send = sender()
    result = AppTaskService(send).step(app_notifications_page, completion_triage=True)
    send.assert_not_called()
    assert [(c.label, c.role) for c in result.completion.checks] == [('앱 알림', 'text')]


def test_no_completion_while_the_named_destination_is_still_an_actionable_control():
    # Same page title as the goal, but a row with the goal phrase is still tappable here.
    page = arrived(dict(label='앱 알림', role='text'), dict(label='앱 알림', role='button', clickable=True),
                   goal=APP_NOTIFICATIONS)
    send = sender(subgoals=['앱 알림 열기'], completion_checks=[])
    AppTaskService(send).step(page, completion_triage=True)
    send.assert_called_once()


@pytest.mark.parametrize('goal,title', [
    ('디스플레이 설정 화면을 열어줘', '디스플레이'), ('설정에서 디스플레이 메뉴로 들어가줘', '디스플레이'),
    ('소리 및 진동 설정 화면을 열어줘', '소리 및 진동'), ('알림을 열어줘', '알림'),
    ('글자 크기와 스타일 화면을 열어줘', '글자 크기와 스타일')])
def test_whole_phrase_titles_from_device_goals_still_settle(goal, title):
    send = sender()
    result = AppTaskService(send).step(arrived(dict(label=title, role='text'), MENU, goal=goal), completion_triage=True)
    send.assert_not_called()
    assert result.completion.checks[0].label == title


def test_intermediate_title_of_a_two_step_goal_is_not_the_destination():
    # 글자 크기와 스타일 lives under 디스플레이; the 디스플레이 page title must not settle it.
    page = arrived(TITLE, dict(label='글자 크기와 스타일', role='button', clickable=True), goal='글자 크기와 스타일 화면을 열어줘')
    send = sender(subgoals=['디스플레이 열기', '글자 크기와 스타일 열기'], completion_checks=[])
    AppTaskService(send).step(page, completion_triage=True)
    send.assert_called_once()
