"""Start with only the user's goal and public controls, as the first phone request does."""
import pytest

from app.schemas.app_task import TaskApp, TaskCompletion, TaskResultCheck
from app.services.app_task import AppTaskService
from app.services.app_task_completion import completion_checks_schema
from app.services.app_task_jev_completion import JevCompletionResult
from tests.test_app_task import compact_sender, request, sender


@pytest.fixture(autouse=True)
def provider(monkeypatch):
    monkeypatch.setenv('APP_TASK_PROVIDER', 'gemini')
    monkeypatch.setenv('GEMINI_SETTINGS_MODEL', 'gemini-3.8-flash')


def unseeded(goal, nodes, package='org.example.clock'):
    req = request(package, nodes=[dict(id=f'n{i}', bounds=[0, 90*i, 400, 90*i+80], **n) for i, n in enumerate(nodes)])
    req.goal = goal
    req.apps = [TaskApp(package=package, label='테스트 앱')]
    req.planning_mode = 'goal_first'
    assert req.state.completion is None and not req.state.subgoals
    return req


def check(target, *, selected='unknown', checked='unknown'):
    return dict(target=target, label='', app='none', checked=checked, selected=selected)


def tabs(label, selected=False):
    return [dict(label='기록', role='tab', clickable=selected, selected=not selected),
            dict(label=label, role='tab', clickable=not selected, selected=selected, resource='id/tab')]


@pytest.mark.parametrize('goal,label,package', [
    ('시계에서 스톱워치 탭을 열어줘', '스톱워치', 'org.example.clock'),
    ('Open the Reading List tab', 'Reading List', 'org.example.reader'),
])
@pytest.mark.parametrize('raw', [[], [check('c1', selected='true', checked='true')]])
def test_missing_or_redundant_flag_sends_the_requested_tab_before_and_after(goal, label, package, raw):
    req = unseeded(goal, tabs(label), package)
    plan = sender(goal_status='not_satisfied', target='c1', expected_selected='true', completion_checks=raw)
    proposal = AppTaskService(plan).step(req, completion_triage=True)
    plan.assert_called_once()
    assert proposal.status == 'proposed' and proposal.action.target_id == 'n1'
    expected, = proposal.completion.checks
    assert (expected.package, expected.label, expected.role, expected.selected, expected.checked) == (package, label, 'tab', True, None)
    assert proposal.diagnostic['completion_grounding'] == 'grounded_native_goal'
    # Also exercise the model path when early triage is disabled.
    req = unseeded(goal, tabs(label, selected=True), package)
    req.planning_mode = 'result_only'
    result = AppTaskService(compact_sender(dict(completion_checks=raw))).step(req)
    assert result.status == 'confirm_result' and result.completion.checks == [expected] and result.action is None


@pytest.mark.parametrize('raw', [
    [], [check('c0', selected='true')], [check('c1', selected='false')],
    [check('c1', selected='true', checked='true')],
])
def test_sibling_or_unmet_state_cannot_settle_read_only_discovery(raw):
    req = unseeded('스톱워치 탭을 열어줘', tabs('스톱워치'))
    req.planning_mode = 'result_only'
    result = AppTaskService(compact_sender(dict(completion_checks=raw))).step(req)
    assert result.status == 'needs_input' and result.completion is None and result.deferred_completion is None
    assert result.action is None


def test_jev_cannot_confirm_a_different_selected_tab(monkeypatch):
    req = unseeded('스톱워치 탭으로 이동해줘', tabs('스톱워치'))
    req.planning_mode = 'result_only'
    sibling = TaskResultCheck(package=req.screen.package, label='기록', role='tab', selected=True)
    monkeypatch.setenv('APP_TASK_COMPLETION_PROVIDER', 'jev')
    monkeypatch.setattr('app.services.app_task.jev_completion', lambda *a, **kw:
                        JevCompletionResult(TaskCompletion(checks=[sibling]), {'called': True, 'completion_provider': 'jev'}))
    result = AppTaskService().step(req, completion_triage=True)
    assert result.completion is None and result.status == 'needs_input'


def test_explicit_tab_request_needs_its_native_selection_even_with_a_matching_heading():
    req = unseeded('스톱워치 탭으로 이동해줘', [dict(label='스톱워치', role='text', native_heading=True)])
    req.planning_mode = 'result_only'
    send = compact_sender(dict(completion_checks=[check('c0')]))
    result = AppTaskService(send).step(req, completion_triage=True)
    send.assert_called_once()
    assert result.completion is None and result.diagnostic['completion_grounding'] == 'deferred_goal_mismatch'


def test_repair_never_discards_a_second_clause_or_changes_a_requested_flag():
    req = unseeded('스톱워치 탭을 열어줘', tabs('스톱워치'))
    for raw in ([check('c1', selected='true', checked='true'), check('c0')],
                [check('c1', selected='false', checked='true')]):
        result = AppTaskService(sender(goal_status='not_satisfied', target='c1', completion_checks=raw)).step(req)
        assert result.status == 'proposed' and result.completion is None and result.deferred_completion is None


@pytest.mark.parametrize('case', ['compound', 'duplicate', 'other_app', 'ocr'])
def test_no_unambiguous_native_goal(case):
    req = unseeded('스톱워치 탭을 열고 측정을 시작해줘' if case == 'compound' else '스톱워치 탭을 열어줘', tabs('스톱워치'))
    if case == 'duplicate':
        req.screen.nodes[0].label = '스톱워치'
    if case == 'other_app':
        req.apps[0].package = 'org.example.other'
    if case == 'ocr':
        req.screen.nodes[1].source = 'ocr'
    result = AppTaskService(sender(goal_status='not_satisfied', target='c1', completion_checks=[])).step(req)
    assert result.completion is None and result.deferred_completion is None


@pytest.mark.parametrize('visible', [True, False])
def test_menu_proposal_carries_only_the_requested_future_page(visible):
    label = '앱 알림' if visible else '알림'
    req = unseeded('앱 알림 화면을 열어줘', [dict(label=label, role='button', clickable=True, resource='id/menu_row')])
    result = AppTaskService(sender(goal_status='not_satisfied', completion_checks=[])).step(req)
    assert result.status == 'proposed' and result.action.target_id == 'n0' and result.completion is None
    future, = result.deferred_completion.checks
    assert (future.package, future.label, future.role, future.resource) == (req.screen.package, '앱 알림', 'text', '')


def test_a_menu_row_suggested_as_proof_supplies_only_its_future_title():
    req = unseeded('보관함 화면을 열어줘', [dict(label='보관함', role='button', clickable=True, resource='id/entry')])
    result = AppTaskService(sender(goal_status='not_satisfied', completion_checks=[check('c0')])).step(req)
    assert result.status == 'proposed' and result.completion is None
    title, = result.deferred_completion.checks
    assert (title.label, title.role, title.resource) == ('보관함', 'text', '')


def test_native_title_and_selected_tab_can_settle_without_action_history():
    for node, goal in ((dict(label='보관함', role='text', native_heading=True), '보관함 화면을 열어줘'),
                       (tabs('즐겨찾기', selected=True)[1], '즐겨찾기 탭을 선택해줘')):
        req = unseeded(goal, [node])
        send = sender()
        result = AppTaskService(send).step(req, completion_triage=True)
        send.assert_not_called()
        assert result.status == 'confirm_result' and result.diagnostic['goal_assessment'] == 'satisfied'
        assert result.completion.checks[0].label == node['label'] and result.action is None


@pytest.mark.parametrize('raw', [[], [check('c1', checked='false')], [check('c0')]])
def test_generic_switch_preserves_its_heading_and_the_requested_off_state_before_a_tap(raw):
    req = unseeded('자동 동기화를 꺼줘', [dict(label='자동 동기화', role='text', native_heading=True),
                              dict(label='사용', role='toggle', clickable=True, checked=True)])
    result = AppTaskService(sender(goal_status='not_satisfied', target='c1', completion_checks=raw)).step(req, completion_triage=True)
    assert result.status == 'proposed'
    assert [(c.label, c.checked) for c in result.completion.checks] == [('자동 동기화', None), ('사용', False)]


def test_schema_limits_each_targets_flags_and_distinguishes_future_from_observed_states():
    req = unseeded('스톱워치 탭을 열어줘', [*tabs('스톱워치'), dict(label='사용', role='toggle', checked=False),
                                      dict(label='제목', role='text')])
    indexed = {f'c{i}': node for i, node in enumerate(req.screen.nodes)}
    for read_only in (True, False):
        item = completion_checks_schema(indexed, ['none'], result_only=read_only)['items']
        by_id = {alias: branch['properties'] for branch in item['anyOf'] for alias in branch['properties']['target']['enum']}
        assert by_id.keys() == {*indexed, 'none'}
        assert all(by_id[key]['checked']['enum'] == ['unknown'] for key in ('none', 'c0', 'c1', 'c3'))
        assert by_id['c3']['selected']['enum'] == ['unknown']
        assert ('true' in by_id['c1']['selected']['enum']) is (not read_only)
        assert ('true' in by_id['c2']['checked']['enum']) is (not read_only)
