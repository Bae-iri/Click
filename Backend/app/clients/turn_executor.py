"""Bound isolated runtime work; timed-out workers never access the database."""
from queue import Queue, Empty
from threading import BoundedSemaphore, Thread
from time import monotonic

from fastapi import HTTPException
from ai_agent.runtime.budget import deadline

_slots = BoundedSemaphore(4)


def execute(operation, timeout):
    if not _slots.acquire(blocking=False):
        raise HTTPException(503)
    result = Queue(maxsize=1)

    def run():
        token = deadline.set(monotonic() + timeout)
        try:
            result.put((True, operation()))
        except BaseException as error:
            result.put((False, error))
        finally:
            deadline.reset(token)
            _slots.release()

    worker = Thread(target=run, daemon=True, name="agent-turn")
    try:
        worker.start()
    except BaseException:
        _slots.release()
        raise
    try:
        success, value = result.get(timeout=timeout)
    except Empty as error:
        raise HTTPException(504) from error
    if not success:
        if isinstance(value, TimeoutError):
            raise HTTPException(504) from value
        raise value
    return value
