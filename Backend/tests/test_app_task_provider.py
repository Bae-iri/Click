"""Deadline/capacity/process tests. None call a remote model or use an API key."""
from base64 import b64encode
import json
import multiprocessing
from threading import BoundedSemaphore, Timer
from time import monotonic, sleep
from types import SimpleNamespace

import pytest

from app.services import app_task_provider as provider


class Clock:
    def __init__(self): self.now = 0
    def __call__(self): return self.now


class Connection:
    def __init__(self, frames, clock): self.frames, self.clock, self.closed = list(frames), clock, False
    def close(self): self.closed = True
    def poll(self, timeout):
        if self.frames: return True
        self.clock.now += timeout
        return False
    def recv_bytes(self, maximum):
        frame = self.frames.pop(0)
        if isinstance(frame, BaseException): raise frame
        encoded = json.dumps(frame).encode()
        assert len(encoded) <= maximum
        return encoded


class Process:
    def __init__(self, clock, capacity, *, startup_error=False, keep_alive=False):
        self.clock, self.capacity, self.startup_error, self.keep_alive = clock, capacity, startup_error, keep_alive
        self.pid, self.alive, self.closed, self.events = None, False, False, []
    def start(self):
        if self.startup_error: raise OSError('PRIVATE_SECRET')
        self.pid, self.alive = 123, True
    def is_alive(self): return self.alive
    def join(self, timeout):
        self.events.append('join')
        if not self.keep_alive: self.alive = False
    def terminate(self):
        assert not self.capacity.acquire(blocking=False)
        self.events.append('terminate');self.alive = False
    def kill(self): self.events.append('kill');self.alive = False
    def close(self):
        assert not self.alive
        assert not self.capacity.acquire(blocking=False)
        self.closed = True;self.events.append('close')


class Context:
    def __init__(self, frames=(), *, startup_error=False, keep_alive=False):
        self.clock, self.capacity = Clock(), BoundedSemaphore(1)
        self.receive, self.send = Connection(frames, self.clock), Connection([], self.clock)
        self.process = Process(self.clock, self.capacity, startup_error=startup_error, keep_alive=keep_alive)
        self.process_calls = 0
    def Pipe(self, duplex):
        assert duplex is False
        return self.receive, self.send
    def Process(self, **kwargs):
        self.process_calls += 1
        assert kwargs['daemon'] and kwargs['name'] == 'click-task-provider'
        return self.process


def generate(context, **kwargs):
    return provider.generate('gemini-test', 'fake-key', {'contents': []}, _context=context,
        _clock=context.clock, _capacity=context.capacity, **kwargs)


def complete(body=b'{}', status=200):
    return [{'kind': 'started'}, {'kind': 'response', 'status_code': status, 'body_bytes': len(body)},
        *[{'kind': 'data', 'body': b64encode(body[i:i + 256]).decode()} for i in range(0, len(body), 256)],
        {'kind': 'complete'}]


@pytest.mark.parametrize('budget', [0, -1, float('nan'), float('inf'), True])
def test_invalid_budget_never_starts_a_worker_or_consumes_capacity(budget):
    context = Context()
    with pytest.raises(provider.ProviderError) as failed: generate(context, timeout=budget)
    assert failed.value.reason_code == 'provider_invalid_budget'
    assert failed.value.model_call_state == 'not_started' and context.process_calls == 0
    assert context.capacity.acquire(blocking=False)


def test_exhausted_cleanup_reserve_budget_never_starts_provider():
    context = Context()
    with pytest.raises(provider.ProviderError) as failed: generate(context, timeout=.2)
    assert failed.value.model_call_state == 'not_started' and context.process_calls == 0


def test_capacity_rejection_after_bounded_queue_wait_does_not_spawn(monkeypatch):
    monkeypatch.setattr(provider, 'QUEUE_WAIT_SECONDS', .3)
    context = Context();assert context.capacity.acquire(blocking=False)
    started = monotonic()
    with pytest.raises(provider.ProviderError) as failed: generate(context)
    assert .25 <= monotonic() - started < 1.5
    assert failed.value.reason_code == 'provider_capacity_exhausted'
    assert failed.value.model_call_state == 'not_started' and context.process_calls == 0


def test_queued_call_proceeds_when_a_slot_frees_within_the_wait(monkeypatch):
    monkeypatch.setattr(provider, 'QUEUE_WAIT_SECONDS', 2)
    context = Context(complete(b'{"ok":true}'));assert context.capacity.acquire(blocking=False)
    Timer(.2, context.capacity.release).start()
    reply = generate(context)
    assert reply.status_code == 200 and reply.json() == {'ok': True} and context.process_calls == 1


def test_no_queue_wait_when_it_could_not_leave_a_useful_call(monkeypatch):
    monkeypatch.setattr(provider, 'QUEUE_WAIT_SECONDS', 5)
    context = Context();assert context.capacity.acquire(blocking=False)
    started = monotonic()
    # 2.3 s budget - 0.4 s cleanup reserve leaves 1.9 s, under MIN_CALL_SECONDS: fail at once.
    with pytest.raises(provider.ProviderError) as failed: generate(context, timeout=2.3)
    assert monotonic() - started < .2
    assert failed.value.reason_code == 'provider_capacity_exhausted' and context.process_calls == 0


def test_startup_error_has_safe_code_and_returns_capacity():
    context = Context(startup_error=True)
    with pytest.raises(provider.ProviderError) as failed: generate(context)
    assert failed.value.reason_code == 'provider_startup_failed'
    assert failed.value.model_call_state == 'not_started' and 'PRIVATE' not in str(failed.value)
    assert context.process.closed and context.capacity.acquire(blocking=False)


def test_started_process_without_handshake_is_unknown_not_zero():
    context = Context([EOFError()], keep_alive=True)
    with pytest.raises(provider.ProviderError) as failed: generate(context)
    assert failed.value.reason_code == 'provider_worker_failed' and failed.value.model_call_state == 'unknown'
    assert 'terminate' in context.process.events and context.process.closed
    assert context.capacity.acquire(blocking=False)


def test_absolute_total_cap_stops_silent_worker_and_reaps_before_slot_release():
    context = Context([{'kind': 'started'}], keep_alive=True)
    with pytest.raises(provider.ProviderError) as failed: generate(context, timeout=999)
    assert failed.value.reason_code == 'provider_deadline_exceeded' and failed.value.model_call_state == 'observed'
    assert context.clock.now == pytest.approx(9 - provider._CLEANUP_RESERVE_SECONDS)
    assert 'terminate' in context.process.events and context.process.events[-1] == 'close'
    assert context.capacity.acquire(blocking=False)


def test_body_is_bounded_and_upstream_http_error_is_returned_without_retry():
    context = Context(complete(b'{"error":{"status":"UNAVAILABLE"}}', 503))
    reply = generate(context)
    assert reply.status_code == 503 and reply.json()['error']['status'] == 'UNAVAILABLE'
    assert reply.model_call_state == 'observed' and context.process_calls == 1
    assert context.process.closed and context.capacity.acquire(blocking=False)


def test_oversized_or_malformed_ipc_result_is_rejected():
    for frames in ([{'kind': 'started'}, {'kind': 'response', 'status_code': 200, 'body_bytes': 16385}],
                   [{'kind': 'started'}, {'kind': 'response', 'status_code': 200, 'body_bytes': 1},
                    {'kind': 'data', 'body': b64encode(b'too much').decode()}],
                   [{'kind': 'complete'}]):
        context = Context(frames)
        with pytest.raises(provider.ProviderError) as failed: generate(context)
        assert failed.value.reason_code == 'provider_protocol_error'
        assert context.process.closed


def test_invalid_input_never_spawns_or_exports_secrets():
    context = Context()
    with pytest.raises(provider.ProviderError) as failed:
        provider.generate('https://PRIVATE_HOST', 'PRIVATE_SECRET', {}, _context=context, _clock=context.clock)
    assert failed.value.reason_code == 'provider_invalid_request' and context.process_calls == 0
    assert 'PRIVATE' not in str(failed.value)


def sleeping_worker(connection, *_args):
    provider._send(connection, {'kind': 'started'})
    sleep(30)


def quick_worker(connection, *_args):
    for frame in complete(b'{"ok":true}'):
        provider._send(connection, frame)
    connection.close()


@pytest.mark.process_runtime
def test_real_spawn_timeout_kills_workers_and_recovers_capacity_without_model_calls():
    capacity = BoundedSemaphore(1)
    for _ in range(2):
        started = monotonic()
        with pytest.raises(provider.ProviderError) as failed:
            provider.generate('gemini-test', 'fake-key', {}, timeout=1.2,
                _worker=sleeping_worker, _capacity=capacity)
        assert failed.value.reason_code == 'provider_deadline_exceeded'
        assert failed.value.model_call_state in {'observed', 'unknown'}
        assert monotonic() - started < 2
        assert not any(p.name == 'click-task-provider' for p in multiprocessing.active_children())
    reply = provider.generate('gemini-test', 'fake-key', {}, timeout=5, _worker=quick_worker, _capacity=capacity)
    assert reply.json() == {'ok': True}
    assert capacity.acquire(blocking=False)


def test_worker_uses_one_no_retry_request_and_enforces_response_limit(monkeypatch):
    frames, calls, options = [], [], {}
    class Sender:
        def send_bytes(self, data):
            assert len(data) <= provider._MAX_FRAME_BYTES
            frames.append(json.loads(data))
        def close(self): pass
    class Upstream:
        status_code = 200
        headers = {}
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def iter_bytes(self): yield b'x' * 16385
    class Client:
        def __init__(self, **kwargs): options.update(kwargs)
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def stream(self, *args, **kwargs): calls.append((args, kwargs));return Upstream()
    def transport(**kwargs):
        assert kwargs == {'retries': 0, 'trust_env': False}
        return object()
    monkeypatch.setattr(provider.httpx, 'HTTPTransport', transport)
    monkeypatch.setattr(provider.httpx, 'Client', Client)
    provider._provider_worker(Sender(), 'gemini-test', 'fake-key', b'{}', monotonic() + 1)
    assert len(calls) == 1 and options['follow_redirects'] is False and options['trust_env'] is False
    assert frames == [{'kind': 'started'}, {'kind': 'error', 'reason_code': 'provider_response_too_large'}]


@pytest.mark.parametrize('raw,expected', [(None, 4), ('', 4), ('1', 1), ('6', 6), (' 8 ', 8),
                                          ('0', 4), ('9', 4), ('-2', 4), ('many', 4), ('2.5', 4)])
def test_capacity_is_configurable_within_bounds(monkeypatch, raw, expected):
    if raw is None:
        monkeypatch.delenv('APP_TASK_PROVIDER_CAPACITY', raising=False)
    else:
        monkeypatch.setenv('APP_TASK_PROVIDER_CAPACITY', raw)
    assert provider.configured_capacity() == expected


def test_default_capacity_admits_four_concurrent_calls_and_rejects_the_fifth():
    capacity = BoundedSemaphore(provider.configured_capacity('4'))
    assert all(capacity.acquire(blocking=False) for _ in range(4))
    context = Context(); context.capacity = capacity
    with pytest.raises(provider.ProviderError) as failed: generate(context)
    assert failed.value.reason_code == 'provider_capacity_exhausted' and context.process_calls == 0
