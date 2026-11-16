"""Controlled-provider unit tests inject an in-process runtime boundary.

Production uses isolated, killable workers. Mark process_runtime tests to exercise
that boundary; process tests cannot inherit monkeypatched provider senders.
"""
import os
from cryptography.fernet import Fernet

os.environ.setdefault("DATA_ENCRYPTION_KEYS", Fernet.generate_key().decode())
os.environ.setdefault("DATA_LOOKUP_KEY", Fernet.generate_key().decode())

import pytest
from app.clients.turn_executor import execute

@pytest.fixture(autouse=True)
def controlled_runtime_boundary(request,monkeypatch):
    if request.node.get_closest_marker("process_runtime") is None:
        monkeypatch.setattr("app.services.agent_turn.run_runtime",execute)
        monkeypatch.setattr("app.main.warm_runtime", lambda: None)
