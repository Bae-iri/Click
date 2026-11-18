"""Synthetic HTTP navigation observations exercise graphless Government24 learning."""
from copy import deepcopy
import json
import time
from uuid import uuid4

import pytest
from sqlalchemy import select
from app.models.agent_session import AgentSession
from app.models.runtime_event import RuntimeEvent
from app.repositories.users import UserRepository
from app.services.public_guide_policy import CIVIL_OPTIONS
from app.services.gov24_navigation import CODE_HASH
from app.services.gov24_routes import route_snapshot
from scripts.agent2_monitor import render_gov24
from tests.test_public_guide import login, LoginFlow  # noqa: F401


class CivilFlow(LoginFlow):
    def payload(self, **kwargs):
        body = super().payload(**kwargs)
        body['device_context']['capabilities']['gov24_civil'] = True
        body['device_context']['capabilities']['gov24_services'] = True
        return body


def view(*keys):
    return dict(status='public', package_name='kr.go.minwon.m', observation_id=uuid4().hex,
        captured_at_ms=int(time.time() * 1000), width=1080, height=2400,
        nodes=[dict(node_id='node_' + k, option_id=k, resource_id='', label=CIVIL_OPTIONS[k]['label'],
                    bounds=[10, 10 + i * 100, 500, 90 + i * 100],
                    clickable=CIVIL_OPTIONS[k]['group'] != 'marker') for i, k in enumerate(keys)])


def flow(login):
    return CivilFlow(login.client, login.app, '정부24 주민등록등본 발급 화면 찾아줘')


def grant(f):
    factory = f.app.state.session_factory
    with factory() as db:
        user = db.get(AgentSession, f.session).user_id
        UserRepository(db).record_consent(user, 'model_training', True, 'navigation-test')
    now = int(time.time()) + 3
    f.app.state.turn_service.repository.clock = lambda: now
    return user, now


def navigate(f, *, destination='resident', correlated=True):
    prepared = f.resolve()['preparation']
    assert prepared['execution_mode'] == 'public_guide' and prepared['graph_reference'] is None
    assert prepared['public_guide_goal']['goal_type'] == 'gov24.resident_copy.open'
    start_view = view('nav_resident', 'nav_services')
    result = f.command('start', goal_resolution_id=prepared['id'], screen=start_view)
    if result['question']:
        result = f.command('observe', screen=start_view, question_id=result['question']['id'], choice='nav_resident')
    kwargs = {}
    if correlated:
        kwargs['action'] = dict(guide_id=result['guide_id'], observation_id=start_view['observation_id'],
            node_id=result['target_node_id'], occurred_at_ms=start_view['captured_at_ms'])
    end = (view('marker_resident', 'boundary_apply') if destination == 'resident' else
           view('marker_services', 'nav_frequent') if destination == 'wrong' else start_view)
    return f.command('observe', screen=end, **kwargs)


def test_reaches_service_entry_without_graph_or_application_action(login, monkeypatch):
    f = flow(login)
    _, now = grant(f)
    monkeypatch.setattr(f.app.state.turn_service.graphs, 'select_for_inputs', lambda *a: pytest.fail('No graph'))
    result = navigate(f)
    assert result['outcome'] == 'verified_navigation' and result['subgoal'] == 'resident_service'
    assert result['target_node_id'] is None and result['question'] is None
    state = login.client.get('/api/v1/agent/sessions/' + f.session).json()
    assert state['task_id'] == 'GOV24-RESIDENT-COPY'
    assert state['public_guide_goal_type'] == 'gov24.resident_copy.open'
    report = route_snapshot(f.app.state.session_factory, CODE_HASH, now=now)
    route, = report['routes']
    assert route['successes'] == 1 and not route['leader']
    assert '새 후보' in render_gov24(report)
    text = json.dumps(report, ensure_ascii=False)
    assert f.session not in text and 'bounds' not in text and 'node_id' not in text


def test_three_trials_learn_and_choose_on_fourth_request(login):
    f = flow(login)
    _, now = grant(f)
    navigate(f)
    navigate(flow(login))
    navigate(flow(login))
    factory = login.app.state.session_factory
    before = route_snapshot(factory, CODE_HASH, now=now)
    route, = before['routes']
    assert route['successes'] == 3 and route['leader']
    assert 'STAT_LEADER' in render_gov24(before)
    fourth = flow(login)
    prepared = fourth.resolve()['preparation']
    result = fourth.command('start', goal_resolution_id=prepared['id'], screen=view('nav_resident', 'nav_services'))
    assert result['target_node_id'] == 'node_nav_resident' and result['question'] is None
    after = route_snapshot(factory, CODE_HASH, now=now)
    assert any(e['status'] == 'ai2_selected' for e in after['events'])
    assert 'ai2_selected' in render_gov24(after, before)
    assert after == route_snapshot(factory, CODE_HASH, now=now)


@pytest.mark.parametrize('destination,label', [('wrong', 'failures'), ('unchanged', 'unknown')])
def test_wrong_or_unresolved_taps_are_not_counted_as_success(login, destination, label):
    f = flow(login)
    _, now = grant(f)
    navigate(f, destination=destination)
    report = route_snapshot(f.app.state.session_factory, CODE_HASH, now=now)
    route, = report['routes']
    assert route[label] == 1 and route['successes'] == 0


def test_unlinked_arrival_cannot_supply_success_evidence(login):
    f = flow(login)
    _, now = grant(f)
    assert navigate(f, correlated=False)['outcome'] == 'verified_navigation'
    assert route_snapshot(f.app.state.session_factory, CODE_HASH, now=now)['routes'] == []


@pytest.mark.parametrize('problem', ['withdrawal', 'expiry', 'old_code'])
def test_monitor_and_selection_exclude_ineligible_traces(login, problem):
    f = flow(login)
    user, now = grant(f)
    navigate(f)
    factory = f.app.state.session_factory
    if problem == 'expiry':
        now += 8 * 86400
    elif problem == 'withdrawal':
        with factory() as db:
            UserRepository(db).record_consent(user, 'model_training', False, 'withdraw')
    else:
        with factory() as db:
            event = db.scalar(select(RuntimeEvent).where(RuntimeEvent.session_id == f.session))
            payload = deepcopy(event.payload)
            payload['public_guide']['code_hash'] = 'f' * 64
            event.payload = payload
            db.commit()
    assert route_snapshot(factory, CODE_HASH, now=now)['routes'] == []


def test_old_client_and_private_boundary_do_not_initiate_or_complete_issuance(login):
    login.text = '정부24 주민등록등본 발급 화면 찾아줘'
    assert login.resolve(preparation_reset=True)['preparation']['execution_mode'] == 'graph'
    f = flow(login)
    prep = f.resolve()['preparation']
    result = f.command('start', goal_resolution_id=prep['id'], screen=dict(view(), status='handoff', handoff_reason='credential_input'))
    assert result['outcome'] == 'handoff' and result['target_node_id'] is None


def test_generic_apply_button_and_ambiguous_tiles_do_not_verify_arrival(login):
    f = flow(login)
    prep = f.resolve()['preparation']
    result = f.command('start', goal_resolution_id=prep['id'], screen=view('boundary_apply'))
    assert result['outcome'] != 'verified_navigation' and result['target_node_id'] is None


@pytest.mark.parametrize('status', ['handoff', 'unrecognized'])
def test_private_or_unrecognized_destination_keeps_attempt_in_denominator(login, status):
    f = flow(login)
    _, now = grant(f)
    prep = f.resolve()['preparation']
    before = view('nav_resident')
    guide = f.command('start', goal_resolution_id=prep['id'], screen=before)
    destination = dict(view(), status=status)
    if status == 'handoff':
        destination['handoff_reason'] = 'credential_input'
    f.command('observe', screen=destination, action=dict(guide_id=guide['guide_id'],
        observation_id=before['observation_id'], node_id=guide['target_node_id'],
        occurred_at_ms=before['captured_at_ms']))
    route, = route_snapshot(f.app.state.session_factory, CODE_HASH, now=now)['routes']
    assert route['attempts'] == route['unknown'] == 1 and route['successes'] == 0
