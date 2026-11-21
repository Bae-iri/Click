"""Paper-ideas experiment options (docs/reviews/2026-11-21-paper-ideas): all off by default."""
import pytest

from app.schemas.app_task import TaskApp, TaskAttempt, TaskSubgoal
from app.services.app_task import AppTaskService
from app.services.app_task_experience import AppTaskExperienceService
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


def test_triage_binds_unique_page_title_without_model_call():
    send = sender()
    result = AppTaskService(send).step(arrived(TITLE, MENU), completion_triage=True)
    send.assert_not_called()
    assert result.status == 'confirm_result' and result.action is None
    assert [(c.label, c.role) for c in result.completion.checks] == [('디스플레이', 'text')]
    assert result.diagnostic['called'] is False and result.diagnostic['reason_code'] == 'completion_triage'


def test_triage_is_off_by_default():
    send = sender(subgoals=['디스플레이 열기'], completion_checks=[])
    result = AppTaskService(send).step(arrived(TITLE, MENU))
    send.assert_called_once()
    assert result.diagnostic.get('reason_code') != 'completion_triage'


def test_triage_binds_requested_selected_tab():
    tab = dict(label='타이머', role='tab', clickable=True, selected=True)
    req = arrived(tab, dict(label='알람', role='tab', clickable=True), goal='타이머 탭으로 이동해줘',
                  package='com.sec.android.app.clockpackage')
    send = sender()
    result = AppTaskService(send).step(req, completion_triage=True)
    send.assert_not_called()
    assert [(c.label, c.role, c.selected) for c in result.completion.checks] == [('타이머', 'tab', True)]


@pytest.mark.parametrize('case', ['no_move', 'generic_app_title', 'menu_only', 'unselected_tab', 'two_titles', 'duplicate'])
def test_triage_falls_back_to_model_when_evidence_is_not_unique_destination(case):
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


# ---- memory grounding (M1, M3) ------------------------------------------------

class Env:
    def model_dump(self):
        return {'package': 'com.android.settings'}


def memory_case(outcomes, expectations=None):
    """Analyses whose single step targets the one visible control, keyed like production."""
    from app.services.app_task_experience import digest, screen_key, target_key
    req = request('com.android.settings', nodes=[dict(id='n0', label='디스플레이', role='button', clickable=True,
                                                     bounds=[0, 0, 500, 80])])
    req.goal = '디스플레이 설정 화면을 열어줘'
    node = req.screen.nodes[0]
    memories = []
    for i, outcome in enumerate(outcomes):
        kind = (expectations or ['screen_changed'] * len(outcomes))[i]
        step = dict(environment=Env().model_dump(), goal_key=digest(req.goal), subgoal_key='', screen_key=screen_key(req.screen),
                    after_key='f' * 64, after_package='com.android.settings', outcome=outcome, sequence=1,
                    observed_at_ms=1000 + i, received_at_ms=1000 + i, path_prefix=[], has_initial_context=True,
                    effect_evidence=None,
                    action=dict(tool='tap', target_key=target_key(node), expectation=kind, expected_package='', role='button',
                                advances_subgoal=False))
        advice = 'choose_another_native_target' if outcome == 'rejected' else 'prefer_observed_capability'
        memories.append((f't{i}', dict(goal_key=digest(req.goal), steps=[step], route=[step] if outcome == 'verified' else [],
                                       user_confirmed=outcome == 'verified',
                                       tactics=[dict(tool='tap', role='button', advice=advice)])))
    return req, memories


def ground(req, memories, **flags):
    from app.schemas.app_task_learning import TaskMemoryResponse
    result = TaskMemoryResponse(request_id='r', observation_id='o', fingerprint='a' * 64)
    return AppTaskExperienceService._ground(result, req, Env(), memories, **flags)


def test_m1_executor_refusals_stop_poisoning_recall_only_when_enabled():
    # Two refusals arrive AFTER two successes: today they invalidate the route and add an avoid tactic.
    req, memories = memory_case(['verified', 'verified', 'rejected', 'rejected'])
    before = ground(req, memories)
    assert before.action is None and before.evidence[0]['rejections'] == 2
    assert any(t['advice'] == 'choose_another_native_target' for t in before.tactics)
    after = ground(req, memories, system_rejections_neutral=True)
    assert after.status == 'remembered' and after.action.target_id == 'n0'
    assert after.evidence[0]['rejections'] == 0
    assert not any(t['advice'] == 'choose_another_native_target' for t in after.tactics)


def test_m3_replayed_expectation_no_longer_splits_support_only_when_enabled():
    req, memories = memory_case(['verified'] * 4, ['target_visible', 'target_visible', 'screen_changed', 'screen_changed'])
    assert ground(req, memories).action is None  # two competing groups of two
    merged = ground(req, memories, merge_visible_expectation=True)
    assert merged.status == 'remembered' and merged.action.target_id == 'n0'


@pytest.mark.parametrize('mode', ['goal_first', 'next_action'])
def test_triage_answers_with_the_read_only_contract_the_device_validates(mode):
    req = arrived(TITLE, MENU)
    req.state.subgoals = [TaskSubgoal(id='g0', title='설정 열기', status='verified'), TaskSubgoal(id='g1', title='디스플레이 열기')]
    req.planning_mode = mode
    result = AppTaskService(sender()).step(req, completion_triage=True)
    assert result.status == 'confirm_result' and result.action is None and result.completion is not None
    assert result.subgoals == req.state.subgoals and result.active_subgoal == 'g1'
    assert result.diagnostic.get('goal_assessment') == ('satisfied' if mode == 'goal_first' else None)
