"""AI2 route reuse: learned route -> Gemini -> approved-graph fallback, behind an explicit flag.

Controlled traces only; these are not real device successes.
"""
from copy import deepcopy
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import Mock
from uuid import uuid4

import pytest
from sqlalchemy import select

from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.proposals.settings_route import generate_route, IncompleteSettingsTrace
from app.services.settings_experiment import SettingsExperimentResolver, trace
from app.services.settings_planner import navigation_key
from tests.test_settings_planning_experiment import Planner
from tests.test_settings_stability_matrix import Run
from tests.test_settings_graph_learning import controlled_records, evidence_db  # noqa: F401 (fixture)

SCOPE = 'c' * 64


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')
    monkeypatch.setenv('AI1_UI_SELECTOR_PROVIDER', 'jev')
    monkeypatch.setenv('CLICK_SETTINGS_ROUTE_REUSE_ENABLED', '1')


def learned_route(run, **compatibility):
    from app.clients.checkpoint import graph_version
    from app.clients.runtime_agent import runtime_configuration
    configuration = runtime_configuration()
    steps = [navigation_key(run.home_edge.to_state)]
    if run.setting in {'font_size', 'volume'}:
        second = next(t for t in run.graph.transitions if t.from_state == run.home_edge.to_state)
        steps.append(navigation_key(second.to_state))
    return dict(route_hash='r' * 64, goal_type=run.graph.definition.goal_type, start_state='S1_SETTINGS_HOME',
        subgoals=steps + ['adjust_setting'], support=2, compatibility={**dict(scope_hash=SCOPE,
            base_graph_hash=graph_version(run.graph), runtime_code_hash=configuration['code_hash'],
            jev_model=configuration.get('model')), **compatibility})


def wrapped(run, planner, routes):
    wrapper = SettingsExperimentResolver(run.resolver, planner, routes)
    wrapper.prepare(None, 'llm', 'e')
    wrapper.state['scope_hash'] = SCOPE
    run.engine.screen_resolver = wrapper
    return wrapper


def complete(run, approved):
    from ai_agent.runtime.types import UserActionEvent, UserActionType
    changed = (run.control[0], replace(run.control[1], extras={'progress': 4, 'min': 0, 'max': 7}))
    pending = run.engine.session_store.get('s').pending_attempt
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, pending['guide_command_id'], 'target',
        occurred_at_ms=run.at + 500, event_id='changed', episode_id='e', session_id='s')
    run.observe(changed, event=event, applied={**run.applied, 'font_scale': 1.3})
    return run.engine.session_store.get('s').task_completed


def test_validated_route_replaces_the_planner_call_and_still_requires_native_effect():
    run = Run('font_size', 'increase')
    planner = Planner()
    wrapper = wrapped(run, planner, [learned_route(run)])
    approved = run.route()
    assert planner.calls == []
    assert wrapper.state['plan_source'] == 'learned_route' and wrapper.state['route_hash'] == 'r' * 64
    assert not run.engine.session_store.get('s').task_completed
    assert complete(run, approved)


@pytest.mark.parametrize('change', ['scope', 'code', 'disabled'])
def test_incompatible_or_disabled_route_is_not_reused(monkeypatch, change):
    run = Run('font_size', 'increase')
    planner = Planner()
    route = learned_route(run, **({'scope_hash': 'd' * 64} if change == 'scope' else
                                  {'runtime_code_hash': 'e' * 64} if change == 'code' else {}))
    if change == 'disabled':
        monkeypatch.setenv('CLICK_SETTINGS_ROUTE_REUSE_ENABLED', '0')
    wrapper = wrapped(run, planner, [route])
    run.route()
    assert len(planner.calls) == 1 and wrapper.state['plan_source'] == 'gemini'


def test_failed_reuse_is_rejected_and_gemini_replans():
    from tests.test_screen_jev import scene
    graph, _, node, goal = scene('font_size')
    run = Run('font_size', 'increase')
    planner = Planner()
    inner = Mock(last_diagnostics={'called': True, 'status': 'selected'})
    inner.resolve.return_value = None
    wrapper = SettingsExperimentResolver(inner, planner, [learned_route(run)])
    wrapper.prepare(None, 'llm', 'e')
    wrapper.state['scope_hash'] = SCOPE
    wrapper.resolve(nodes=(node,), graph=graph, goal=goal)
    assert wrapper.state['plan_source'] == 'learned_route' and planner.calls == []
    wrapper.state['replan_requested'] = True
    wrapper.resolve(nodes=(node,), graph=graph, goal=goal)
    assert wrapper.state['rejected_routes'] == ['r' * 64]
    assert len(planner.calls) == 1 and wrapper.state['plan_source'] == 'gemini'


def test_provider_outage_uses_approved_graph_only_in_reuse_mode(monkeypatch):
    from tests.test_screen_jev import scene
    graph, _, node, goal = scene('font_size')
    planner = Mock()
    planner.plan.return_value = (None, {'called': True, 'failure_kind': 'provider', 'reason_code': 'provider_unavailable'})
    inner = Mock(last_diagnostics={'called': True, 'status': 'selected'})
    inner.resolve.return_value = 'baseline-choice'
    wrapper = SettingsExperimentResolver(inner, planner)
    wrapper.prepare(None, 'llm', 'e')
    assert wrapper.resolve(nodes=(node,), graph=graph, goal=goal) == 'baseline-choice'
    assert wrapper.state['plan_source'] == 'approved_graph_fallback'
    # Later turns stay on the baseline without new planner calls.
    assert wrapper.resolve(nodes=(node,), graph=graph, goal=goal) == 'baseline-choice'
    assert planner.plan.call_count == 1


def test_fallback_and_reused_runs_are_evidence_as_marked():
    base, records = controlled_records()
    for record in records:
        record['trace']['planner'] = {'called': False, 'status': 'SKIPPED'}
    with pytest.raises(IncompleteSettingsTrace):
        generate_route(records, base)
    reused = deepcopy(records)
    for record in reused:
        record['trace'].update(plan_source='learned_route', route_hash='r' * 64)
    assert generate_route(reused, base)['transitions'][-1]['completes_task']
    for record in reused:
        record['trace']['plan_source'] = 'approved_graph_fallback'
    with pytest.raises(IncompleteSettingsTrace):
        generate_route(reused, base)


def add_episode(factory, user, now, session, records):
    from app.models.agent_session import AgentSession
    from app.models.runtime_event import RuntimeEvent
    base, _ = controlled_records()
    with factory() as db:
        db.add(AgentSession(id=session, user_id=user, status='COMPLETED', version=len(records), episode_id='episode',
            accepted_goal_type=base['definition']['goal_type'], created_at=now, updated_at=now, expires_at=now + 600))
        db.flush()
        for sequence, record in enumerate(records, 1):
            db.add(RuntimeEvent(id=str(uuid4()), session_id=session, user_id=user, episode_id='episode',
                sequence=sequence, request_id=f'{session}{sequence}', graph_hash=fingerprint(base), kind='turn',
                payload=dict(source_kind='runtime', settings_experiment=record['trace'], runtime=record['runtime'],
                    graph_reference={'sha256': fingerprint(base), 'version': '1.0.0'}), created_at=now, expires_at=now + 600))
        db.commit()


def test_route_needs_independent_support_and_failures_quarantine_it(evidence_db, monkeypatch):
    from app.repositories.users import UserRepository
    from app.services.settings_graphs import reusable_routes, route_inventory
    from scripts.settings_graph_learning import SettingsGraphLearningService
    factory, user, now = evidence_db
    learner = SettingsGraphLearningService(factory)
    first = learner.learn_episode('route-session', user, 'episode', now=now)
    goal = 'settings.font_size.increase'
    [route] = route_inventory(factory, now=now)
    assert route['route_hash'] == first['route_hash'] and route['status'] == 'CANDIDATE'
    assert reusable_routes(factory, goal, now=now) == []

    _, second = controlled_records()
    for record in second:
        record['trace']['run_ref'] = 'trial-2'
    add_episode(factory, user, now, 'second-session', second)
    assert learner.learn_episode('second-session', user, 'episode', now=now)['route_hash'] == first['route_hash']
    [reusable] = reusable_routes(factory, goal, now=now)
    assert reusable['support'] == 2 and reusable['subgoals'][-1] == 'adjust_setting'

    _, failed = controlled_records()
    for record in failed:
        record['trace'].update(run_ref='trial-3', rejected_routes=[first['route_hash']])
    failed[-1]['trace']['verification']['final']['status'] = 'UNKNOWN'
    add_episode(factory, user, now, 'failed-session', failed)
    assert learner.learn_episode('failed-session', user, 'episode', now=now)['status'] == 'NEEDS_DATA'
    assert route_inventory(factory, now=now)[0]['status'] == 'QUARANTINED'
    assert reusable_routes(factory, goal, now=now) == []

    with factory() as db:
        UserRepository(db).record_consent(user, 'model_training', False, 'withdraw')
    assert route_inventory(factory, now=now) == []


def test_trace_records_plan_source_for_ai2():
    run = Run('font_size', 'increase')
    wrapper = wrapped(run, Planner(), [learned_route(run)])
    original = run.engine.handle_turn
    seen = []
    def observed(request):
        before = deepcopy(run.engine.session_store.get('s'))
        result = original(request)
        seen.append(trace(wrapper, request, result, before, run.engine.session_store.get('s'), run.graph, 1))
        return result
    run.engine.handle_turn = observed
    run.edge = run.home_edge
    run.observe(run.home)
    assert seen[-1]['plan_source'] == 'learned_route' and seen[-1]['route_hash'] == 'r' * 64
    assert seen[-1]['planner']['reason_code'] == 'learned_route'
