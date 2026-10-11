"""Purge expired execution events and cascading AI2 receipts (seven-day retention)."""
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.database import build_engine, build_session_factory
from app import models
from app.repositories.runtime_events import cleanup
from scripts.render_start import database_url

if __name__ == "__main__":
    if not os.environ.get("DATABASE_URL"):
        raise RuntimeError("DATABASE_URL is required")
    raw = os.environ["DATABASE_URL"]
    engine = build_engine(database_url(raw) if raw.startswith(("postgres://", "postgresql://")) else raw)
    try:
        print({"removed": cleanup(build_session_factory(engine))})
    finally:
        engine.dispose()
