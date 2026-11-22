"""Closed runtime phases: no requests, labels, provider errors or credentials."""
from contextvars import ContextVar

PHASES = frozenset({
    'worker_startup', 'checkpoint_restore', 'engine_init', 'request_translation',
    'runtime_selection', 'selection_candidates', 'provider_http', 'selection_validate',
    'response_build', 'execution_event', 'checkpoint_capture', 'result_delivery',
})
reporter = ContextVar('runtime_phase_reporter', default=None)


def phase(name):
    sink = reporter.get()
    if sink is not None and isinstance(name, str) and name in PHASES:
        try:
            sink(name)
        except (OSError, ValueError):
            # Diagnostics cannot change the result of an otherwise valid turn.
            pass
