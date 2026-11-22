"""Killable production runtime boundary; capacity is returned only after exit."""
import json
import math
import multiprocessing
import logging
from hashlib import sha256
from threading import BoundedSemaphore
from time import monotonic
from fastapi import HTTPException
from ai_agent.runtime.budget import deadline
from app.clients.runtime_timing import PHASES, reporter, phase

logger = logging.getLogger(__name__)

_slots = BoundedSemaphore(4)
_MAX_RESULT = 4 * 1024 * 1024
# Fork from a clean, database-free server rather than re-importing the entire
# runtime on every screen. Each turn still receives its own killable process.
_START_METHOD = "forkserver" if "forkserver" in multiprocessing.get_all_start_methods() else "spawn"
if _START_METHOD == "forkserver":
    multiprocessing.set_forkserver_preload(["app.clients.runtime_work"])

def _run(connection, operation, end):
    token = deadline.set(end)
    count = 0
    last_phase = 'worker_startup'
    def progress(name):
        nonlocal count, last_phase
        last_phase = name
        if count < 64:
            count += 1
            connection.send_bytes(json.dumps({'phase': name}).encode())
    timing_token = reporter.set(progress)
    try:
        value = {"ok": True, "value": operation()}
    except TimeoutError:
        value = {"ok": False, "status": 504, "failure_phase": last_phase}
    except BaseException:
        # Provider exceptions may contain input text or credentials. Never export them.
        value = {"ok": False, "status": 502, "failure_phase": last_phase}
    try:
        phase('result_delivery')
        encoded = json.dumps(value, allow_nan=False, separators=(",", ":")).encode()
        if len(encoded) > _MAX_RESULT:
            encoded = b'{"ok":false,"status":502}'
        connection.send_bytes(encoded)
    finally:
        deadline.reset(token)
        reporter.reset(timing_token)
        connection.close()

def run_runtime(operation, timeout):
    if not math.isfinite(timeout) or timeout <= 0:
        raise HTTPException(504)
    if not _slots.acquire(blocking=False):
        raise HTTPException(503, headers={"Retry-After": "1"})
    began = monotonic()
    end = began + timeout
    current_phase, phase_began = 'worker_startup', began
    durations = {}
    outcome = 'error'
    failure_phase = None
    request_id = getattr(getattr(operation, 'request', None), 'request_id', '')
    reference = sha256(str(request_id).encode()).hexdigest()[:16]
    receive=send=process=None
    started = False
    try:
        context = multiprocessing.get_context(_START_METHOD)
        receive, send = context.Pipe(duplex=False)
        process = context.Process(target=_run, args=(send, operation, end), daemon=True,name="click-runtime")
        process.start(); started = True; send.close()
        progress_count = 0
        while True:
            if not receive.poll(max(0, end - monotonic())):
                raise HTTPException(504)
            try:
                result = json.loads(receive.recv_bytes(_MAX_RESULT))
            except (EOFError, OSError, ValueError) as error:
                raise HTTPException(502) from error
            if isinstance(result, dict) and set(result) == {'phase'}:
                progress_count += 1
                if not isinstance(result['phase'], str) or result['phase'] not in PHASES or progress_count > 64:
                    raise HTTPException(502)
                now = monotonic()
                durations[current_phase] = durations.get(current_phase, 0) + (now - phase_began) * 1000
                current_phase, phase_began = result['phase'], now
                continue
            break
        if monotonic() >= end:
            raise HTTPException(504)
        if not result.get("ok"):
            if isinstance(result.get('failure_phase'), str) and result['failure_phase'] in PHASES:
                failure_phase = result['failure_phase']
            raise HTTPException(result.get("status", 502))
        outcome = 'ok'
        return result["value"]
    except HTTPException as error:
        outcome = str(error.status_code)
        if error.status_code == 504:
            error.headers = {**(error.headers or {}), 'X-Click-Runtime-Phase': failure_phase or current_phase}
        raise
    finally:
        now = monotonic()
        durations[current_phase] = durations.get(current_phase, 0) + (now - phase_began) * 1000
        if receive is not None:receive.close()
        if send is not None:send.close()
        if started:
            process.join(.05)
            if process.is_alive():
                process.terminate(); process.join(.3)
            if process.is_alive():
                process.kill(); process.join(.3)
            alive = process.is_alive()
            if not alive: process.close()
        else:
            alive = False
        # Never return capacity while an OS worker can still execute.
        if not alive: _slots.release()
        try:
            logger.log(logging.WARNING if outcome != 'ok' else logging.INFO,
                       'runtime_turn ref=%s outcome=%s phase=%s total_ms=%.3f phase_ms=%s',
                       reference, outcome, failure_phase or current_phase, (now-began)*1000,
                       {key: round(value, 3) for key, value in durations.items()})
        except Exception:
            # A broken diagnostic sink must not leak a worker or replace a result.
            pass
