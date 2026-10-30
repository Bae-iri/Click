from dataclasses import replace
from ai_agent.runtime import UserActionType
from ai_agent.telemetry.execution_events import produce
from ai_agent.graph_proposal.ingestion.runtime_events import label_event
from test_runtime_reliability import Turns
from test_engine_flow import _node


def test_back_navigation_retires_pending_attempt_without_failure():
    turns=Turns();first=turns.send(at=1000)
    event=turns.event(first,at=1200,action=UserActionType.BACK_NAVIGATED)
    result=turns.send(at=2000,event=event)
    assert not result.verification_result or not result.verification_result.count_attempt
    assert turns.session.total_failed_attempts==0
    assert turns.session.typed_goal.goal_type=='settings.font_size.increase'


def test_observed_home_resets_progress_and_keeps_goal():
    turns=Turns();first=turns.send(at=1000)
    turns.engine.session_store.update('s',observed_path=('S1_SETTINGS_HOME','S2_DISPLAY_MENU'),
        transition_attempts={'old':2},total_failed_attempts=2,failed_node_ids=('old',))
    before=turns.session
    launcher=replace(_node('settings','설정', bounds=(0,0,100,100)),package_name='com.android.launcher3')
    event=turns.event(first,at=1200,action=UserActionType.HOME_NAVIGATED)
    result=turns.send((launcher,),at=2000,event=event)
    assert turns.session.current_state_id=='S0_UNKNOWN'
    assert 'S2_DISPLAY_MENU' not in turns.session.observed_path
    assert turns.session.total_failed_attempts==0
    assert not turns.session.transition_attempts
    assert turns.session.typed_goal.goal_type==before.typed_goal.goal_type
    evidence=produce(result,before,turns.session,navigation_action='home_navigated')
    assert evidence['count_attempt'] is False
    assert evidence['reason_code']=='user_home_navigation'
    assert label_event({'runtime':evidence})['label']=='navigation_recovery'


def test_home_claim_without_visible_launcher_never_resets_path():
    turns=Turns();first=turns.send(at=1000)
    turns.engine.session_store.update('s',observed_path=('S1_SETTINGS_HOME','S2_DISPLAY_MENU'),total_failed_attempts=1)
    event=turns.event(first,at=1200,action=UserActionType.HOME_NAVIGATED)
    turns.send(at=2000,event=event)
    assert turns.session.total_failed_attempts==1


def test_duplicate_or_cross_session_navigation_does_not_reapply_reset():
    from ai_agent.runtime.navigation import apply_navigation
    from ai_agent.runtime.types import AgentTurnRequest
    turns=Turns();first=turns.send(at=1000)
    event=turns.event(first,at=1200,event_id='back',action=UserActionType.BACK_NAVIGATED)
    request=AgentTurnRequest('nav','s',raw_goal=None,accessibility_tree=(),user_action_event=event)
    before=turns.session
    updated=apply_navigation(turns.engine.session_store,request,before)
    assert updated is not before
    assert updated.pending_attempt is None
    assert apply_navigation(turns.engine.session_store,request,updated) is updated
    wrong=replace(request,user_action_event=replace(event,session_id='another-session',event_id='wrong'))
    assert apply_navigation(turns.engine.session_store,wrong,updated) is updated


def test_recents_launcher_window_is_not_home_evidence():
    from ai_agent.runtime.navigation import apply_navigation
    from ai_agent.runtime.types import AgentTurnRequest
    turns=Turns();first=turns.send(at=1000)
    event=turns.event(first,at=1200,action=UserActionType.HOME_NAVIGATED)
    node=replace(_node('overview','최근 앱'),package_name='com.android.launcher3',class_name='RecentsView')
    request=AgentTurnRequest('recents','s',raw_goal=None,accessibility_tree=(node,),user_action_event=event)
    assert apply_navigation(turns.engine.session_store,request,turns.session) is turns.session
