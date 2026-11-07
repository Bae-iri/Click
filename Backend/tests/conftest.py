"""Disposable keys for tests only; production has no built-in persistent key."""
import os
from cryptography.fernet import Fernet

os.environ.setdefault("DATA_ENCRYPTION_KEYS", Fernet.generate_key().decode())
os.environ.setdefault("DATA_LOOKUP_KEY", Fernet.generate_key().decode())
