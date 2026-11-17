"""Exercise the actual child process and CLI against a disposable shared DB."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from types import SimpleNamespace

from fastapi.testclient import TestClient
import pytest
from sqlalchemy import select
from sqlalchemy.engine import make_url

from app.main import create_app
from app.models.agent_session import AgentSession
from app.models.graph_proposal import GraphProposal
from app.repositories.users import UserRepository
from app.services.browse_graph_jobs import BrowseGraphJobs
from tests.helpers import register
from tests.test_coupang_browse import BrowseFlow, prepare, screen, node


def test_api_generates_graph_in_real_worker_and_cli_reads_the_same_db(tmp_path, monkeypatch):
    monkeypatch.setenv('CLICK_COUPANG_BROWSE_ENABLED', '1')
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '1')
    url = 'sqlite+pysqlite:///' + (tmp_path / 'worker.db').as_posix()
    app = create_app(url)
    with TestClient(app) as client:
        register(client)
        flow = BrowseFlow(client, app, '쿠팡 상품 검색해줘')
        with app.state.session_factory() as db:
            user = db.get(AgentSession, flow.session).user_id
            UserRepository(db).record_consent(user, 'model_training', True, 'worker-test')
        app.state.turn_service.repository.clock = lambda: int(time.time()) + 3
        app.state.browse_service.selector.choose = lambda goal, view, candidates, history: (
            candidates[0], {'called': False, 'candidate_count': len(candidates)})
        before = screen(node('search_bar', clickable=True))
        guide = flow.browse('start', goal_resolution_id=prepare(flow), screen=before)
        result = flow.browse('observe', screen=screen(node('search_results'), node('product_item', '무선 이어폰', True),
            query='무선 이어폰'), action={'guide_id': guide['guide_id'], 'observation_id': before['observation_id'],
                'node_id': guide['target_node_id'], 'occurred_at_ms': before['captured_at_ms']})
        assert result['outcome'] == 'verified_navigation'
        with app.state.session_factory() as db:
            proposal = db.scalar(select(GraphProposal).where(GraphProposal.proposal_type == 'new_browse_task_graph'))
            assert proposal is not None
            proposal_id = proposal.id
            assert proposal.change['graph']['transitions'][0]['action_kind'] == 'search'
        env = {**os.environ, 'DATABASE_URL': url}
        script = str(Path(__file__).parents[1] / 'scripts/agent2.py')
        for command in ('list-browse-graphs', 'learn-browse', 'process'):
            response = subprocess.run([sys.executable, script, command, '--limit', '10'],
                env=env, capture_output=True, text=True, timeout=30)
            assert response.returncode == 0, response.stderr
            body = json.loads(response.stdout)
            if command == 'list-browse-graphs':
                assert body['drafts'][0]['proposal_id'] == proposal_id
            elif command == 'process':
                assert body['browse_graphs']['created'] == 0
            else:
                assert body['created'] == 0
            assert user not in response.stdout and flow.session not in response.stdout
            assert '무선 이어폰' not in response.stdout


def test_worker_passes_database_credentials_only_in_environment(monkeypatch):
    class DB:
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def get_bind(self): return SimpleNamespace(url=make_url('postgresql+psycopg://worker:test-secret@localhost/db'))
    calls = []
    def run(command, **kwargs):
        assert 'test-secret' not in str(command) + kwargs['input']
        assert 'test-secret' in kwargs['env']['DATABASE_URL']
        calls.append(command)
        return SimpleNamespace(returncode=0, stdout='{"created":0}')
    monkeypatch.setattr('app.services.browse_graph_jobs.subprocess.run', run)
    assert BrowseGraphJobs(DB)._run_process({'limit': 20}) == {'created': 0}
    assert len(calls) == 1


def test_busy_or_failed_worker_leaves_durable_work_for_the_next_poll(monkeypatch):
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '1')
    jobs = BrowseGraphJobs(None)
    calls = []
    def runner(job):
        calls.append(job)
        assert jobs.run_safely()['status'] == 'DEFERRED'
        raise TimeoutError('synthetic timeout')
    jobs.runner = runner
    assert jobs.run_safely()['status'] == 'DEFERRED'
    jobs.runner = lambda job: {'created': 1}
    assert jobs.run_safely() == {'created': 1}
    assert len(calls) == 1


@pytest.mark.parametrize('interval', ['0', '9', '3601', 'invalid'])
def test_invalid_worker_configuration_fails_before_serving_requests(monkeypatch, interval):
    monkeypatch.setenv('CLICK_AI2_BROWSE_GRAPH_INTERVAL_SECONDS', interval)
    with pytest.raises(ValueError):
        BrowseGraphJobs(None)
