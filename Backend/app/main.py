from __future__ import annotations

import sys
from pathlib import Path

from fastapi import FastAPI


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
AI_AGENT_SOURCE = REPOSITORY_ROOT / "AI-Agent" / "src"
if str(AI_AGENT_SOURCE) not in sys.path:
    sys.path.insert(0, str(AI_AGENT_SOURCE))

from app.api.v1.endpoints.guidance import build_router  # noqa: E402
from app.clients.agent import AgentClient  # noqa: E402
from app.repositories.sessions import InMemorySessionRepository  # noqa: E402
from app.services.guidance import GuidanceService  # noqa: E402


def create_app() -> FastAPI:
    app = FastAPI(
        title="Click Backend API",
        version="0.1.0",
        description="Android 설정 앱 위 오버레이 안내를 위한 MVP API",
    )
    service = GuidanceService(InMemorySessionRepository(), AgentClient(REPOSITORY_ROOT))
    app.include_router(build_router(service), prefix="/api/v1")

    @app.get("/health", tags=["system"])
    def health() -> dict[str, str]:
        return {"status": "ok"}

    return app


app = create_app()

