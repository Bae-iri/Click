"""Real authenticated browsing -> AI2 -> encrypted DB graph drafts and lifecycle."""
import asyncio
from copy import deepcopy
import json
import time

import pytest
from sqlalchemy import func, select

from app.models.agent_session import AgentSession
from app.models.graph_proposal import BROWSE_GRAPH_CONSUMER, GraphProposal
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.task_graph import ApprovedTaskGraph
from app.repositories.runtime_events import cleanup
from app.repositories.users import UserRepository
from scripts.browse_graph_learning import BrowseGraphLearningService
from app.services.browse_graph_jobs import BrowseGraphJobs
from app.services.browse_policy import POLICY
from ai_agent.graph_proposal.proposals.browse_graph import validate_browse_graph
from tests.test_coupang_browse import browse, prepare, screen, node, PATH, BrowseFlow  # noqa: F401
from tests.helpers import register


@pytest.fixture(autouse=True)
def controlled_graph_worker(browse):
    """Inject only the process boundary; below, a separate test uses a real child."""
    learner = BrowseGraphLearningService(browse.app.state.session_factory)
    browse.app.state.browse_graph_learner = learner
    browse.app.state.browse_graph_jobs.runner = lambda job: (
        learner.process(limit=job['limit']) if 'limit' in job else
        learner.learn_session(job['session_id'], job['user_id']))


def consent(f):
    with f.app.state.session_factory() as db:
        user = db.get(AgentSession, f.session).user_id
        UserRepository(db).record_consent(user, 'model_training', True, 'graph-learning-test')
    future = int(time.time()) + 3
    f.app.state.turn_service.repository.clock = lambda: future
    f.app.state.browse_graph_learner.clock = lambda: future
    return user, future


def complete(f, *, details=False, correlate=True):
    resolution = prepare(f, details)
    before = (screen(node('search_results'), node('product_title', '무선 이어폰', True), query='무선 이어폰')
              if details else screen(node('search_bar', clickable=True)))
    first = f.browse('start', goal_resolution_id=resolution, screen=before)
    after = (screen(node('product_detail'), node('product_detail_title', '무선 이어폰')) if details else
             screen(node('search_results'), node('product_item', '무선 이어폰', True), query='무선 이어폰'))
    payload = f.browse_payload('observe', screen=after)
    if correlate:
        payload['action'] = {'guide_id': first['guide_id'], 'observation_id': before['observation_id'],
            'node_id': first['target_node_id'], 'occurred_at_ms': before['captured_at_ms']}
    response = f.client.post(PATH, json=payload)
    assert response.status_code == 200, response.text
    f.version = response.json()['session_version']
    return response.json(), payload


def drafts(f):
    with f.app.state.session_factory() as db:
        return list(db.scalars(select(GraphProposal).where(GraphProposal.proposal_type == 'new_browse_task_graph')))


@pytest.mark.parametrize('details', [False, True])
def test_success_automatically_creates_a_real_new_graph_in_db_without_activation(browse, details):
    user, now = consent(browse)
    result, payload = complete(browse, details=details)
    assert result['outcome'] == 'verified_navigation'
    rows = drafts(browse)
    assert len(rows) == 1
    proposal = rows[0]
    graph = proposal.change['graph']
    assert validate_browse_graph(graph, POLICY)
    assert graph['definition']['goal_type'] == ('coupang.product.details' if details else 'coupang.product.search')
    assert graph['definition']['inputs'] == {'query': 'confirmed_request.query'}
    assert len(graph['states']) == 2 and len(graph['transitions']) == 1
    assert proposal.base_graph_version == 'graphless' and proposal.requires_human_review
    assert proposal.evidence['independent_device_validation'] == 'NOT_ASSESSED'
    assert browse.app.state.browse_service.selector.choose.call_count == 1
    with browse.app.state.session_factory() as db:
        assert db.scalar(select(func.count()).select_from(ApprovedTaskGraph)) == 182
        assert db.get(ApprovedTaskGraph, proposal.task_id) is None
        receipt = db.get(RuntimeEventConsumption, proposal.runtime_receipt_id)
        assert receipt.result['status'] == 'DRAFT_CREATED' and not receipt.result['automatic_release']
        serialized = json.dumps({'graph': proposal.change, 'evidence': proposal.evidence}, ensure_ascii=False)
        for private in ('무선 이어폰', user, browse.session, 'query_hash', 'target_bounds'):
            assert private not in serialized
        raw = db.connection().exec_driver_sql('SELECT change FROM graph_proposals').scalar_one()
        assert 'browse-task-graph' not in str(raw) and 'search_bar' not in str(raw)
    # Replaying the committed HTTP request and re-running AI2 are both idempotent.
    assert browse.client.post(PATH, json=payload).status_code == 200
    assert browse.app.state.browse_graph_learner.learn_session(browse.session, user, now=now)['status'] == 'UNCHANGED'
    assert len(drafts(browse)) == 1


def test_record_without_training_consent_does_not_create_a_draft(browse):
    complete(browse)
    assert not drafts(browse)
    assert browse.app.state.browse_graph_learner.process()['created'] == 0


def test_android_start_without_screen_then_explicit_checks_still_builds_a_graph(browse):
    consent(browse)
    browse.browse('start', goal_resolution_id=prepare(browse))
    before = screen(node('search_bar', clickable=True))
    guide = browse.browse('observe', screen=before)
    result = browse.browse('observe', screen=screen(node('search_results'),
        node('product_item', '무선 이어폰', True), query='무선 이어폰'), action={
            'guide_id': guide['guide_id'], 'observation_id': before['observation_id'],
            'node_id': guide['target_node_id'], 'occurred_at_ms': before['captured_at_ms']})
    assert result['outcome'] == 'verified_navigation'
    assert len(drafts(browse)) == 1
    assert len(drafts(browse)[0].change['graph']['transitions']) == 1


def test_new_multi_step_graph_connects_search_to_results_to_selected_product(browse):
    consent(browse)
    resolution = prepare(browse, details=True)
    before = screen(node('search_bar', clickable=True))
    search = browse.browse('start', goal_resolution_id=resolution, screen=before)
    results = screen(node('search_results'), node('product_title', '무선 이어폰', True), query='무선 이어폰')
    product = browse.browse('observe', screen=results, action={
        'guide_id': search['guide_id'], 'observation_id': before['observation_id'],
        'node_id': search['target_node_id'], 'occurred_at_ms': before['captured_at_ms']})
    assert product['action_kind'] == 'product' and product['status'] == 'IN_PROGRESS'
    end = browse.browse('observe', screen=screen(node('product_detail'), node('product_detail_title', '무선 이어폰')),
        action={'guide_id': product['guide_id'], 'observation_id': results['observation_id'],
            'node_id': product['target_node_id'], 'occurred_at_ms': results['captured_at_ms']})
    assert end['outcome'] == 'verified_navigation'
    graph = drafts(browse)[0].change['graph']
    assert [s['page'] for s in graph['states'].values()] == ['search', 'results', 'detail']
    assert [e['action_kind'] for e in graph['transitions']] == ['search', 'product']


def test_normalized_evidence_does_not_invent_a_raw_native_resource_id(browse):
    consent(browse)
    before = screen(node('searchBar', clickable=True))
    guide = browse.browse('start', goal_resolution_id=prepare(browse), screen=before)
    browse.browse('observe', screen=screen(node('searchResults'),
        node('productItem', '무선 이어폰', True), query='무선 이어폰'), action={
            'guide_id': guide['guide_id'], 'observation_id': before['observation_id'],
            'node_id': guide['target_node_id'], 'occurred_at_ms': before['captured_at_ms']})
    target = drafts(browse)[0].change['graph']['transitions'][0]['target']
    assert target == {'resource_key': 'search_bar', 'match': 'normalized_browse_resource.v1',
                      'binding': 'fresh_public_candidate'}


@pytest.mark.parametrize('failure', ['unlinked', 'user_reported', 'cancelled'])
def test_uncertain_or_user_reported_results_do_not_become_new_paths(browse, failure):
    consent(browse)
    if failure == 'unlinked':
        result, _ = complete(browse, correlate=False)
        assert result['outcome'] == 'verified_navigation'  # Screen success alone is insufficient for a learned edge.
    else:
        browse.browse('start', goal_resolution_id=prepare(browse))
        browse.browse('finish' if failure == 'user_reported' else 'cancel')
    assert not drafts(browse)


def test_background_failure_keeps_the_response_successful_and_retained_work_is_retried(browse, monkeypatch):
    from scripts import browse_graph_learning as module
    consent(browse)
    original = module.generate_browse_graph
    monkeypatch.setattr(module, 'generate_browse_graph', lambda *args: (_ for _ in ()).throw(RuntimeError('synthetic failure')))
    result, _ = complete(browse)
    assert result['outcome'] == 'verified_navigation'
    assert not drafts(browse)
    monkeypatch.setattr(module, 'generate_browse_graph', original)
    report = browse.app.state.browse_graph_learner.process()
    assert report['created'] == 1 and report['errors'] == 0
    assert browse.app.state.browse_graph_learner.process()['created'] == 0
    assert len(drafts(browse)) == 1


def test_restart_backfill_and_cli_metadata_reuse_existing_events(browse, monkeypatch):
    user, now = consent(browse)
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '0')
    complete(browse)
    assert not drafts(browse)
    learner = BrowseGraphLearningService(browse.app.state.session_factory, clock=lambda: now)
    assert learner.process(limit=1)['created'] == 1
    report = learner.list_drafts()
    assert len(report) == 1 and report[0]['transitions'] == 1
    assert user not in json.dumps(report) and browse.session not in json.dumps(report)
    assert learner.process(limit=1)['created'] == 0
    other_user = register(browse.client)['user_id']
    assert learner.learn_session(browse.session, other_user, now=now)['status'] == 'INELIGIBLE'


@pytest.mark.parametrize('operation', ['withdraw', 'new_grant', 'delete_session', 'delete_user', 'expiry'])
def test_generated_graph_is_removed_with_its_source_or_consent(browse, operation):
    user, now = consent(browse)
    complete(browse)
    assert len(drafts(browse)) == 1
    factory = browse.app.state.session_factory
    if operation in {'withdraw', 'new_grant'}:
        with factory() as db:
            UserRepository(db).record_consent(user, 'model_training', operation == 'new_grant', 'next-boundary')
    elif operation == 'delete_session':
        assert browse.client.delete('/api/v1/agent/sessions/' + browse.session).status_code == 204
    elif operation == 'delete_user':
        with factory() as db:
            UserRepository(db).delete_user(user)
    else:
        with factory() as db:
            receipt = db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER))
            expiry = receipt.expires_at
        cleanup(factory, now=expiry)
    assert not drafts(browse)


@pytest.mark.parametrize('problem', ['pre_grant', 'expired_prefix', 'missing_prefix', 'mixed_policy'])
def test_ineligible_prefix_cannot_become_a_reusable_graph(browse, monkeypatch, problem):
    user, now = consent(browse)
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '0')
    complete(browse)
    with browse.app.state.session_factory() as db:
        first = db.scalar(select(RuntimeEvent).where(RuntimeEvent.session_id == browse.session).order_by(RuntimeEvent.sequence))
        if problem == 'pre_grant':
            first.created_at = 0
        elif problem == 'expired_prefix':
            first.expires_at = now
        elif problem == 'missing_prefix':
            db.delete(first)
        else:
            evidence = deepcopy(first.payload)
            evidence['browse']['policy_hash'] = '0' * 64
            first.payload = evidence
        db.commit()
    learner = browse.app.state.browse_graph_learner
    learner.process(now=now)
    learner.learn_session(browse.session, user, now=now)
    assert not drafts(browse)


def test_receipt_expires_with_earliest_input_not_only_final_event(browse, monkeypatch):
    user, now = consent(browse)
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '0')
    complete(browse)
    factory = browse.app.state.session_factory
    with factory() as db:
        first = db.scalar(select(RuntimeEvent).order_by(RuntimeEvent.sequence))
        first.expires_at = now + 20
        db.commit()
    browse.app.state.browse_graph_learner.learn_session(browse.session, user, now=now)
    assert len(drafts(browse)) == 1
    cleanup(factory, now=now + 20)
    assert not drafts(browse)
    with factory() as db:
        assert db.scalar(select(func.count()).select_from(RuntimeEvent)) == 1


def test_small_batches_progress_and_withdrawal_preserves_another_users_draft(browse, monkeypatch):
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '0')
    first_user, _ = consent(browse)
    complete(browse)
    second_user = register(browse.client)['user_id']
    second = BrowseFlow(browse.client, browse.app, '쿠팡 상품 검색해줘')
    consent(second)
    complete(second)
    learner = browse.app.state.browse_graph_learner
    first_batch, second_batch = learner.process(limit=1), learner.process(limit=1)
    assert first_batch['created'] == second_batch['created'] == 1
    assert first_batch['has_more'] and not second_batch['has_more']
    assert learner.process(limit=1)['created'] == 0
    proposals = drafts(browse)
    assert len(proposals) == 2 and proposals[0].task_id == proposals[1].task_id
    assert proposals[0].id != proposals[1].id  # Same generic route, separate source lifecycles.
    with browse.app.state.session_factory() as db:
        UserRepository(db).record_consent(first_user, 'model_training', False, 'withdraw')
    remaining = drafts(browse)
    assert len(remaining) == 1
    with browse.app.state.session_factory() as db:
        receipt = db.get(RuntimeEventConsumption, remaining[0].runtime_receipt_id)
        assert db.get(RuntimeEvent, receipt.event_id).user_id == second_user


def test_periodic_worker_runs_off_the_request_and_stops_cleanly(monkeypatch):
    learner = BrowseGraphJobs(None)
    calls = []
    class Stop:
        stopped = False
        def is_set(self): return self.stopped
        async def wait(self): raise TimeoutError()
    stop = Stop()
    def run():
        calls.append(True)
        stop.stopped = True
    monkeypatch.setattr(learner, 'run_safely', run)
    asyncio.run(learner.serve(stop))
    assert calls == [True]
