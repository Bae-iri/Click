from hashlib import sha256
import json
import os

from fastapi import HTTPException

from app.clients.checkpoint import SCHEMA_VERSION, capture, graph_version, restore, safe_response
from app.clients.runtime_agent import RuntimeAgentAdapter
from app.clients.turn_executor import execute
from app.repositories.agent_sessions import AgentSessionRepository
from app.repositories.personalization import PersonalizationRepository, UserNotFoundError
from app.schemas.agent_turn import AgentTurnResponse


class AgentTurnService:
    def __init__(self, session_factory, engine_factory=RuntimeAgentAdapter):
        self.session_factory = session_factory
        self.engine_factory = engine_factory
        self.repository = AgentSessionRepository(session_factory,
            idle_seconds=int(os.getenv("AGENT_SESSION_IDLE_SECONDS", "1800")),
            lease_seconds=int(os.getenv("AGENT_TURN_LEASE_SECONDS", "60")))
        self.timeout_seconds = float(os.getenv("AGENT_TURN_TIMEOUT_SECONDS", "20"))
        if not 0 < self.timeout_seconds < self.repository.lease_seconds:
            raise ValueError("Turn timeout must be positive and shorter than the lease")

    def handle_turn(self, request):
        canonical = json.dumps(request.model_dump(mode="json"), sort_keys=True,
                               separators=(",", ":"), ensure_ascii=False)
        digest = sha256(canonical.encode()).hexdigest()
        claim = self.repository.claim(request, digest)
        if "cached" in claim:
            if claim["graph_version"] is not None and (
                claim["schema_version"] != SCHEMA_VERSION or claim["graph_version"] != graph_version()
            ):
                self.repository.conflict("CHECKPOINT_INCOMPATIBLE")
            return self.personalize(safe_response(AgentTurnResponse.model_validate(claim["cached"])), request.user_id)
        try:
            if claim["checkpoint"] is not None and (
                claim["schema_version"] != SCHEMA_VERSION or claim["graph_version"] != graph_version()
            ):
                self.repository.conflict("CHECKPOINT_INCOMPATIBLE")
            # An isolated runtime per turn prevents cross-request state corruption.
            store = restore(claim["checkpoint"])
            engine = self.engine_factory(session_store=store)
            response = safe_response(execute(lambda: engine.handle_turn(request), self.timeout_seconds))
            response.session_version = claim["version"] + 1
            checkpoint = capture(store, request.session_id)
            self.repository.finish(request, claim, response, checkpoint, SCHEMA_VERSION, graph_version())
        except Exception:
            self.repository.release(request.session_id, claim["token"])
            raise
        # Persist a neutral response; reevaluate consent even on retries.
        return self.personalize(response, request.user_id)

    def personalize(self, response, user_id):
        with self.session_factory() as db:
            repository = PersonalizationRepository(db)
            try:
                consent = repository.latest_consent(user_id, "personalization")
                if consent is None or not consent.consented:
                    return response
                profile = repository.get_profile(user_id)
            except UserNotFoundError as error:
                raise HTTPException(401) from error
            context = {
                "comprehension_level": profile.comprehension_level,
                "explanation_depth": profile.explanation_depth,
                "prefers_step_by_step": profile.prefers_step_by_step,
                "confidence_score": profile.confidence_score,
            }
            response.metadata["ui_profile"] = context
            message = response.guide_command.message
            if context["comprehension_level"] == "beginner" or context["explanation_depth"] == "detailed":
                response.guide_command.message = f"천천히 한 단계씩 진행할게요. {message}"
            elif context["explanation_depth"] == "concise":
                response.guide_command.message = message.split(".", 1)[0].strip() + ("." if "." in message else "")
            return response
