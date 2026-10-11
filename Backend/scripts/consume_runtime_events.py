"""Run offline AI2 ingestion independently of guidance. Requires model-training consent."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
from datetime import timezone

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / "AI-Agent" / "src"))

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from app.database import build_engine, build_session_factory
from app import models
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.personalization import PersonalizationRepository, UserNotFoundError
from ai_agent.graph_proposal.ingestion.runtime_events import label_event
from scripts.render_start import database_url

CONSUMER = "ai2-runtime-label.v1"


def consume(factory, *, limit=100, now=None):
    if not 1 <= limit <= 1000:
        raise ValueError("limit must be between 1 and 1000")
    now = int(time.time()) if now is None else now
    with factory() as db:
        consumed = select(RuntimeEventConsumption.event_id).where(RuntimeEventConsumption.consumer == CONSUMER)
        ids = list(db.scalars(select(RuntimeEvent.id).where(RuntimeEvent.id.not_in(consumed),
            RuntimeEvent.expires_at > now).order_by(RuntimeEvent.session_id,
            RuntimeEvent.sequence).limit(limit)))
    count = 0
    for identifier in ids:
        try:
            with factory() as db:
                event = db.get(RuntimeEvent, identifier)
                if event is None or event.expires_at <= now:
                    continue
                profiles = PersonalizationRepository(db)
                profiles.lock_user(event.user_id)
                if db.scalar(select(RuntimeEventConsumption.id).where(
                        RuntimeEventConsumption.event_id == identifier, RuntimeEventConsumption.consumer == CONSUMER)):
                    continue
                consent = profiles.latest_consent(event.user_id, "model_training")
                recorded = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp() if consent else float("inf")
                # Never retroactively train on events recorded before consent.
                eligible = consent and consent.consented and event.created_at >= recorded
                result = label_event(event.payload) if eligible else {"label": "not_eligible"}
                db.add(RuntimeEventConsumption(event_id=identifier, consumer=CONSUMER,
                    result=result, consumed_at=now))
                db.commit()
                count += 1
        except (IntegrityError, UserNotFoundError):
            # A concurrent consumer committed the unique receipt first.
            continue
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()
    if not os.environ.get("DATABASE_URL"):
        parser.error("DATABASE_URL is required")
    raw = os.environ["DATABASE_URL"]
    engine = build_engine(database_url(raw) if raw.startswith(("postgres://", "postgresql://")) else raw)
    try:
        print(json.dumps({"processed": consume(build_session_factory(engine), limit=args.limit)}))
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
