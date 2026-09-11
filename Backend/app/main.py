from __future__ import annotations

import sys
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
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
from app.services.guidance import GuidanceService  # noqa: E402


def create_app(database_url: str | None = None) -> FastAPI:
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
        responses={code: {"model": ErrorEnvelope} for code in (401, 403, 404, 409, 413, 422, 429, 500)},
        description="Android 설정 앱 위 오버레이 안내를 위한 MVP API",
    )
    engine = build_engine(database_url)
    if engine.dialect.name == "sqlite":
        Base.metadata.create_all(engine)
    session_factory = build_session_factory(engine)
    sessions = InMemorySessionRepository()
    guidance_service = GuidanceService(sessions, AgentClient(REPOSITORY_ROOT))
    turn_service = AgentTurnService(RuntimeAgentAdapter(), session_factory=session_factory)
    app.state.engine = engine
    app.state.session_factory = session_factory
    auth_service = AuthService(session_factory)
    auth_router, principal = build_auth(auth_service)
    app.state.auth_service = auth_service
    app.add_middleware(HttpBoundary)
    install_error_handlers(app)

    def get_db():
        yield from session_scope(session_factory)

    app.include_router(auth_router, prefix="/api/v1")
    app.include_router(build_guidance_router(guidance_service, principal), prefix="/api/v1")
    app.include_router(build_agent_router(turn_service, principal), prefix="/api/v1")
    app.include_router(build_users_router(get_db, principal), prefix="/api/v1")

    @app.get("/health", tags=["system"])
    def health() -> dict[str, str]:
        return {"status": "ok"}

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
        except SQLAlchemyError:
            return JSONResponse(status_code=503, content={
                "status": "not_ready", "database": "unavailable",
            })
        return {"status": "ready", "database": "ok"}

    return app


app = create_app()
