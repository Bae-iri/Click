"""Validate native Kakao tokens at Kakao; never trust a client-supplied user ID."""
import os

import httpx
from fastapi import HTTPException


class KakaoTokenVerifier:
    def __init__(self, transport=None):
        self.transport = transport

    def verify(self, token: str) -> str:
        configured = os.getenv("KAKAO_APP_ID", "").strip()
        if not configured.isascii() or not configured.isdigit() or int(configured) <= 0:
            raise HTTPException(503, detail="Kakao login is not configured.")
        try:
            with httpx.Client(timeout=8, follow_redirects=False, trust_env=False,
                              transport=self.transport) as client:
                response = client.get("https://kapi.kakao.com/v1/user/access_token_info",
                                      headers={"Authorization": "Bearer " + token})
        except httpx.TimeoutException as error:
            raise HTTPException(504) from error
        except httpx.HTTPError as error:
            raise HTTPException(503) from error
        if response.status_code in (400, 401, 403):
            # Kakao's temporary internal error (-1) must not invalidate a user's login.
            try:
                transient = response.json().get("code") == -1
            except (ValueError, AttributeError):
                transient = False
            raise HTTPException(503 if transient else 401)
        if response.status_code == 429 or response.status_code >= 500:
            raise HTTPException(503)
        if response.status_code != 200:
            raise HTTPException(502)
        try:
            data = response.json()
            subject, app_id, expires = data["id"], data["app_id"], data["expires_in"]
            if any(type(value) is not int for value in (subject, app_id, expires)):
                raise ValueError("Invalid token metadata")
            if subject <= 0 or len(str(subject)) > 40 or expires <= 0 or app_id != int(configured):
                raise HTTPException(401)
        except (ValueError, KeyError, TypeError) as error:
            raise HTTPException(502) from error
        return str(subject)
