"""Opt-in retry must stay bounded across checkpoint cycles and unrelated sensor drift."""
from types import SimpleNamespace
from dataclasses import replace
from hashlib import sha256
import pytest
from tests.test_primary_recovery import Scenario
from ai_agent.runtime import ClickAgentEngine
from ai_agent.runtime.primary import threshold_reobserve
from ai_agent.runtime.types import AgentTurnRequest, DeviceContext
from app.clients.checkpoint import capture, restore


def test_retry_budget_survives_settings_drift_and_checkpoint(monkeypatch):
    monkeypatch.setenv('CLICK_JEV_THRESHOLD_REOBSERVE', '1')
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')
    s = Scenario([(.72, .81)])
    for i in range(8):
        session = s.engine.session_store.get('s')
        result = s.engine.handle_turn(AgentTurnRequest(request_id=f'r{i}', session_id='s',
            raw_goal=None, typed_goal=s.goal if session is None else None,
            accessibility_tree=s.nodes, device_context=DeviceContext('android'),
            screen_bounds=(0,0,1080,2400), active_window_id=7, observation_id=f'o{i}',
            captured_at_ms=(i+1)*1000, applied_settings={'screen_brightness': 50+i}))
        assert result.guide_command.target_node_id is None
        if i == 0:
            assert result.debug['ui_selection']['threshold_reobserve']
            failure = result.debug['ui_selection']['failure']
            assert failure['cause'] == 'low_confidence'
            assert failure['required_change'] == 'fresh_observation'
        state = capture(s.engine.session_store, 's', s.graph)
        assert len(state['jev_reobserve_keys']) == 1
        s.engine = ClickAgentEngine(session_store=restore(state),
            task_graphs={s.goal.task_id: s.graph}, screen_resolver=s.resolver)
    assert len(s.calls) == 2


def info(s):
    return dict(called=True, status='low_confidence', hold_reason='raw_confidence_below_minimum',
        grounded_candidate_count=1, page_target_consistent=True,
        target_support_probability=.95, minimum_confidence=.8,
        chosen_transition_hash=sha256(s.edge.transition_id.encode()).hexdigest())


@pytest.mark.parametrize('change', [dict(status='abstained'), dict(hold_reason='target_not_supported'),
    dict(hold_reason='page_target_conflict'), dict(grounded_candidate_count=2),
    dict(target_support_probability=.79), dict(called=False)])
def test_never_reroll_missing_or_ambiguous_evidence(monkeypatch, change):
    monkeypatch.setenv('CLICK_JEV_THRESHOLD_REOBSERVE', '1')
    s=Scenario(['selected'])
    assert not threshold_reobserve({**info(s), **change}, s.graph, None, 'v2:one', False)


def test_ledger_total_cap_and_scope(monkeypatch):
    monkeypatch.setenv('CLICK_JEV_THRESHOLD_REOBSERVE', '1')
    s=Scenario(['selected'])
    assert threshold_reobserve(info(s), s.graph, None, 'v2:one', False)
    assert not threshold_reobserve(info(s), s.graph,
        SimpleNamespace(jev_reobserve_keys=('v2:one',)), 'v2:one', False)
    assert not threshold_reobserve(info(s), s.graph,
        SimpleNamespace(jev_reobserve_keys=('v2:one','v2:two','v2:three')), 'v2:four', False)
    assert not threshold_reobserve(info(s), s.graph, None, 'v2:one', True)
    foreign=replace(s.graph, definition=replace(s.graph.definition,target_app='youtube'))
    assert not threshold_reobserve(info(s), foreign, None, 'v2:one', False)


def test_fresh_accepted_judgment_is_required_before_any_action(monkeypatch):
    monkeypatch.setenv('CLICK_JEV_THRESHOLD_REOBSERVE', '1')
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')
    s=Scenario([(.72,.81), 'selected'])
    first=s.observe()
    assert first.guide_command.target_node_id is None
    assert first.debug['recovery']['waiting_for'] == 'observation'
    second=s.observe()
    assert second.guide_command.target_node_id == 'display'
    assert len(s.calls) == 2
    assert len(s.engine.session_store.get('s').jev_reobserve_keys) == 1


def test_stale_observation_never_spends_an_extra_call(monkeypatch):
    monkeypatch.setenv('CLICK_JEV_THRESHOLD_REOBSERVE', '1')
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')
    s=Scenario([(.72,.81)])
    s.observe(at=1000)
    s.observe(at=1000)
    assert len(s.calls)==1


def test_diagnostic_flag_is_closed_boolean():
    from ai_agent.telemetry.trace_logger import diagnostic_payload
    assert diagnostic_payload({'ui_selection': {'threshold_reobserve': True}})['ui_selection']['threshold_reobserve'] is True
    assert 'threshold_reobserve' not in diagnostic_payload({'ui_selection': {'threshold_reobserve': 'private-text'}})['ui_selection']
    assert diagnostic_payload({'required_change':'fresh_observation'})['required_change']=='fresh_observation'


def test_default_off_checkpoint_keeps_previous_shape(monkeypatch):
    monkeypatch.delenv('CLICK_JEV_THRESHOLD_REOBSERVE',raising=False)
    monkeypatch.setenv('TYPESAFE_API_KEY','test-only-key')
    s=Scenario(['selected']);s.observe()
    state=capture(s.engine.session_store,'s',s.graph)
    assert 'jev_reobserve_keys' not in state
    assert restore(state).get('s').jev_reobserve_keys == ()


def test_explicit_task_completing_edges_do_not_expand_experimental_scope(monkeypatch):
    monkeypatch.setenv('CLICK_JEV_THRESHOLD_REOBSERVE','1')
    s=Scenario(['selected'])
    final_edge=replace(s.edge,completes_task=True)
    graph=replace(s.graph,transitions=tuple(final_edge if e==s.edge else e for e in s.graph.transitions))
    assert not threshold_reobserve(info(s),graph,None,'v2:one',False)
    navigation=replace(graph,definition=replace(graph.definition,task_type='settings_navigation'))
    assert not threshold_reobserve(info(s),navigation,None,'v2:one',False)
