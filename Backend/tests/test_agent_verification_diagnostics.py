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
