"""Speech-like requests must resolve to a real approved graph before any execution."""
from unittest.mock import Mock
import pytest
from tests.test_task_outcomes import Flow
from tests.test_settings_execution import CATALOG, tree

SWITCHES = [g for g in CATALOG.values() if g['target_app'] == 'android_settings'
    and g.get('slots', {}).get('state') in {'on', 'off'}]

@pytest.mark.parametrize('goal', SWITCHES, ids=lambda g: g['goal_type'])
def test_conversational_switch_request_selects_its_approved_graph_without_model(goal):
    state = goal['slots']['state']
    name = goal['display_name'].removesuffix(' 켜기').removesuffix(' 끄기')
    text = name + (' 키고 싶어' if state == 'on' else ' 끄고 싶어')
    f = Flow()
    f.app.state.turn_service.goal_preparation.llm = Mock()
    f.app.state.turn_service.goal_preparation.llm.interpret_inputs.side_effect = AssertionError('Explicit switch request must not need a model')
    result = f.resolve(text)
    assert result['resolution_status'] == 'confirmation_required', result
    assert result['goal']['goal_type'] == goal['goal_type']
    assert result['goal']['slots']['state'] == state
    if goal['task_type'] == 'settings_change':
        assert result['preparation']['values']['state'] == state
    assert result['preparation']['question'] is None
    assert result['preparation']['graph_reference']

@pytest.mark.parametrize('text', ['다크모드 키고 싶어', '다크 모드를 켜고 싶어', '다크모드 켜줘'])
def test_dark_mode_utterance_reaches_same_pinned_graph_on_start(text):
    f = Flow()
    result = f.resolve(text)
    assert result['resolution_status'] == 'confirmation_required', result
    ref = result['preparation']['graph_reference']
    assert ref['task_id'] == 'SET-dark_mode-enable'
    started = f.send('start_task', preparation_version='goal-inputs.v1',
        goal_resolution_id=result['preparation']['id'], source_text=text, goal=result['goal'],
        device_context={'platform': 'android', 'locale': 'ko-KR'},
        accessibility_tree=tree('settings.dark_mode.enable', False))
    assert started['graph_reference'] == {k:ref[k] for k in ('task_id','version','sha256')}
    assert started['task_status'] == 'WAITING_CONFIRMATION'
    assert started['guide_command']['target_node_id'] == 'master'

@pytest.mark.parametrize('text', ['다크모드 키고 싶지 않아', '다크모드 끄지 마', '다크모드 켜지 말아줘'])
def test_negative_switch_request_never_yields_an_executable_goal(text):
    result = Flow().resolve(text)
    assert result['goal'] is None
    assert result['preparation']['graph_reference'] is None
