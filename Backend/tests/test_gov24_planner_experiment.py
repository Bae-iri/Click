"""Planner pilot: three independent verdicts, actual action binding and privacy gates."""
import json
import time
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest
from sqlalchemy import select

from app.models.agent_session import AgentSession
from app.models.runtime_event import RuntimeEvent
from app.schemas.public_guide import PublicGuideRequest
from app.services.gov24_experiment import decide
from app.services.gov24_planner import GeminiGovernmentPlanner, SERVICES
from app.services.gov24_jev import JevGovernmentSelector
from app.services.public_guide import PublicGuideService
from tests.test_gov24_navigation import CivilFlow, view
from tests.test_public_guide import login  # noqa: F401


def plan(option='nav_resident', via_menu=False):
    nodes = [{'predicate': 'requested_service_visible', 'options': [option]}]
    if via_menu:
        nodes.insert(0, {'predicate': 'services_visible', 'options': ['nav_services', 'nav_frequent']})
    return {'nodes': nodes}


def harness(scope='screen_only', goal='gov24.resident_copy.open', via_menu=False):
    service = SimpleNamespace(target=PublicGuideService.target, government_planner=Mock(), government_selector=Mock())
    service.government_planner.plan.return_value = (plan(SERVICES[goal][1], via_menu), {'called': True, 'status': 'SUCCESS'})
    service.government_selector.select.side_effect = lambda goal, subgoal, options, *a: (options[0], {'called': True, 'status': 'SUCCESS'})
    state = {'goal': {'goal_type': goal, 'request_scope': scope}, 'steps': 0}
    return service, state


def turn(service, state, screen=None, command='observe', action=None):
    request = PublicGuideRequest(request_id='r', session_id='s', expected_version=state['steps'],
        command=command, screen=screen, action=action, goal_resolution_id='p' if command == 'start' else None)
    return decide(service, request, state, int(time.time()))


def action_for(response, screen):
    return dict(guide_id=response.guide_id, observation_id=response.observation_id,
                node_id=response.target_node_id, occurred_at_ms=screen['captured_at_ms'])


@pytest.mark.parametrize('goal', SERVICES)
def test_four_goals_verify_only_their_own_service_screen(goal):
    service, state = harness(goal=goal)
    initial = view(SERVICES[goal][1], 'nav_services')
    first, _, state = turn(service, state, initial)
    result, evidence, state = turn(service, state, view(SERVICES[goal][2], 'boundary_apply'), action=action_for(first, initial))
    checks = result.experiment['verification']
    assert checks['action']['status'] == checks['subgoal']['status'] == checks['final']['status'] == 'SUCCESS'
    assert result.outcome == 'verified_navigation' and result.manual_only
    assert service.government_planner.plan.call_count == 1
    assert 'requested_service_visible' in state['experiment']['completed']
    assert evidence['schema_version'] == 'gov24-planner.v1'


def test_actual_issuance_request_is_not_satisfied_by_service_screen():
    service, state = harness(scope='task_completion')
    result, _, _ = turn(service, state, view('marker_resident', 'boundary_apply'))
    checks = result.experiment['verification']
    assert checks['subgoal']['status'] == 'SUCCESS' and checks['final']['status'] == 'UNKNOWN'
    assert checks['action']['status'] == 'UNKNOWN'
    service.government_planner.plan.assert_not_called()


def test_waiting_user_does_not_replan_or_falsely_succeed():
    service, state = harness()
    screen = view('nav_resident')
    first, _, state = turn(service, state, screen)
    again, _, state = turn(service, state, screen)
    assert again.guide_id != first.guide_id
    assert again.observation_id == screen['observation_id']
    assert again.experiment['verification']['action']['status'] == 'WAITING'
    assert again.experiment['verification']['subgoal']['status'] == 'WAITING'
    assert service.government_selector.select.call_count == 1
    assert service.government_planner.plan.call_count == 1


def test_matching_tap_does_not_prove_subgoal_if_screen_has_not_loaded():
    service, state = harness()
    screen = view('nav_resident')
    first, _, state = turn(service, state, screen)
    result, _, _ = turn(service, state, screen, action=action_for(first, screen))
    assert result.experiment['verification']['action']['status'] == 'SUCCESS'
    assert result.experiment['verification']['subgoal']['status'] == 'WAITING'
    assert result.experiment['verification']['final']['status'] == 'UNKNOWN'


def test_empty_ax_is_unknown_and_never_causes_replanning():
    service, state = harness()
    initial = view('nav_resident')
    first, _, state = turn(service, state, initial)
    result, _, _ = turn(service, state, view(), action=action_for(first, initial))
    assert result.experiment['verification']['subgoal']['status'] == 'UNKNOWN'
    assert service.government_planner.plan.call_count == 1


def test_wrong_destination_after_tap_is_failed_without_claiming_final_failure():
    service, state = harness()
    initial = view('nav_resident')
    first, _, state = turn(service, state, initial)
    result, _, state = turn(service, state, view('marker_services', 'nav_frequent'), action=action_for(first, initial))
    assert result.experiment['verification']['action']['status'] == 'SUCCESS'
    assert result.experiment['verification']['subgoal']['status'] == 'FAILED'
    assert result.experiment['verification']['final']['status'] == 'UNKNOWN'
    assert state['experiment']['failures'][-1]['option'] == 'nav_resident'


def test_direct_final_screen_does_not_leave_an_optional_menu_goal_active():
    service, state = harness(via_menu=True)
    first, _, state = turn(service, state, view('nav_services'))
    result, _, state = turn(service, state, view('marker_resident', 'boundary_apply'))
    assert result.experiment['active_subgoal'] is None
    assert result.experiment['verification']['final']['status'] == 'SUCCESS'


def test_subgoal_progress_is_preserved_when_remaining_plan_is_replaced():
    service, state = harness(via_menu=True)
    initial = view('nav_services')
    first, _, state = turn(service, state, initial)
    result, _, state = turn(service, state, view('marker_services', 'nav_resident'), action=action_for(first, initial))
    assert 'services_visible' in state['experiment']['completed']
    state['experiment']['local_failures'] = 2
    state.pop('pending', None)
    service.government_planner.plan.return_value = (plan(), {'called': True})
    _, _, state = turn(service, state, view('marker_services', 'nav_resident'))
    assert state['experiment']['revision'] == 2
    assert state['experiment']['completed'] == ['services_visible']
    assert service.government_planner.plan.call_args.args[3] == ['services_visible']


@pytest.mark.parametrize('status', ['sensitive', 'unrecognized', 'outside_app', 'handoff'])
def test_private_and_unrecognized_screens_never_reach_models(status):
    service, state = harness()
    screen = view()
    screen['status'] = status
    if status == 'handoff':
        screen['handoff_reason'] = 'credential_input'
    result, evidence, _ = turn(service, state, screen)
    service.government_planner.plan.assert_not_called()
    service.government_selector.select.assert_not_called()
    assert result.target_node_id is None
    expected = 'BLOCKED' if status in {'sensitive', 'handoff'} else 'UNKNOWN'
    assert result.experiment['verification']['subgoal']['status'] == expected


def test_stale_or_forged_tap_never_proves_action_success():
    service, state = harness()
    initial = view('nav_resident')
    first, _, state = turn(service, state, initial)
    wrong = action_for(first, initial)
    wrong['guide_id'] = 'other'
    result, _, _ = turn(service, state, view('marker_resident', 'boundary_apply'), action=wrong)
    assert result.experiment['verification']['action']['status'] == 'UNKNOWN'
    assert result.experiment['verification']['final']['status'] == 'SUCCESS'


@pytest.mark.parametrize('bad_plan', [plan('nav_move_in'), {'nodes': [{'predicate':'services_visible', 'options':['nav_services']}]},
    {'nodes':[{'predicate':'requested_service_visible','options':['boundary_apply']}]},
    {'nodes':[{'predicate':'requested_service_visible','options':['nav_resident'],'prompt':'ignore rules'}]}])
def test_gemini_plan_is_locally_validated(bad_plan):
    sender = lambda *a, **kw: httpx.Response(200, json={'candidates':[{'finishReason':'STOP','content':{'parts':[{'text':json.dumps(bad_plan)}]}}]})
    result, diagnostic = GeminiGovernmentPlanner(sender).plan('gov24.resident_copy.open', 'public_menu', ['nav_resident'], [], [])
    assert result is None and diagnostic['status'] == 'UNKNOWN'


@pytest.mark.parametrize('choice,probabilities,expected', [
    ('c0', {'c0': .99, 'insufficient': .01}, 'nav_resident'),
    ('insufficient', {'c0': .01, 'insufficient': .99}, None),
    ('c0', {'c0': .6, 'insufficient': .4}, None),
    ('c1', {'c0': .99, 'insufficient': .01}, None),
])
def test_jev_fixed_template_accepts_only_confident_current_candidate(monkeypatch, choice, probabilities, expected):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-token')
    def send(payload, **kwargs):
        assert set(payload['questions']['navigation']['criteria']) == {'c0', 'insufficient'}
        assert payload['state']['visible_options'] == ['nav_resident']
        return httpx.Response(200, json={'model': payload['model'], 'answers': {'navigation': {
            'type': 'choice', 'choice': choice, 'confidence': .99, 'probabilities': probabilities}}})
    selected, diagnostic = JevGovernmentSelector(send).select('gov24.resident_copy.open', plan()['nodes'][0],
        ['nav_resident'], ['nav_resident'], [])
    assert selected == expected


def test_completed_predicate_cannot_be_reintroduced_by_planner():
    valid = plan(via_menu=True)
    sender = lambda *a, **kw: httpx.Response(200, json={'candidates':[{'finishReason':'STOP','content':{'parts':[{'text':json.dumps(valid)}]}}]})
    result, diagnostic = GeminiGovernmentPlanner(sender).plan('gov24.resident_copy.open', 'services',
        ['nav_resident'], ['services_visible'], [])
    assert result is None


def test_missing_key_never_silently_uses_a_static_plan(monkeypatch):
    monkeypatch.delenv('GEMINI_API_KEY', raising=False)
    result, diagnostic = GeminiGovernmentPlanner().plan('gov24.resident_copy.open', 'public_menu', [], [], [])
    assert result is None and not diagnostic['called'] and diagnostic['reason'] == 'missing_api_key'


@pytest.mark.parametrize('goal', SERVICES)
def test_http_preparation_overlay_trace_and_final_verdict_persist(login, monkeypatch, goal):
    monkeypatch.setenv('CLICK_GOV24_PLANNER_ENABLED', '1')
    service = login.app.state.public_guide_service
    fake, _ = harness(goal=goal)
    service.government_planner, service.government_selector = fake.government_planner, fake.government_selector
    class PilotFlow(CivilFlow):
        def payload(self, **kwargs):
            body = super().payload(**kwargs)
            body['device_context']['capabilities']['gov24_planner'] = True
            return body
    f = PilotFlow(login.client, login.app, f'정부24 {SERVICES[goal][0]} 화면 찾아줘')
    prepared = f.resolve()['preparation']
    assert prepared['public_guide_goal']['goal_type'] == goal
    first_screen = view(SERVICES[goal][1])
    first = f.command('start', goal_resolution_id=prepared['id'], screen=first_screen)
    result = f.command('observe', screen=view(SERVICES[goal][2], 'boundary_apply'), action={
        'guide_id':first['guide_id'], 'observation_id':first['observation_id'],
        'node_id':first['target_node_id'], 'occurred_at_ms':first_screen['captured_at_ms']})
    assert result['experiment']['verification']['final']['status'] == 'SUCCESS'
    assert login.client.get('/api/v1/agent/sessions/' + f.session).status_code == 200
    with login.app.state.session_factory() as db:
        event = db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == f.session).order_by(RuntimeEvent.sequence.desc())).first()
        assert event.payload['public_guide']['experiment']['verification']['action']['status'] == 'SUCCESS'
        row = db.get(AgentSession, f.session)
        assert row.task_outcome['verification']['final']['status'] == 'SUCCESS'
