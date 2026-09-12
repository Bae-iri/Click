"""Delete expired event details; aggregate UI profiles remain until user deletion."""
import argparse
from datetime import datetime, timedelta, timezone
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sqlalchemy import delete
from app.database import build_engine, build_session_factory
from app.models.personalization import UIEvent


def cleanup(factory, retention_seconds=86400, now=None):
    if retention_seconds <= 0:
        raise ValueError("Retention must be positive")
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(seconds=retention_seconds)
    with factory() as db, db.begin():
        return db.execute(delete(UIEvent).where(UIEvent.created_at < cutoff)).rowcount


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--retention-seconds", type=int, default=86400)
    args = parser.parse_args()
    if not os.getenv("DATABASE_URL"):
        parser.error("Set DATABASE_URL before cleanup")
    if args.retention_seconds <= 0:
        parser.error("Retention must be positive")
    engine = build_engine()
    try:
        print({"deleted_events": cleanup(build_session_factory(engine), args.retention_seconds)})
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
