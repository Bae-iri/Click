"""Canonical identities shared by execution and offline graph proposals."""
from hashlib import sha256
import json


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def fingerprint(value):
    return sha256(canonical(value).encode("utf-8")).hexdigest()
