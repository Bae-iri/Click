"""Opt-in Qwen screen choice: one id per screen for a one-destination navigation goal.

Screens follow the Files app (DocumentsUI) on SM-N981N: home with filter chips, the roots
drawer, and an arrived root whose title is a short form of the goal.
"""
import json
from unittest.mock import Mock

import httpx
import pytest

from app.schemas.app_task import TaskApp, TaskAttempt, TaskSubgoal
from app.services import openai_responses
from app.services.app_task import AppTaskService
from tests.test_app_task import request

FILES = 'com.google.android.documentsui'
RECENT = '파일 앱에서 최근 파일 화면을 열어줘'
DOCS = '파일 앱에서 문서 분류 화면을 열어줘'


def btn(label, top, **extra):
    return dict(label=label, role='button', clickable=True, bounds=[0, top, 780, top + 150], **extra)


def icon(label, left):
    return dict(label=label, role='button', clickable=True, bounds=[left, 92, left + 136, 227])


def text(label, top, left=180, right=760):
    return dict(label=label, role='text', bounds=[left, top, right, top + 60])


HOME = [icon('루트 표시', 0), text('다운로드', 120, 170, 390), btn('이미지', 360), btn('오디오', 520),
        dict(label='문서', role='toggle', clickable=True, checked=False, bounds=[0, 680, 780, 830]), icon('검색', 809)]
DRAWER = [btn('최근', 250), btn('이미지', 410), btn('문서', 570), btn('다운로드', 730)]
ARRIVED_RECENT = [icon('루트 표시', 0), text('최근', 120, 170, 390), icon('검색', 809)]


@pytest.fixture(autouse=True)
def router(monkeypatch):
    monkeypatch.setenv('APP_TASK_PROVIDER', 'openrouter')
    monkeypatch.setenv('OPENROUTER_API_KEY', 'test-router')
    monkeypatch.setenv('APP_TASK_OPENROUTER_SHORT_MENU', 'screen')


def screen(nodes, goal=RECENT, planned=False):
    req = request(FILES, nodes=[dict(id=f'n{i}', **node) for i, node in enumerate(nodes)])
    req.goal, req.planning_mode = goal, 'goal_first'
    req.apps = [TaskApp(package=FILES, label='파일')]
    if planned:
        req.state.subgoals = [TaskSubgoal(id='g0', title=goal)]
        req.state.attempts = [TaskAttempt(fingerprint='b' * 64, tool='tap', target='n0', outcome='verified')]
    return req


def qwen(monkeypatch, choice):
    body = {'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant', 'status': 'completed',
            'content': [{'type': 'output_text', 'text': json.dumps({'choice': choice})}]}]}
    transport = Mock(return_value=httpx.Response(200, json=body))
    monkeypatch.setattr('app.services.app_task.generate', transport)
    return transport


def alias(req, label, role='button'):
    return next(f'c{i}' for i, n in enumerate(req.screen.nodes) if n.label == label and n.role == role)


def offered(transport):
    payload = transport.call_args.args[2]
    return payload, json.loads(payload['input'][payload['input'].index('{'):])['controls']


def test_first_screen_taps_with_one_short_qwen_answer(monkeypatch):
    req = screen(HOME)
    transport = qwen(monkeypatch, alias(req, '루트 표시'))
    result = AppTaskService().step(req)
    model, key, payload = transport.call_args.args
    assert (model, key, transport.call_args.kwargs['provider']) == (openai_responses.QWEN_FLASH_MODEL, 'test-router', 'openrouter')
    assert payload['max_output_tokens'] == 32 and payload['temperature'] == 0
    # A checkable filter chip no longer pushes the screen to the full planner.
    assert {c['label'] for c in offered(transport)[1] if c['kind'] == 'tap'} == {'루트 표시', '이미지', '오디오', '문서', '검색'}
    assert result.status == 'proposed' and result.action.tool == 'tap' and result.action.target_id == 'n0'
    assert [s.title for s in result.subgoals] == [RECENT] and not result.action.advances_subgoal
    assert result.diagnostic['short_choice'] == 'screen' and result.diagnostic['choice_kind'] == 'tap'


def test_the_screen_after_the_menu_keeps_the_plan_and_continues(monkeypatch):
    # Before: the drawer went to the full planner, whose Qwen plan the server rejected.
    req = screen(DRAWER, planned=True)
    transport = qwen(monkeypatch, alias(req, '최근'))
    result = AppTaskService().step(req)
    transport.assert_called_once()
    assert result.status == 'proposed' and result.action.target_id == 'n0'
    assert result.subgoals == req.state.subgoals and result.active_subgoal == 'g0'
    assert result.diagnostic['goal_assessment'] == 'not_satisfied'


def test_short_title_of_the_destination_settles_arrival(monkeypatch):
    req = screen(ARRIVED_RECENT, planned=True)
    transport = qwen(monkeypatch, alias(req, '최근', 'text'))
    result = AppTaskService().step(req)
    assert [(c['label'], c['kind']) for c in offered(transport)[1] if c['kind'] == 'destination_title'] == [('최근', 'destination_title')]
    assert result.status == 'confirm_result' and result.action is None
    assert [(c.label, c.role) for c in result.completion.checks] == [('최근', 'text')]
    assert result.diagnostic['goal_assessment'] == 'satisfied' and result.subgoals == req.state.subgoals


def test_documents_goal_offers_the_chip_and_the_drawer_route(monkeypatch):
    req = screen(HOME, goal=DOCS)
    transport = qwen(monkeypatch, alias(req, '문서', 'toggle'))
    result = AppTaskService().step(req)
    payload, controls = offered(transport)
    assert not [c for c in controls if c['kind'] == 'destination_title']
    assert result.status == 'proposed' and result.action.target_id == 'n4'


@pytest.mark.parametrize('nodes,goal', [
    # The parent page title: the destination is still a row here.
    ([text('알림', 120, 170, 390), btn('앱 알림', 300), btn('잠금화면 알림', 460)], '앱 알림 화면을 열어줘'),
    # Another page's title.
    (HOME, RECENT),
    # Row text sits inside its tappable row.
    ([btn('최근 항목', 250), text('최근', 270)], RECENT),
])
def test_never_offers_a_title_that_is_not_this_destination(monkeypatch, nodes, goal):
    req = screen(nodes, goal=goal, planned=True)
    transport = qwen(monkeypatch, 'none')
    result = AppTaskService().step(req)
    assert not [c for c in offered(transport)[1] if c['kind'] == 'destination_title']
    assert result.status == 'needs_input' and result.completion is None and result.action is None


def test_an_id_outside_the_offer_is_rejected(monkeypatch):
    req = screen([text('알림', 120, 170, 390), btn('앱 알림', 300)], goal='앱 알림 화면을 열어줘', planned=True)
    qwen(monkeypatch, alias(req, '알림', 'text'))
    result = AppTaskService().step(req)
    assert result.status == 'unavailable' and result.completion is None
    assert result.diagnostic['reason_code'] == 'unknown_target'


@pytest.mark.parametrize('goal', ['파일 앱에서 최근 파일을 열고 첫 파일을 삭제해줘', '최근 파일 화면을 열고 그리고 검색해줘'])
def test_multi_step_goals_keep_the_full_planner(monkeypatch, goal):
    req = screen(DRAWER, goal=goal, planned=True)
    transport = qwen(monkeypatch, 'none')
    result = AppTaskService().step(req)
    assert 'choice' not in transport.call_args.args[2]['text']['format']['schema']['properties']
    assert 'short_choice' not in result.diagnostic


def test_first_menu_flag_keeps_later_screens_on_the_full_planner(monkeypatch):
    monkeypatch.setenv('APP_TASK_OPENROUTER_SHORT_MENU', '1')
    req = screen(DRAWER, planned=True)
    transport = qwen(monkeypatch, 'none')
    AppTaskService().step(req)
    assert 'choice' not in transport.call_args.args[2]['text']['format']['schema']['properties']
