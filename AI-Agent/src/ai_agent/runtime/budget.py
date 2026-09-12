"""A shared per-turn deadline for sequential provider calls."""
from contextvars import ContextVar
from time import monotonic

deadline = ContextVar("agent_deadline", default=None)


def remaining(maximum):
    end = deadline.get()
    if end is None:
        return maximum
    seconds = min(maximum, end - monotonic())
    if seconds <= 0:
        raise TimeoutError("Agent deadline exceeded")
    return seconds
