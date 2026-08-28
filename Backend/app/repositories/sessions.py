from __future__ import annotations

from threading import RLock

from app.models.session import GuidanceSession


class SessionNotFoundError(KeyError):
    pass


class InMemorySessionRepository:
    def __init__(self) -> None:
        self._sessions: dict[str, GuidanceSession] = {}
        self._lock = RLock()

    def add(self, session: GuidanceSession) -> None:
        with self._lock:
            self._sessions[session.session_id] = session

    def get(self, session_id: str) -> GuidanceSession:
        with self._lock:
            try:
                return self._sessions[session_id]
            except KeyError as error:
                raise SessionNotFoundError(session_id) from error

    def save(self, session: GuidanceSession) -> None:
        with self._lock:
            if session.session_id not in self._sessions:
                raise SessionNotFoundError(session.session_id)
            self._sessions[session.session_id] = session

