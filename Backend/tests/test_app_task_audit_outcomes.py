"""Galaxy Note20 audit 2026-11-22: correct arrivals that were never provable, and one false one."""
import json
from types import SimpleNamespace

import pytest

from app.schemas.app_task import OutcomeClause, OutcomeGoal, TaskApp, TaskCompletion, TaskResultCheck
from app.services import app_task_outcome as outcome
from app.services.app_task import AppTaskService, triage_completion
from tests.test_app_task_goal_grounding import unseeded


def clock_tabs(selected):
    return [dict(label=name, role='tab', clickable=name != selected, selected=name == selected)
            for name in ('알람', '세계시각', '스톱워치', '타이머')]


def test_the_app_the_user_named_is_not_a_page_to_reach():
    # Sealed live as page ['시계','Clock'] + selected ['세계시각','World Clock']: never provable.
    clauses = [OutcomeClause(kind='page', labels=['시계', 'Clock']),
               OutcomeClause(kind='selected', labels=['세계시각', 'World Clock'])]
    effective = outcome.effective(clauses, '시계에서 세계시각 탭으로 이동해줘')
    assert [(c.kind, c.labels) for c in effective] == [('selected', ['세계시각', 'World Clock'])]
    # The sealed contract itself is untouched.
    assert clauses[0].labels == ['시계', 'Clock']


def test_the_destination_the_user_said_counts_when_the_contract_is_only_english():
    request = unseeded('시계에서 세계시각 탭으로 이동해줘', clock_tabs('세계시각'))
    clauses = outcome.effective([OutcomeClause(kind='page', labels=['World Clock'])], request.goal)
    proof = TaskCompletion(checks=[TaskResultCheck(package=request.screen.package, label='세계시각',
                                                   role='tab', selected=True)])
    assert outcome.covers(clauses, proof, request.screen, observed=True)
    # A different, unselected tab is still not arrival.
    elsewhere = unseeded('시계에서 세계시각 탭으로 이동해줘', clock_tabs('알람'))
    assert not outcome.covers(clauses, proof, elsewhere.screen, observed=True)


@pytest.mark.parametrize('selected', [True, False])
def test_a_selected_bottom_navigation_button_proves_its_page(selected):
    # YouTube exports its 'Shorts' destination as a button, never as a tab.
    request = unseeded('YouTube에서 Shorts 탭으로 이동해줘',
                       [dict(label='홈', role='button', clickable=True, selected=not selected),
                        dict(label='Shorts', role='button', clickable=True, selected=selected)],
                       package='com.google.android.youtube')
    request.apps = [TaskApp(package='com.google.android.youtube', label='YouTube')]
    clauses = outcome.effective([OutcomeClause(kind='page', labels=['Shorts'])], request.goal)
    proof = TaskCompletion(checks=[TaskResultCheck(package=request.screen.package, label='Shorts',
                                                   role='button', selected=True)])
    assert outcome.covers(clauses, proof, request.screen, observed=True) is selected
    triaged = triage_completion(request.goal, request.screen, request.screen.nodes, request.apps)
    assert (triaged is not None) is selected
    if triaged:
        assert triaged.checks[0].role == 'button' and triaged.checks[0].selected is True


def test_an_unselected_button_with_the_destination_name_is_still_a_way_to_reach_it():
    request = unseeded('YouTube에서 Shorts 탭으로 이동해줘',
                       [dict(label='Shorts', role='button', clickable=True, selected=False),
                        dict(label='Shorts', role='text')], package='com.google.android.youtube')
    request.apps = [TaskApp(package='com.google.android.youtube', label='YouTube')]
    assert triage_completion(request.goal, request.screen, request.screen.nodes, request.apps) is None


def _reply(clauses):
    raw = json.dumps(dict(clauses=clauses))
    return SimpleNamespace(status_code=200, content=json.dumps(dict(
        status='completed', model='qwen/qwen3.8-flash',
        output=[dict(type='message', role='assistant', content=[dict(type='output_text', text=raw)])])).encode())


def test_the_app_name_is_removed_before_the_contract_is_sealed():
    # Sealed live as page ['YouTube','검색창']; the YouTube logo then "proved" it on the home screen.
    request = unseeded('YouTube 검색창을 열어줘', [dict(label='YouTube', role='text'),
                                              dict(label='검색', role='button', clickable=True)],
                       package='com.google.android.youtube')
    request.apps = [TaskApp(package='com.google.android.youtube', label='YouTube')]
    request.prepare_outcome = True
    service = AppTaskService(lambda payload, timeout: _reply([dict(kind='page', labels=['YouTube', '검색창'])]))
    compiled = service.step(request).outcome_goal
    assert compiled and [c.labels for c in compiled.clauses] == [['검색창']]


def test_a_contract_that_only_names_the_app_is_not_sealed():
    request = unseeded('YouTube 열어줘', [dict(label='YouTube', role='text')], package='com.google.android.youtube')
    request.apps = [TaskApp(package='com.google.android.youtube', label='YouTube')]
    request.prepare_outcome = True
    service = AppTaskService(lambda payload, timeout: _reply([dict(kind='page', labels=['YouTube'])]))
    result = service.step(request)
    assert result.outcome_goal is None and result.diagnostic['reason_code'] == 'outcome_app_only'
