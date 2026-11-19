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
from app.schemas.public_guide import PRIVACY_REASONS, PublicGuideScreen
from pydantic import ValidationError
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


@pytest.mark.parametrize('detail,pair', PRIVACY_REASONS.items())
def test_guard_reasons_remain_distinct_and_do_not_reach_models(detail, pair):
    status, handoff_reason = pair
    screen = dict(view(), status=status, privacy_reason=detail)
    if handoff_reason:
        screen['handoff_reason'] = handoff_reason
    service, state = harness()
    result, evidence, state = turn(service, state, screen)
    trace = result.experiment
    expected = 'BLOCKED' if status in {'handoff', 'sensitive'} else 'UNKNOWN'
    assert trace['verification']['subgoal']['status'] == expected
    assert trace['verification']['subgoal']['reason'] == detail
    assert trace['diagnostic']['code'] == detail
    assert trace['diagnostic']['category'] == ('privacy' if expected == 'BLOCKED' else 'observation')
    assert trace['observation_check']['native_detail_available'] is True
    assert trace['observation_check']['exported_node_count'] == 0
    assert trace['planner'] == trace['jev'] == {'called': False, 'reason': detail}
    assert evidence['reason'] == detail
    assert state['experiment']['history'][-1]['diagnostic'] == trace['diagnostic']
    assert result.target_node_id is None and state['experiment']['failures'] == []
    service.government_planner.plan.assert_not_called()
    service.government_selector.select.assert_not_called()
    encoded = json.dumps(trace, ensure_ascii=False)
    for forbidden in (screen['observation_id'], 'node_id', 'bounds', 'resource_id'):
        assert forbidden not in encoded


@pytest.mark.parametrize('handoff_reason', ['credential_input', 'certificate_selection', 'external_auth', 'consent_required'])
def test_old_app_reports_only_known_boundary_without_guessing_specific_input(handoff_reason):
    service, state = harness()
    result, _, _ = turn(service, state, dict(view(), status='handoff', handoff_reason=handoff_reason))
    trace = result.experiment
    assert trace['diagnostic']['code'] == handoff_reason
    assert trace['observation_check']['native_detail_available'] is False
    assert trace['verification']['subgoal']['reason'] == handoff_reason
    assert 'private_boundary' not in json.dumps(trace)
    if handoff_reason == 'credential_input':
        assert '구분해 보고하지 않아요' in result.message


@pytest.mark.parametrize('change,expected', [
    ({'captured_at_ms': 0}, 'stale_observation'),
    ({'captured_at_ms': 9999999999999}, 'future_observation'),
    ({'package_name': 'other.private.app'}, 'package_mismatch'),
    ({'status': 'outside_app', 'nodes': []}, 'outside_gov24_app'),
    ({'status': 'unrecognized', 'nodes': []}, 'unrecognized_screen'),
    ({'nodes': []}, 'no_exported_nodes'),
])
def test_unusable_observations_are_unknown_with_the_exact_rejection_reason(change, expected):
    service, state = harness()
    screen = dict(view('nav_resident'), **change)
    result, evidence, _ = turn(service, state, screen)
    assert result.experiment['diagnostic']['code'] == expected
    assert result.experiment['verification']['subgoal']['status'] == 'UNKNOWN'
    assert result.experiment['verification']['subgoal']['reason'] == expected
    assert evidence['reason'] == expected
    service.government_planner.plan.assert_not_called()
    service.government_selector.select.assert_not_called()
    assert 'other.private.app' not in json.dumps(result.experiment)


def test_missing_observation_and_invalid_semantic_node_have_different_reasons():
    service, state = harness()
    missing, _, _ = turn(service, state, command='start')
    assert missing.experiment['diagnostic']['code'] == 'observation_missing'
    screen = view('nav_resident')
    screen['nodes'][0]['label'] = '홍길동 010-1234-5678'
    invalid, _, _ = turn(service, state, screen)
    assert invalid.experiment['diagnostic']['code'] == 'invalid_public_option'
    assert '홍길동' not in json.dumps(invalid.experiment, ensure_ascii=False)
    service.government_planner.plan.assert_not_called()


def test_stale_handoff_is_unknown_while_sensitive_guard_still_stops_safely():
    service, state = harness()
    stale = dict(view(), status='handoff', handoff_reason='credential_input', privacy_reason='password_field', captured_at_ms=0)
    result, _, _ = turn(service, state, stale)
    assert result.experiment['diagnostic']['code'] == 'stale_observation'
    assert result.experiment['verification']['subgoal']['status'] == 'UNKNOWN'
    sensitive = dict(view(), status='sensitive', privacy_reason='foreign_package_node', captured_at_ms=0)
    result, _, _ = turn(service, state, sensitive)
    assert result.experiment['diagnostic']['code'] == 'foreign_package_node'
    assert result.experiment['observation_check']['code'] == 'stale_observation'
    assert result.outcome == 'handoff'
    service.government_planner.plan.assert_not_called()


@pytest.mark.parametrize('screen', [
    dict(view('nav_resident'), privacy_reason='password_field'),
    dict(view(), status='handoff', handoff_reason='external_auth', privacy_reason='consent_prompt'),
    dict(view(), status='unrecognized', privacy_reason='홍길동 010-1234-5678'),
])
def test_diagnostic_field_rejects_raw_text_and_conflicting_guard_status(screen):
    with pytest.raises(ValidationError):
        PublicGuideScreen.model_validate(screen)


def test_session_budget_does_not_masquerade_as_authentication_or_task_failure():
    service, state = harness()
    state['steps'] = 24
    result, evidence, _ = turn(service, state, view('nav_resident'))
    assert result.status == 'BLOCKED' and result.experiment['diagnostic']['category'] == 'budget'
    assert result.experiment['diagnostic']['facts'] == {'step': 25, 'max_turns': 24}
    assert result.experiment['verification']['final']['status'] == 'UNKNOWN'
    assert evidence['reason'] == 'turn_limit'
    service.government_planner.plan.assert_not_called()


def test_planner_budget_is_a_separate_blocker():
    service, state = harness()
    _, _, state = turn(service, state, view('nav_resident'))
    state['experiment'].update(planner_calls=3, local_failures=2)
    state.pop('pending', None)
    result, evidence, _ = turn(service, state, view('nav_services'))
    assert result.experiment['diagnostic']['code'] == 'planning_budget_exhausted'
    assert result.experiment['diagnostic']['facts'] == {'planner_calls': 3, 'max_planner_calls': 3}
    assert evidence['reason'] == 'planning_budget_exhausted'
    assert service.government_planner.plan.call_count == 1


def test_original_three_step_pattern_explains_the_gate_before_any_model_call():
    service, state = harness()
    first, _, state = turn(service, state, command='start')
    second, _, state = turn(service, state, dict(view(), status='unrecognized', privacy_reason='no_public_options'))
    third, _, state = turn(service, state, dict(view(), status='handoff', handoff_reason='credential_input',
                                               privacy_reason='sensitive_input_field'))
    assert [r.experiment['diagnostic']['code'] for r in (first, second, third)] == [
        'observation_missing', 'no_public_options', 'sensitive_input_field']
    assert [r.experiment['verification']['subgoal']['status'] for r in (first, second, third)] == [
        'UNKNOWN', 'UNKNOWN', 'BLOCKED']
    assert third.experiment['next'] == 'user_handoff'
    assert third.experiment['plan'] is None and third.experiment['planner_calls'] == 0
    assert state['experiment']['failures'] == []
    service.government_planner.plan.assert_not_called()
    service.government_selector.select.assert_not_called()


def test_missing_model_key_reason_is_not_overwritten_by_guard_diagnostics():
    service, state = harness()
    service.government_planner.plan.return_value = (None, {'called': False, 'reason': 'missing_api_key'})
    result, _, _ = turn(service, state, view('nav_resident'))
    assert result.experiment['planner']['reason'] == 'missing_api_key'
    assert result.experiment['observation_check']['code'] == 'public_observation'
    assert result.experiment['verification']['subgoal']['reason'] == 'missing_api_key'


def test_boundary_diagnostic_survives_http_persistence_replay_and_snapshot(login, monkeypatch):
    monkeypatch.setenv('CLICK_GOV24_PLANNER_ENABLED', '1')
    f = CivilFlow(login.client, login.app, '정부24 주민등록등본 화면 찾아줘')
    prepared = f.resolve()['preparation']
    first = f.command('start', goal_resolution_id=prepared['id'])
    assert first['experiment']['diagnostic']['code'] == 'observation_missing'
    screen = dict(view(), status='handoff', handoff_reason='credential_input', privacy_reason='sensitive_input_field')
    body = dict(request_id='diagnostic-replay', session_id=f.session, expected_version=f.version,
                command='observe', screen=screen)
    path = '/api/v1/agent/public-guide/commands'
    response = login.client.post(path, json=body)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result['experiment']['diagnostic']['code'] == 'sensitive_input_field'
    assert login.client.post(path, json=body).json() == result
    f.version = result['session_version']
    snapshot = f.command('snapshot')
    assert snapshot['experiment'] == result['experiment']
    assert snapshot['message'] == result['message'] and snapshot['subgoal'] == 'handoff'
    assert snapshot['target_node_id'] is None
    with login.app.state.session_factory() as db:
        event = db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == f.session)
                           .order_by(RuntimeEvent.sequence.desc())).first()
        assert event.payload['public_guide']['experiment'] == result['experiment']
