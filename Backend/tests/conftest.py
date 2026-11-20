"""Controlled-provider unit tests inject an in-process runtime boundary.

Production uses spawned, killable workers. Mark process_runtime tests to exercise
that boundary; process tests cannot inherit monkeypatched provider senders.
"""
import os
from hashlib import sha256
from cryptography.fernet import Fernet

os.environ.setdefault("DATA_ENCRYPTION_KEYS", Fernet.generate_key().decode())
os.environ.setdefault("DATA_LOOKUP_KEY", Fernet.generate_key().decode())

import pytest
from app.clients.turn_executor import execute


def pytest_make_parametrize_id(config, val, argname):
    # Audio fixtures can be megabytes long; their bytes must not become test IDs,
    # terminal output or multi-megabyte pytest caches.
    if isinstance(val, (bytes, bytearray)):
        return f'{argname}-{len(val)}bytes-{sha256(val).hexdigest()[:12]}'

@pytest.fixture(autouse=True)
def controlled_runtime_boundary(request,monkeypatch):
    if request.node.get_closest_marker("process_runtime") is None:
        monkeypatch.setattr("app.services.agent_turn.run_runtime",execute)
