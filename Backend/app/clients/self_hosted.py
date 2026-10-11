"""Explicit operator-owned inference endpoints; no ambient proxies or redirects."""
from dataclasses import dataclass
import os
from urllib.parse import urlsplit

import httpx


@dataclass(frozen=True)
class InferenceEndpoint:
    url: str
    token: str

    @classmethod
    def from_env(cls, prefix: str):
        url = os.getenv(f"{prefix}_URL", "").strip().rstrip("/")
        token = os.getenv(f"{prefix}_TOKEN", "").strip()
        parsed = urlsplit(url)
        _ = parsed.port  # Reject malformed ports before any inference attempt.
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment or not token):
            raise ValueError(f"{prefix}_URL and {prefix}_TOKEN must identify our inference server")
        # Ownership is an operator setting. Restrict egress in production and use
        # TLS or an encrypted private link; a hostname cannot prove ownership.
        return cls(url, token)

    def post(self, payload: dict, *, timeout: float) -> httpx.Response:
        with httpx.Client(timeout=httpx.Timeout(timeout, connect=3.0),
                          follow_redirects=False, trust_env=False) as client:
            return client.post(self.url, headers={"Authorization": f"Bearer {self.token}"}, json=payload)
