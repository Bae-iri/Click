"""Killable production runtime boundary; capacity is returned only after exit."""
import json
import math
import multiprocessing
from threading import BoundedSemaphore
from time import monotonic
from fastapi import HTTPException
from ai_agent.runtime.budget import deadline

_slots = BoundedSemaphore(4)
_MAX_RESULT = 4 * 1024 * 1024

def _run(connection, operation, end):
    token = deadline.set(end)
    try:
        value = {"ok": True, "value": operation()}
    except TimeoutError:
        value = {"ok": False, "status": 504}
    except BaseException:
        # Provider exceptions may contain input text or credentials. Never export them.
        value = {"ok": False, "status": 502}
    try:
        encoded = json.dumps(value, allow_nan=False, separators=(",", ":")).encode()
        if len(encoded) > _MAX_RESULT:
            encoded = b'{"ok":false,"status":502}'
        connection.send_bytes(encoded)
    finally:
        deadline.reset(token)
        connection.close()

def run_runtime(operation, timeout):
    if not math.isfinite(timeout) or timeout <= 0:
        raise HTTPException(504)
    if not _slots.acquire(blocking=False):
        raise HTTPException(503, headers={"Retry-After": "1"})
    end = monotonic() + timeout
    receive=send=process=None
    started = False
    try:
        context = multiprocessing.get_context("spawn")
        receive, send = context.Pipe(duplex=False)
        process = context.Process(target=_run, args=(send, operation, end), daemon=True,name="click-runtime")
        process.start(); started = True; send.close()
        if not receive.poll(max(0, end - monotonic())):
            raise HTTPException(504)
        try:
            result = json.loads(receive.recv_bytes(_MAX_RESULT))
        except (EOFError, OSError, ValueError) as error:
            raise HTTPException(502) from error
        if monotonic() >= end:
            raise HTTPException(504)
        if not result.get("ok"):
            raise HTTPException(result.get("status", 502))
        return result["value"]
    finally:
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
