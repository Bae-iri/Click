from __future__ import annotations

import sys
import os
import secrets
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from sqlalchemy import inspect, text
from sqlalchemy.exc import SQLAlchemyError


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
AI_AGENT_SOURCE = REPOSITORY_ROOT / "AI-Agent" / "src"
if str(AI_AGENT_SOURCE) not in sys.path:
    sys.path.insert(0, str(AI_AGENT_SOURCE))

from app.api.v1.endpoints.agent_turn import build_router as build_agent_router  # noqa: E402
from app.api.v1.endpoints.guidance import build_router as build_guidance_router  # noqa: E402
from app.api.v1.endpoints.users import build_router as build_users_router  # noqa: E402
from app.api.v1.endpoints.auth import build_auth  # noqa: E402
from app.services.auth import AuthService  # noqa: E402
from app.core.http import HttpBoundary, ErrorEnvelope, install_error_handlers  # noqa: E402
from app.clients.agent import AgentClient, ClickAgentEngine  # noqa: E402
from app.clients.runtime_agent import RuntimeAgentAdapter  # noqa: E402
from app.database import Base, build_engine, build_session_factory, session_scope  # noqa: E402
from app import models as _models  # noqa: E402,F401
from app.repositories.sessions import InMemorySessionRepository  # noqa: E402
from app.services.agent_turn import AgentTurnService  # noqa: E402
from app.api.v1.endpoints.history import build_router as build_history_router
from app.api.v1.endpoints.account_actions import build_router as build_account_actions
from app.services.account_actions import AccountActionService
from app.services.voice_queue import VoiceQueueService
from app.api.v1.endpoints.voice_queue import build_router as build_voice_queue_router
from app.api.v1.endpoints.transcription import build_router as build_transcription_router
from app.core.metrics import RequestMetrics
from app.services.guidance import GuidanceService  # noqa: E402
from app.services.transcription import TranscriptionService


def create_app(database_url: str | None = None, *, enable_legacy: bool = False,
               transcription_service: TranscriptionService | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        try:
            yield
        finally:
            application.state.engine.dispose()

    app = FastAPI(
        title="Click Backend API",
        version="0.1.0",
        lifespan=lifespan,
        responses={code: {"model": ErrorEnvelope} for code in (400, 401, 403, 404, 409, 410, 413, 422, 429, 500, 502, 503, 504)},
        description="Android 설정 앱 위 오버레이 안내를 위한 MVP API",
    )
    engine = build_engine(database_url)
    initialize_encryption = False
    if engine.dialect.name == "sqlite":
        inspector = inspect(engine)
        existing_tables = set(inspector.get_table_names())
        initialize_encryption = "users" not in existing_tables
        if "users" in existing_tables and "data_protection_state" not in existing_tables:
            from app.core.data_encryption import DataEncryptionError
            engine.dispose()
            raise DataEncryptionError("Run the data encryption migration before opening an existing database")
        Base.metadata.create_all(engine)
    from app.core.data_encryption import validate_database_keys
    try:
        validate_database_keys(engine, initialize=initialize_encryption)
    except Exception:
        engine.dispose()
        raise
    session_factory = build_session_factory(engine)
    sessions = InMemorySessionRepository()
    guidance_service = GuidanceService(sessions, AgentClient(REPOSITORY_ROOT))
    turn_service = AgentTurnService(session_factory)
    try:
        turn_service.graphs.seed_mvp()
    except Exception:
        engine.dispose()
        raise
    app.state.turn_service = turn_service
    voice_queue = VoiceQueueService(session_factory, turn_service.repository.idle_seconds)
    app.state.voice_queue = voice_queue
    speech = transcription_service or TranscriptionService()
    app.state.transcription_service = speech
    app.state.engine = engine
    app.state.session_factory = session_factory
    from app.core.validation import install_validation_endpoint
    install_validation_endpoint(app)
    auth_service = AuthService(session_factory)
    auth_router, principal = build_auth(auth_service)
    app.state.auth_service = auth_service
    actions = AccountActionService(session_factory)
    app.state.account_actions = actions
    metrics = RequestMetrics()
    app.add_middleware(HttpBoundary, metrics=metrics)
    install_error_handlers(app)

    def get_db():
        yield from session_scope(session_factory)

    app.include_router(auth_router, prefix="/api/v1")
    app.include_router(build_account_actions(actions, auth_service, principal), prefix="/api/v1")
    if enable_legacy:
        app.include_router(build_guidance_router(guidance_service, principal), prefix="/api/v1")
    app.include_router(build_agent_router(turn_service, principal), prefix="/api/v1")
    app.include_router(build_history_router(turn_service, principal), prefix="/api/v1")
    app.include_router(build_voice_queue_router(voice_queue, principal), prefix="/api/v1")
    app.include_router(build_transcription_router(speech, principal), prefix="/api/v1")
    app.include_router(build_users_router(get_db, principal), prefix="/api/v1")

    @app.get("/health", tags=["system"])
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/version", include_in_schema=False)
    def version():
        from app.core.validation import source_digest
        from app.clients.runtime_agent import runtime_configuration
        from app.services.goal_classifier import goal_classifier_configuration
        from ai_agent.runtime.screen_privacy import observation_contract
        from ai_agent.runtime.capabilities import identity as capability_identity
        import re
        revision = os.getenv("RENDER_GIT_COMMIT", "")
        return {"schema_version": "click-build.v1", "target_validation": "settings-slider.v1",
                "source_sha256": source_digest(), "runtime_configuration": runtime_configuration(),
                "goal_interpretation": goal_classifier_configuration(),
                "observation_contract": observation_contract(),
                "capability_contract": capability_identity(),
                "source_revision": revision if re.fullmatch(r"[0-9a-f]{40}", revision) else None}

    @app.get("/ops/metrics", tags=["system"])
    def operational_metrics(authorization: str | None = Header(None)):
        token = os.getenv("OPS_TOKEN", "")
        if len(token) < 32:
            raise HTTPException(404)
        if not secrets.compare_digest(authorization or "", "Bearer " + token):
            raise HTTPException(401)
        return metrics.snapshot()

    @app.get("/ready", tags=["system"], responses={503: {"description": "Database unavailable or schema missing"}})
    def readiness():
        # A reachable but unmigrated database must not be marked ready.
        try:
            with engine.connect() as connection:
                connection.execute(text("SELECT 1"))
                existing = set(inspect(connection).get_table_names())
                if not set(Base.metadata.tables).issubset(existing):
                    return JSONResponse(status_code=503, content={
                        "status": "not_ready", "database": "schema_missing",
                    })
                for name, table in Base.metadata.tables.items():
                    columns = {column["name"] for column in inspect(connection).get_columns(name)}
                    if not set(table.columns.keys()).issubset(columns):
                        return JSONResponse(status_code=503, content={"status": "not_ready", "database": "schema_missing"})
        except SQLAlchemyError:
            return JSONResponse(status_code=503, content={
                "status": "not_ready", "database": "unavailable",
            })
        return {"status": "ready", "database": "ok"}

    return app


app = create_app()
