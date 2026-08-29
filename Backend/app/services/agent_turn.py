from __future__ import annotations

from threading import RLock

from app.clients.agent import ClickAgentEngine
from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse


class RequestIdConflictError(ValueError):
    pass


class InMemoryTurnResponseStore:
    """Small idempotency store for retries; replace together with sessions in production."""

    def __init__(self) -> None:
        self._responses: dict[str, tuple[str, AgentTurnResponse]] = {}
        self._lock = RLock()

    def get(self, request_id: str, session_id: str) -> AgentTurnResponse | None:
        with self._lock:
            stored = self._responses.get(request_id)
            if stored is None:
                return None
            stored_session_id, response = stored
            if stored_session_id != session_id:
                raise RequestIdConflictError("request_id is already used by another session")
            return response

    def save(self, response: AgentTurnResponse) -> None:
        with self._lock:
            self._responses[response.request_id] = (response.session_id, response)


class AgentTurnService:
    def __init__(
        self,
        engine: ClickAgentEngine,
        responses: InMemoryTurnResponseStore | None = None,
    ) -> None:
        self.engine = engine
        self.responses = responses or InMemoryTurnResponseStore()

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        cached = self.responses.get(request.request_id, request.session_id)
        if cached is not None:
            return cached
        response = self.engine.handle_turn(request)
        self.responses.save(response)
        return response
