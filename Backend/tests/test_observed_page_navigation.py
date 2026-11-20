"""Actual native page changes retire stale guidance without turning navigation into failure."""
from dataclasses import replace
import pytest
from ai_agent.runtime.primary import observe_page_navigation
from ai_agent.runtime.state_manager import build_screen_snapshot
from ai_agent.runtime.types import NormalizedNode
from tests.test_primary_recovery import Scenario,config
from tests.test_settings_stability_matrix import Run, node, GOALS

@pytest.mark.parametrize('setting,direction',GOALS)
def test_known_page_change_releases_stale_recovery_without_retry_button(setting,direction):
    run=Run(setting,direction);run.edge=run.home_edge;first=run.observe(run.home)
    run.engine.session_store.update('s',recovery_state={'waiting_for_user':True,'reason_code':'MODEL_ABSTAINED','scope':'selection:S1_SETTINGS_HOME'},
        total_failed_attempts=1,transition_attempts={'spent':1})
    page=run.home_edge.to_state
    run.edge=next(t for t in run.graph.transitions if t.from_state==page)
    title=run.graph.states[page].required_keywords[0] if run.graph.states[page].required_keywords else run.graph.states[page].required_keyword_groups[0][0]
    result=run.observe((node('title',title),node('next',run.edge.target_keywords[0],slider=run.edge.target_role=='slider')))
    assert not result.debug['ui_selection'].get('paused',False)
    assert len(run.calls)==2
    state=run.engine.session_store.get('s')
    assert state.total_failed_attempts==1 and state.transition_attempts=={'spent':1}
    assert not state.task_completed


def test_same_heading_layout_and_value_changes_do_not_unlock_recovery():
    scenario=Scenario(['insufficient_evidence'])
    for _ in range(scenario.graph.policy.recovery_limit):scenario.observe()
    old=scenario.engine.session_store.get('s');calls=len(scenario.calls)
    moved=tuple(replace(n,bounds_in_screen=(20,400,900,500)) if n.node_id=='display' else n for n in scenario.nodes)
    scenario.observe(nodes=moved)
    assert len(scenario.calls)==calls
    assert scenario.engine.session_store.get('s').recovery_state['waiting_for_user']


def test_correlated_tap_survives_partial_destination_before_native_heading_settles():
    from ai_agent.runtime.types import UserActionEvent, UserActionType
    run=Run('font_size','increase');run.edge=run.home_edge;first=run.observe(run.home)
    event=UserActionEvent(UserActionType.TAP_COMPLETED,first.guide_command.guide_command_id,
        first.guide_command.target_node_id,occurred_at_ms=run.at+500,event_id='native-tap',episode_id='e',session_id='s')
    run.observe((node('title','디스플레이'),),event=event)
    pending=run.engine.session_store.get('s').pending_attempt
    assert pending and pending.get('event_key')
    original=pending['attempt_id']
    run.edge=next(t for t in run.graph.transitions if t.from_state==run.home_edge.to_state)
    result=run.observe((node('title','디스플레이'),node('next',run.edge.target_keywords[0])))
    assert result.verification_result.status.value=='SUCCESS'
    assert result.verification_result.attempt_id==original


def test_unknown_or_synthetic_heading_and_exhausted_action_budget_cannot_reopen():
    run=Run('font_size','increase');run.edge=run.home_edge;run.observe(run.home)
    session=run.engine.session_store.get('s')
    title=NormalizedNode('title','디스플레이','text',bounds=(20,20,900,80),package_name='com.android.settings',window_id=7)
    snapshot=build_screen_snapshot('S2_DISPLAY_MENU',(title,),observation_id='next',captured_at_ms=2000)
    assert observe_page_navigation(session,snapshot,(replace(title,source_confidence=.99),),run.graph) is session
    exhausted=replace(session,total_failed_attempts=run.graph.policy.max_failed_attempts)
    assert observe_page_navigation(exhausted,snapshot,(title,),run.graph) is exhausted
    assert observe_page_navigation(session,replace(snapshot,page_fingerprint=None),(title,),run.graph) is session
