"""One killable provider attempt with a monotonic wall deadline.

Only public model input and the API credential enter this process. No database
objects or planner state are shared. The response body is bounded to 16 KiB and
is returned to the existing parser, never logged. There are no retries. Killing
the local worker closes its sockets; it cannot cancel work already accepted by
the remote provider.
"""
from base64 import b64decode, b64encode
from dataclasses import dataclass, field
import json
import math
import multiprocessing
import re
from threading import BoundedSemaphore
from time import monotonic

import httpx


MAX_TOTAL_SECONDS = 9.0
MAX_BODY_BYTES = 16 * 1024
_MAX_PAYLOAD_BYTES = 128 * 1024
_MAX_FRAME_BYTES = 400
_BODY_CHUNK_BYTES = 256
_CLEANUP_RESERVE_SECONDS = .4
_CAPACITY = BoundedSemaphore(2)
_UNREAPED = []  # An OS worker that will not exit never returns capacity.
REASON_CODES = frozenset(('provider_invalid_request', 'provider_invalid_budget',
    'provider_capacity_exhausted', 'provider_startup_failed', 'provider_deadline_exceeded',
    'provider_timeout', 'provider_transport_error', 'provider_response_too_large',
    'provider_protocol_error', 'provider_worker_failed', 'provider_cleanup_failed'))
CALL_STATES = frozenset(('not_started', 'observed', 'unknown'))


class ProviderError(Exception):
    """Only closed codes and call attribution cross the process boundary."""
    def __init__(self, reason_code, model_call_state, elapsed_ms=0):
        self.reason_code = reason_code if isinstance(reason_code, str) and reason_code in REASON_CODES else 'provider_protocol_error'
        self.model_call_state = model_call_state if isinstance(model_call_state, str) and model_call_state in CALL_STATES else 'unknown'
        self.elapsed_ms = max(0, round(elapsed_ms, 3))
        super().__init__(self.reason_code)


@dataclass(frozen=True)
class ProviderResponse:
    status_code: int
    content: bytes
    elapsed_ms: float
    headers: dict = field(default_factory=dict)
    model_call_state: str = 'observed'

    def json(self):
        return json.loads(self.content)


def _send(connection, message):
    data = json.dumps(message, allow_nan=False, separators=(',', ':')).encode()
    if len(data) > _MAX_FRAME_BYTES:
        raise ValueError('provider_protocol_error')
    # Each complete frame plus its four-byte length is below POSIX PIPE_BUF's
    # minimum 512 bytes. poll()+recv_bytes() cannot wait for a large partial body.
    connection.send_bytes(data)


def _provider_worker(connection, model, token, payload, end):
    try:
        left = end - monotonic()
        if left <= 0:
            _send(connection, {'kind': 'error', 'reason_code': 'provider_deadline_exceeded'})
            return
        transport = httpx.HTTPTransport(retries=0, trust_env=False)
        with httpx.Client(transport=transport, timeout=httpx.Timeout(left, connect=min(2, left)),
                          trust_env=False, follow_redirects=False) as client:
            # This is an attempted provider operation, not proof of remote billing.
            _send(connection, {'kind': 'started'})
            with client.stream('POST',
                    f'https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent',
                    headers={'x-goog-api-key': token, 'Content-Type': 'application/json'}, content=payload) as upstream:
                body = bytearray()
                for chunk in upstream.iter_bytes():
                    if monotonic() >= end:
                        raise TimeoutError()
                    body.extend(chunk)
                    if len(body) > MAX_BODY_BYTES:
                        _send(connection, {'kind': 'error', 'reason_code': 'provider_response_too_large'})
                        return
                status = upstream.status_code
                retry = upstream.headers.get('retry-after', '')
                headers = {'retry-after': retry} if re.fullmatch(r'[0-9]{1,8}(?:\.[0-9]{1,3})?', retry) else {}
        _send(connection, {'kind': 'response', 'status_code': status, 'body_bytes': len(body), 'headers': headers})
        for offset in range(0, len(body), _BODY_CHUNK_BYTES):
            _send(connection, {'kind': 'data', 'body': b64encode(body[offset:offset + _BODY_CHUNK_BYTES]).decode('ascii')})
        _send(connection, {'kind': 'complete'})
    except (httpx.TimeoutException, TimeoutError):
        try:
            _send(connection, {'kind': 'error', 'reason_code': 'provider_timeout'})
        except (OSError, ValueError):
            pass
    except httpx.HTTPError:
        try:
            _send(connection, {'kind': 'error', 'reason_code': 'provider_transport_error'})
        except (OSError, ValueError):
            pass
    except BaseException:
        try:
            _send(connection, {'kind': 'error', 'reason_code': 'provider_worker_failed'})
        except (OSError, ValueError):
            pass
    finally:
        connection.close()


def _reap(process, end, clock):
    """Only release a slot after this OS process has actually exited."""
    for method, allowance in (('join', .02), ('terminate', .15), ('kill', .15)):
        if not process.is_alive():
            break
        if method != 'join':
            getattr(process, method)()
        process.join(max(0, min(allowance, end - clock())))
    if process.is_alive():
        _UNREAPED.append(process)
        return False
    process.join(0)
    process.close()
    return True


def generate(model, token, payload, *, timeout=MAX_TOTAL_SECONDS,
             _context=None, _clock=None, _worker=None, _capacity=None):
    """Return one bounded response; test seams never change the production path.

    timeout is total wall time, including process startup and cleanup. It is
    capped at nine seconds; 0.4 seconds is reserved for terminating/reaping.
    A smaller enclosing request budget must be passed by the caller.
    """
    clock = _clock or monotonic
    started = clock()
    if type(timeout) not in (int, float) or not math.isfinite(timeout) or timeout <= 0:
        raise ProviderError('provider_invalid_budget', 'not_started')
    total = min(timeout, MAX_TOTAL_SECONDS)
    end, work_end = started + total, started + total - _CLEANUP_RESERVE_SECONDS
    def failed(code, state):
        return ProviderError(code, state, (clock() - started) * 1000)
    if work_end <= started:
        raise failed('provider_deadline_exceeded', 'not_started')
    if (not isinstance(model, str) or not re.fullmatch(r'gemini-[A-Za-z0-9.-]{1,100}', model)
            or not isinstance(token, str) or not 0 < len(token) <= 4096
            or any(ord(c) < 33 or ord(c) > 126 for c in token) or not isinstance(payload, dict)):
        raise failed('provider_invalid_request', 'not_started')
    try:
        encoded = json.dumps(payload, allow_nan=False, separators=(',', ':')).encode()
    except (TypeError, ValueError):
        raise failed('provider_invalid_request', 'not_started') from None
    if len(encoded) > _MAX_PAYLOAD_BYTES:
        raise failed('provider_invalid_request', 'not_started')
    capacity = _capacity or _CAPACITY
    if not capacity.acquire(blocking=False):
        raise failed('provider_capacity_exhausted', 'not_started')
    receive = send = process = None
    process_started = False
    call_state = 'not_started'
    response = None
    error = None
    try:
        if clock() >= work_end:
            raise failed('provider_deadline_exceeded', call_state)
        context = _context or multiprocessing.get_context('spawn')
        receive, send = context.Pipe(duplex=False)
        process = context.Process(target=_worker or _provider_worker,
            args=(send, model, token, encoded, work_end), daemon=True, name='click-task-provider')
        process.start()
        process_started = True
        call_state = 'unknown'
        send.close()
        expected_bytes = status = None
        headers, content = {}, bytearray()
        while True:
            left = work_end - clock()
            if left <= 0 or not receive.poll(left):
                raise failed('provider_deadline_exceeded', call_state)
            try:
                message = json.loads(receive.recv_bytes(_MAX_FRAME_BYTES))
            except (EOFError, OSError):
                raise failed('provider_worker_failed', call_state) from None
            except (ValueError, TypeError):
                raise failed('provider_protocol_error', call_state) from None
            if clock() >= work_end:
                raise failed('provider_deadline_exceeded', call_state)
            if not isinstance(message, dict):
                raise failed('provider_protocol_error', call_state)
            kind = message.get('kind')
            if kind == 'started' and call_state == 'unknown' and expected_bytes is None:
                call_state = 'observed'
            elif kind == 'error':
                raise failed(message.get('reason_code'), call_state)
            elif kind == 'response' and call_state == 'observed' and expected_bytes is None:
                status, expected_bytes = message.get('status_code'), message.get('body_bytes')
                if type(status) is not int or not 100 <= status <= 599 or type(expected_bytes) is not int or not 0 <= expected_bytes <= MAX_BODY_BYTES:
                    raise failed('provider_protocol_error', call_state)
                candidate_headers = message.get('headers', {})
                retry = candidate_headers.get('retry-after') if isinstance(candidate_headers, dict) else None
                if isinstance(retry, str) and re.fullmatch(r'[0-9]{1,8}(?:\.[0-9]{1,3})?', retry):
                    headers['retry-after'] = retry
            elif kind == 'data' and expected_bytes is not None:
                try:
                    chunk = b64decode(message.get('body', ''), validate=True)
                except (ValueError, TypeError):
                    raise failed('provider_protocol_error', call_state) from None
                if not 0 < len(chunk) <= _BODY_CHUNK_BYTES or len(content) + len(chunk) > expected_bytes:
                    raise failed('provider_protocol_error', call_state)
                content.extend(chunk)
            elif kind == 'complete' and expected_bytes is not None and len(content) == expected_bytes:
                response = ProviderResponse(status, bytes(content), (clock() - started) * 1000, headers)
                break
            else:
                raise failed('provider_protocol_error', call_state)
    except ProviderError as failure:
        error = failure
    except Exception:
        # Process startup/IPC exceptions can contain credentials or input. Only
        # a closed category crosses back to the service.
        if process is not None and getattr(process, 'pid', None) is not None:
            process_started = True
            if call_state == 'not_started':
                call_state = 'unknown'
        error = failed('provider_startup_failed' if not process_started else 'provider_worker_failed', call_state)
    finally:
        for connection in (receive, send):
            if connection is not None:
                try:
                    connection.close()
                except Exception:
                    error = failed('provider_cleanup_failed', call_state)
        reaped = True
        if process_started:
            try:
                reaped = _reap(process, end, clock)
            except Exception:
                reaped = False
                _UNREAPED.append(process)
        elif process is not None:
            try:
                process.close()
            except Exception:
                error = failed('provider_cleanup_failed', call_state)
        if reaped:
            capacity.release()
        else:
            error = failed('provider_cleanup_failed', call_state)
    if error is not None:
        error.elapsed_ms = max(0, round((clock() - started) * 1000, 3))
        raise error from None
    if clock() >= end:
        raise failed('provider_deadline_exceeded', call_state)
    return ProviderResponse(response.status_code, response.content, (clock() - started) * 1000, response.headers)
