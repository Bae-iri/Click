"""One maintenance invocation; schedule externally with the same database URL."""
import os
from pathlib import Path
import sys
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sqlalchemy import delete
from app.database import build_engine, build_session_factory
from app.models.auth import AuthSession
from app.models.lifecycle import AccountAction, SafetyConfirmation
from app.repositories.agent_sessions import AgentSessionRepository
from scripts.cleanup_ui_events import cleanup
from app.services.voice_queue import VoiceQueueService


def run(factory, retention=86400):
    if retention <= 0:
        raise ValueError("Retention must be positive")
    now = int(time.time())
    voice_removed = VoiceQueueService(factory).cleanup(retention)
    result = AgentSessionRepository(factory).cleanup(retention)
    result["voice_requests"] = voice_removed
    result["events"] = cleanup(factory, retention)
    with factory() as db, db.begin():
        for model, field, name in ((AuthSession, AuthSession.refresh_expires_at, "auth_sessions"),
                                   (AccountAction, AccountAction.expires_at, "account_actions"),
                                   (SafetyConfirmation, SafetyConfirmation.expires_at, "confirmations")):
            result[name] = db.execute(delete(model).where(field <= now)).rowcount
    return result


if __name__ == "__main__":
    if not os.getenv("DATABASE_URL"):
        raise SystemExit("DATABASE_URL is required")
    engine = build_engine()
    try:
        print(run(build_session_factory(engine), int(os.getenv("DATA_RETENTION_SECONDS", "86400"))))
    finally:
        engine.dispose()
