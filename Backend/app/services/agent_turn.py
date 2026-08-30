from __future__ import annotations

from threading import RLock
from typing import Any

from sqlalchemy.orm import Session, sessionmaker

from app.clients.agent import ClickAgentEngine
from app.repositories.personalization import PersonalizationRepository, UserNotFoundError
from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse


class RequestIdConflictError(ValueError):
    pass


class InMemoryTurnResponseStore:
    """Small idempotency store for retries; replace together with sessions in production."""

    def __init__(self) -> None:
        self._responses: dict[str, tuple[str, str | None, AgentTurnResponse]] = {}
        self._lock = RLock()

    def get(
        self, request_id: str, session_id: str, user_id: str | None
    ) -> AgentTurnResponse | None:
        with self._lock:
            stored = self._responses.get(request_id)
            if stored is None:
                return None
            stored_session_id, stored_user_id, response = stored
            if stored_session_id != session_id or stored_user_id != user_id:
                raise RequestIdConflictError(
                    "request_id is already used by another session or user"
                )
            return response

    def save(self, response: AgentTurnResponse, user_id: str | None) -> None:
        with self._lock:
            self._responses[response.request_id] = (response.session_id, user_id, response)


class AgentTurnService:
    def __init__(
        self,
        engine: ClickAgentEngine,
        responses: InMemoryTurnResponseStore | None = None,
        session_factory: sessionmaker[Session] | None = None,
    ) -> None:
        self.engine = engine
        self.responses = responses or InMemoryTurnResponseStore()
        self.session_factory = session_factory

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        cached = self.responses.get(request.request_id, request.session_id, request.user_id)
        if cached is not None:
            return cached
        response = self.engine.handle_turn(request)
        profile = self._profile_context(request.user_id)
        if profile is not None:
            response.metadata["ui_profile"] = profile
            response.guide_command.message = self._personalize_message(
                response.guide_command.message, profile
            )
        self.responses.save(response, request.user_id)
        return response

    def _profile_context(self, user_id: str | None) -> dict[str, Any] | None:
        if user_id is None or self.session_factory is None:
            return None
        with self.session_factory() as session:
            repository = PersonalizationRepository(session)
            try:
                consent = repository.latest_consent(user_id, "personalization")
                if consent is None or not consent.consented:
                    return None
                profile = repository.get_profile(user_id)
            except UserNotFoundError as error:
                raise ValueError("Unknown user_id.") from error
            return {
                "comprehension_level": profile.comprehension_level,
                "explanation_depth": profile.explanation_depth,
                "prefers_step_by_step": profile.prefers_step_by_step,
                "confidence_score": profile.confidence_score,
            }

    @staticmethod
    def _personalize_message(message: str, profile: dict[str, Any]) -> str:
        if profile["comprehension_level"] == "beginner" or profile["explanation_depth"] == "detailed":
            return f"천천히 한 단계씩 진행할게요. {message}"
        if profile["explanation_depth"] == "concise":
            return message.split(".", 1)[0].strip() + ("." if "." in message else "")
        return message
