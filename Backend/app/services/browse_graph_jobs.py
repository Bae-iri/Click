"""Launch isolated Agent(2) jobs without importing it into user execution."""
import asyncio
import json
import logging
import os
from pathlib import Path
import subprocess
import sys
from threading import BoundedSemaphore
from sqlalchemy import select
from app.models.user import Consent

log = logging.getLogger(__name__)


def enabled():
    return os.getenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '1') == '1'


class BrowseGraphJobs:
    def __init__(self, factory, runner=None):
        self.factory, self.runner = factory, runner or self._run_process
        self.slots = BoundedSemaphore(1)
        self.interval = int(os.getenv('CLICK_AI2_BROWSE_GRAPH_INTERVAL_SECONDS', '60'))
        if not 10 <= self.interval <= 3600:
            raise ValueError('AI2 browse graph interval must be between 10 and 3600 seconds')

    def _run_process(self, job):
        with self.factory() as db:
            url = db.get_bind().url
            if url.database in (None, '', ':memory:'):
                raise ValueError('The graph worker requires a shared database')
            if url.drivername.startswith('sqlite'):
                url = url.set(database=str(Path(url.database).resolve()))
        # Credentials use the worker's environment, never process arguments or logs.
        env = {**os.environ, 'DATABASE_URL': url.render_as_string(hide_password=False)}
        backend = Path(__file__).resolve().parents[2]
        result = subprocess.run([sys.executable, str(backend / 'scripts/browse_graph_learning.py')],
            input=json.dumps(job), text=True, capture_output=True, cwd=backend, env=env, timeout=30)
        if result.returncode or len(result.stdout) > 65536:
            raise RuntimeError('Graph learning worker failed')
        report = json.loads(result.stdout)
        if not isinstance(report, dict):
            raise ValueError('Invalid graph learning result')
        return report

    def run_safely(self, session_id=None, user_id=None):
        if not enabled() or not self.slots.acquire(blocking=False):
            return {'status': 'DEFERRED'}
        try:
            if session_id is not None:
                with self.factory() as db:
                    granted = db.scalar(select(Consent.consented).where(Consent.user_id == user_id,
                        Consent.purpose == 'model_training').order_by(Consent.recorded_at.desc(), Consent.id.desc()).limit(1))
                    if not granted:
                        return {'status': 'INELIGIBLE'}
            job = {'limit': 20} if session_id is None else {'session_id': session_id, 'user_id': user_id}
            return self.runner(job)
        except Exception as error:
            log.warning('AI2 browse graph job deferred (%s)', type(error).__name__)
            return {'status': 'DEFERRED'}
        finally:
            self.slots.release()

    async def serve(self, stop):
        while not stop.is_set():
            try:
                await asyncio.wait_for(stop.wait(), timeout=self.interval)
            except TimeoutError:
                await asyncio.to_thread(self.run_safely)
