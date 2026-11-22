from dataclasses import dataclass
from time import monotonic,sleep
import pytest
from fastapi import HTTPException
from app.clients.runtime_process import run_runtime

pytestmark=pytest.mark.process_runtime

@dataclass
class Work:
    duration:float=0
    def __call__(self):
        sleep(self.duration)
        return {"completed":True}

def test_timed_out_workers_are_killed_and_capacity_recovers():
    for _ in range(5):
        start=monotonic()
        with pytest.raises(HTTPException) as failed:run_runtime(Work(30),.15)
        assert failed.value.status_code==504
        assert monotonic()-start<2
    assert run_runtime(Work(),10)=={"completed":True}

def test_real_runtime_returns_checkpoint_to_parent():
    from fastapi.testclient import TestClient
    from app.main import create_app
    from tests.helpers import register
    from tests.test_agent_sessions import payload,post,session
    with TestClient(create_app()) as client:
        register(client)
        response=post(client,payload())
        assert response.status_code==200,response.text
        assert session(client).json()["version"]==1
        assert post(client,payload()).json()==response.json()


def test_typed_approval_and_verified_value_change_survive_process_boundaries():
    from tests.test_task_outcomes import Flow
    f=Flow();result=f.change()
    assert result["task_status"]=="WAITING_RESULT"
    assert result["metadata"]["task_outcome"]["technical"]["status"]=="CHANGED"
    answer=f.answer("satisfied")
    assert answer["task_status"]=="COMPLETED"


def test_ipc_startup_failure_does_not_leak_capacity(monkeypatch):
    from threading import BoundedSemaphore
    from types import SimpleNamespace
    import app.clients.runtime_process as runner
    capacity=BoundedSemaphore(1);monkeypatch.setattr(runner,"_slots",capacity)
    def fail(**kwargs):raise OSError("synthetic IPC failure")
    monkeypatch.setattr(runner.multiprocessing,"get_context",lambda *args:SimpleNamespace(Pipe=fail))
    with pytest.raises(OSError):run_runtime(Work(),1)
    assert capacity.acquire(blocking=False)
    capacity.release()


def test_runtime_processes_do_not_share_mutated_worker_state():
    import os
    first=run_runtime(ProcessIdentity(),10)
    second=run_runtime(ProcessIdentity(),10)
    assert first["pid"] != os.getpid()
    assert first["pid"] != second["pid"]
    assert first["initial"] == second["initial"] == "unset"


@dataclass
class ProcessIdentity:
    def __call__(self):
        import os
        initial=os.environ.get("CLICK_WORKER_ISOLATION_PROBE","unset")
        os.environ["CLICK_WORKER_ISOLATION_PROBE"]="changed"
        return {"pid":os.getpid(),"initial":initial}


@dataclass
class PhaseWork:
    failure: str = ''
    def __call__(self):
        from app.clients.runtime_timing import phase
        phase('engine_init')
        phase('PRIVATE_SCREEN_OR_PROVIDER_TOKEN')
        phase({'not': 'a phase'})
        phase('provider_http')
        if self.failure == 'hang':sleep(30)
        if self.failure == 'timeout':raise TimeoutError('PRIVATE_PROVIDER_ERROR')
        for _ in range(100):phase('selection_validate')
        return {'unchanged': True}


@pytest.mark.parametrize('failure', ['hang', 'timeout'])
def test_timeout_reports_the_work_phase_without_exporting_private_errors(failure, caplog):
    # Warm the forkserver before measuring an intentionally stalled provider.
    run_runtime(Work(), 10)
    with pytest.raises(HTTPException) as error:
        run_runtime(PhaseWork(failure), 2)
    assert error.value.status_code == 504
    assert error.value.headers['X-Click-Runtime-Phase'] == 'provider_http'
    assert 'phase=provider_http' in caplog.text
    assert 'PRIVATE_' not in caplog.text
    assert run_runtime(Work(), 10) == {'completed': True}


def test_progress_is_bounded_and_does_not_change_the_operation_result(caplog):
    import logging
    with caplog.at_level(logging.INFO, logger='app.clients.runtime_process'):
        assert run_runtime(PhaseWork(), 10) == {'unchanged': True}
    assert 'outcome=ok' in caplog.text
    assert 'provider_http' in caplog.text
    assert 'PRIVATE_' not in caplog.text


def test_phase_diagnostics_without_a_worker_or_with_a_closed_sink_are_inert():
    from app.clients.runtime_timing import phase, reporter
    phase('provider_http')
    def closed(_):raise OSError('closed')
    token = reporter.set(closed)
    try:
        phase('provider_http')
    finally:
        reporter.reset(token)


def test_logging_failure_cannot_replace_a_result_or_leak_capacity(monkeypatch):
    from threading import BoundedSemaphore
    import app.clients.runtime_process as runner
    capacity = BoundedSemaphore(1)
    monkeypatch.setattr(runner, '_slots', capacity)
    def broken(*args, **kwargs):raise OSError('diagnostic sink closed')
    monkeypatch.setattr(runner.logger, 'log', broken)
    assert run_runtime(Work(), 10) == {'completed': True}
    assert capacity.acquire(blocking=False)
    capacity.release()
