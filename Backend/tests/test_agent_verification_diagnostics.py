"""Common result evidence survives incomplete verification for all reversible settings."""
from dataclasses import replace
import pytest
from tests.test_settings_stability_matrix import Run,GOALS

@pytest.fixture(autouse=True)
def configured_jev(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    monkeypatch.setenv('JEV_MODEL','jev-1.14.0')


@pytest.mark.parametrize('setting,direction',GOALS)
def test_missing_action_remains_unverified_with_its_actual_reason(setting,direction):
    run=Run(setting,direction);run.route()
    sign=1 if direction=='increase' else -1
    changed=(run.control[0],replace(run.control[1],extras={'progress':3+sign,'min':0,'max':7}))
    key={'font_size':'font_scale','brightness':'screen_brightness','volume':'media_volume'}[setting]
    before=run.applied[key]
    result=run.observe(changed,applied={**run.applied,key:before+sign*(.2 if setting=='font_size' else 1)})
    state=run.engine.session_store.get('s')
    assert not state.task_completed
    assert state.technical_outcome['status']=='UNVERIFIABLE'
    assert state.technical_outcome['reason']=='no_correlated_action'
    assert state.technical_outcome['setting']==setting
    assert state.technical_outcome['requested_direction']==direction


@pytest.mark.parametrize('setting,direction',GOALS)
def test_settled_opposite_change_is_distinguished_from_no_change(setting,direction):
    from ai_agent.runtime.types import UserActionEvent,UserActionType
    run=Run(setting,direction);approved=run.route()
    sign=1 if direction=='increase' else -1
    changed=(run.control[0],replace(run.control[1],extras={'progress':3-sign,'min':0,'max':7}))
    key={'font_size':'font_scale','brightness':'screen_brightness','volume':'media_volume'}[setting]
    applied={**run.applied,key:run.applied[key]-sign*(.2 if setting=='font_size' else 1)}
    event=UserActionEvent(UserActionType.DRAG_COMPLETED,approved.guide_command.guide_command_id,
        'target',occurred_at_ms=run.at+500,event_id='opposite',episode_id='e',session_id='s')
    run.observe(changed,event=event,applied=applied)
    run.observe(changed,applied=applied)
    state=run.engine.session_store.get('s')
    assert not state.task_completed
    assert state.technical_outcome['status']=='NOT_ACHIEVED'
    assert state.technical_outcome['reason']=='target_value_changed_opposite_direction'


@pytest.mark.parametrize('setting,direction', GOALS)
@pytest.mark.parametrize('first_signal', ['control', 'system'])
def test_delayed_second_value_verifies_original_action_after_checkpoint(setting, direction, first_signal):
    from ai_agent.runtime import ClickAgentEngine, UserActionEvent, UserActionType
    from app.clients.checkpoint import capture, restore

    run = Run(setting, direction)
    guide = run.route().guide_command
    calls = len(run.calls)
    sign = 1 if direction == 'increase' else -1
    changed = (run.control[0], replace(run.control[1], extras={'progress': 3 + sign, 'min': 0, 'max': 7}))
    key = {'font_size': 'font_scale', 'brightness': 'screen_brightness', 'volume': 'media_volume'}[setting]
    applied = {**run.applied, key: run.applied[key] + sign * (.2 if setting == 'font_size' else 1)}
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, guide.guide_command_id, 'target',
        occurred_at_ms=run.at + 500, event_id='drag', episode_id='e', session_id='s')
    partial_control = changed if first_signal == 'control' else run.control
    partial_system = applied if first_signal == 'system' else run.applied
    for action in (event, None):
        result = run.observe(partial_control, event=action, applied=partial_system)
        assert result.verification_result.status.value == 'WAITING_FOR_OBSERVATION'
        assert result.verification_result.evidence == 'setting_value_signals_disagree'
        assert not result.debug['task_completed'] and result.guide_command.action_type.value == 'observe_only'
    saved = capture(run.engine.session_store, 's', run.graph)
    run.engine = ClickAgentEngine(session_store=restore(saved), task_graphs={run.goal.task_id: run.graph},
                                  screen_resolver=run.resolver)
    # Two signals still disagree after the observation deadline. Neither a success nor a failed action.
    run.at += run.graph.policy.observation_timeout_ms
    timed_out = run.observe(partial_control, applied=partial_system)
    assert timed_out.verification_result.status.value == 'UNKNOWN'
    assert not timed_out.debug['task_completed']
    assert run.engine.session_store.get('s').total_failed_attempts == 0
    assert run.observe(changed, applied=applied).verification_result.task_completed
    proof = run.engine.session_store.get('s').technical_outcome
    assert proof['guide_command_id'] == guide.guide_command_id
    assert len(run.calls) == calls


def test_result_prompt_explains_missing_action_without_claiming_a_change():
    from app.services.task_outcome import make_outcome
    outcome=make_outcome({'status':'UNVERIFIABLE','reason':'no_correlated_action'},123,setting='brightness')
    assert '조작 기록' in outcome['prompt']['message']
    assert '확인하지 못했어요' in outcome['prompt']['message']
    assert outcome['technical']['status']=='UNVERIFIABLE'



def test_all_approved_catalog_entries_identify_their_registered_graph_for_resume():
    import json
    from pathlib import Path
    from app.task_graphs import approved_graphs
    catalog=json.loads((Path(__file__).parents[1]/'app/task_graphs/goal_catalog.json').read_text())
    graphs=approved_graphs()
    approved=[g for g in catalog['goals'] if g['execution_status']=='approved']
    assert approved, "The approved goal catalog must not be empty"
    for goal in approved:
        assert goal['graph_id']==graphs[goal['goal_type']].definition.task_id
