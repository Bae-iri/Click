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
