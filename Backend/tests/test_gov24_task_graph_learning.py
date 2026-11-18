"""Observed Government24 graphs, consent fences and live success-ranked reuse."""
from copy import deepcopy
from unittest.mock import Mock
import pytest
from sqlalchemy import select, func
from app.models.graph_proposal import GraphProposal, GOV24_GRAPH_CONSUMER
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.users import UserRepository
from app.services.gov24_graphs import GovernmentGraphLearningService, graph_candidates
from app.services.gov24_navigation import CODE_HASH, digest
from app.services.public_guide_policy import CIVIL, POLICY
from ai_agent.graph_proposal.proposals.gov24_graph import generate_graph, validate_graph, IncompleteGovernmentTrace
from tests.test_gov24_navigation import CivilFlow, flow, grant, navigate, view
from tests.test_public_guide import login  # noqa: F401


def learned(login, *, correlated=True):
    f = flow(login)
    user, now = grant(f)
    navigate(f, correlated=correlated)
    learner = GovernmentGraphLearningService(f.app.state.session_factory)
    return f, user, now, learner, learner.learn_session(f.session, user, now=now)


def test_persists_a_real_graph_without_node_coordinates_or_screen_text(login):
    f, user, now, learner, result = learned(login)
    assert result['status'] == 'DRAFT_CREATED'
    with f.app.state.session_factory() as db:
        proposal = db.get(GraphProposal, result['proposal_id'])
        graph = proposal.change['graph']
        validate_graph(graph, CIVIL, POLICY['package'])
        assert graph['definition']['goal_type'] == 'gov24.resident_copy.open'
        assert graph['transitions'][0]['option_id'] == 'nav_resident'
        assert graph['completion']['scope'] == 'public_screen_only'
        assert 'bounds' not in str(graph) and 'node_id' not in str(graph) and f.session not in str(graph)
    assert learner.learn_session(f.session, user, now=now)['status'] == 'UNCHANGED'


def test_unlinked_screen_arrival_cannot_generate_a_task_graph(login):
    _, _, _, _, result = learned(login, correlated=False)
    assert result['status'] == 'NEEDS_DATA'
    assert result['reason'] == 'uncorrelated_navigation'


def test_periodic_processing_materializes_government_graphs(login):
    f = flow(login)
    _, now = grant(f)
    navigate(f)
    service = GovernmentGraphLearningService(f.app.state.session_factory)
    assert service.process(now=now)['created'] == 1
    assert service.process(now=now)['created'] == 0


def test_terminal_response_schedules_agent2_outside_the_request_decision(login, monkeypatch):
    job = Mock(return_value={'status': 'DEFERRED'})
    monkeypatch.setattr(login.app.state.browse_graph_jobs, 'run_safely', job)
    f = flow(login)
    user, _ = grant(f)
    navigate(f)
    job.assert_called_once_with(f.session, user)


def test_three_observed_routes_reuse_a_validated_graph_on_the_fresh_screen(login):
    f, user, now, service, _ = learned(login)
    for _ in range(2):
        following = flow(login)
        navigate(following)
        assert service.learn_session(following.session, user, now=now)['status'] == 'DRAFT_CREATED'
    from app.services.gov24_graphs import graph_summary
    assert len(graph_summary(f.app.state.session_factory, now=now)) == 1
    fourth = flow(login)
    prep = fourth.resolve()['preparation']
    response = fourth.command('start', goal_resolution_id=prep['id'], screen=view('nav_resident', 'nav_services'))
    assert response['target_node_id'] == 'node_nav_resident'
    with f.app.state.session_factory() as db:
        event = db.scalar(select(RuntimeEvent).where(RuntimeEvent.session_id == fourth.session))
        assert event.payload['public_guide']['selection_policy'] == 'ai2_gov24_task_graph'
        assert len(event.payload['public_guide']['selected_graph_hash']) == 64


@pytest.mark.parametrize('problem', ['withdrawal', 'regrant', 'expiry', 'prefix_expiry', 'old_code', 'not_visible', 'changed_evidence'])
def test_ineligible_or_invisible_graphs_never_become_live_candidates(login, problem):
    f, user, now, _, result = learned(login)
    factory = f.app.state.session_factory
    if problem in {'withdrawal', 'regrant'}:
        with factory() as db:
            UserRepository(db).record_consent(user, 'model_training', problem == 'regrant', 'new-boundary')
            assert db.scalar(select(func.count()).select_from(GraphProposal)) == 0
    elif problem == 'expiry':
        now += 8 * 86400
    elif problem == 'prefix_expiry':
        with factory() as db:
            event = db.scalar(select(RuntimeEvent).where(RuntimeEvent.session_id == f.session).order_by(RuntimeEvent.sequence))
            event.expires_at = now - 1
            db.commit()
    elif problem == 'changed_evidence':
        with factory() as db:
            event = db.scalar(select(RuntimeEvent).where(RuntimeEvent.session_id == f.session).order_by(RuntimeEvent.sequence))
            changed = deepcopy(event.payload)
            changed['public_guide']['source'] = 'f' * 64
            event.payload = changed
            db.commit()
    options = {'nav_services'} if problem == 'not_visible' else {'nav_resident', 'nav_services'}
    source = digest(['nav_resident', 'nav_services'])
    code = 'f' * 64 if problem == 'old_code' else CODE_HASH
    assert not graph_candidates(factory, code, source, options, now)


@pytest.mark.parametrize('utterance', ['주민등록등본 발급해줘', '주민등록 등본 발급', '정부24 주민등록등본 발급해줘'])
def test_ordinary_issuance_requests_offer_the_supported_public_screen_scope(login, utterance):
    result = CivilFlow(login.client, login.app, utterance).resolve()
    assert result['resolution_status'] == 'confirmation_required'
    assert result['preparation']['public_guide_goal']['goal_type'] == 'gov24.resident_copy.open'
    assert '직접' in result['message']


def test_graph_validator_rejects_an_application_submission_edge(login):
    f, _, _, _, result = learned(login)
    with f.app.state.session_factory() as db:
        body = deepcopy(db.get(GraphProposal, result['proposal_id']).change['graph'])
    body['transitions'][0]['option_id'] = 'boundary_apply'
    with pytest.raises(ValueError):
        validate_graph(body, CIVIL, POLICY['package'])


def test_multi_step_route_records_each_observed_transition(login):
    f = flow(login)
    user, now = grant(f)
    prep = f.resolve()['preparation']
    before = view('nav_services')
    first = f.command('start', goal_resolution_id=prep['id'], screen=before)
    middle = view('marker_services', 'nav_resident')
    second = f.command('observe', screen=middle, action=dict(guide_id=first['guide_id'],
        observation_id=before['observation_id'], node_id=first['target_node_id'], occurred_at_ms=before['captured_at_ms']))
    final = f.command('observe', screen=view('marker_resident', 'boundary_apply'), action=dict(guide_id=second['guide_id'],
        observation_id=middle['observation_id'], node_id=second['target_node_id'], occurred_at_ms=middle['captured_at_ms']))
    assert final['outcome'] == 'verified_navigation'
    result = GovernmentGraphLearningService(f.app.state.session_factory).learn_session(f.session, user, now=now)
    with f.app.state.session_factory() as db:
        graph = db.get(GraphProposal, result['proposal_id']).change['graph']
        assert [edge['option_id'] for edge in graph['transitions']] == ['nav_services', 'nav_resident']
        assert len(graph['states']) == 3


def test_actual_government_worker_uses_the_shared_database(tmp_path, monkeypatch):
    import json, os, subprocess, sys
    from pathlib import Path
    from fastapi.testclient import TestClient
    from app.main import create_app
    from tests.helpers import register
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '1')
    monkeypatch.setenv('CLICK_GOV24_GUIDE_ENABLED', '1')
    url = 'sqlite+pysqlite:///' + (tmp_path / 'gov24-worker.db').as_posix()
    app = create_app(url)
    with TestClient(app) as client:
        register(client)
        f = CivilFlow(client, app, '주민등록등본 발급해줘')
        _, _ = grant(f)
        assert navigate(f)['outcome'] == 'verified_navigation'
        with app.state.session_factory() as db:
            proposal = db.scalar(select(GraphProposal).where(GraphProposal.proposal_type == 'new_gov24_task_graph'))
            assert proposal is not None
            assert proposal.change['graph']['definition']['target_app'] == 'gov24'
        for command in ('learn-gov24', 'list-gov24-graphs'):
            response = subprocess.run([sys.executable, str(Path(__file__).parents[1] / 'scripts/agent2.py'), command],
                env={**os.environ, 'DATABASE_URL': url}, capture_output=True, text=True, timeout=30)
            assert response.returncode == 0, response.stderr
            body = json.loads(response.stdout)
            if command == 'list-gov24-graphs':
                assert len(body['drafts']) == 1
                assert body['drafts'][0]['completion_scope'] == 'public_screen_only'
            assert f.session not in response.stdout
