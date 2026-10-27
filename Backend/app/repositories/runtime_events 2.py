"""Durable execution outbox; receipts and retention never depend on online AI2 calls."""
import time
from sqlalchemy import delete
from app.models.runtime_event import RuntimeEvent

RETENTION_SECONDS = 7 * 86400


def append(db, row, request_id, payload, *, kind="turn", episode_id=None, graph_hash=None, now=None):
    now = int(time.time()) if now is None else now
    db.add(RuntimeEvent(session_id=row.id, user_id=row.user_id, episode_id=episode_id or row.episode_id,
        sequence=row.version, request_id=request_id, graph_hash=graph_hash or row.graph_hash,
        kind=kind, payload=payload, created_at=now, expires_at=now + RETENTION_SECONDS))


def cleanup(factory, now=None):
    with factory() as db:
        removed = db.execute(delete(RuntimeEvent).where(RuntimeEvent.expires_at <= (int(time.time()) if now is None else now))).rowcount
        db.commit()
        return removed
