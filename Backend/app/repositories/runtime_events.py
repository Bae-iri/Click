"""Durable execution outbox; receipts and retention never depend on online AI2 calls."""
import time
import re
from sqlalchemy import delete
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption

RETENTION_SECONDS = 7 * 86400


def device_cohort(context):
    """Operational device grouping, never demographics, account IDs or UI text."""
    context = context or {}
    manufacturer = str(context.get("manufacturer") or "").strip().lower()
    if manufacturer not in {"samsung", "google", "xiaomi", "oppo", "vivo", "motorola", "sony", "lg", "oneplus", "huawei"}:
        manufacturer = "unknown"
    version = re.fullmatch(r"(?:Android\s+)?(\d{1,2})(?:[.][\d.]+)?", str(context.get("os_version") or ""), re.IGNORECASE)
    return manufacturer + ":android-" + (version.group(1) if version else "unknown")


def append(db, row, request_id, payload, *, kind="turn", episode_id=None, graph_hash=None, graph_reference=None, now=None):
    now = int(time.time()) if now is None else now
    reference = graph_reference or {"task_id": row.task_id, "version": row.graph_version,
                                   "sha256": graph_hash or row.graph_hash}
    payload = {**payload, "graph_reference": reference, "source_kind": "runtime",
               "device_cohort": device_cohort(row.device_context)}
    db.add(RuntimeEvent(session_id=row.id, user_id=row.user_id, episode_id=episode_id or row.episode_id,
        sequence=row.version, request_id=request_id, graph_hash=graph_hash or row.graph_hash,
        kind=kind, payload=payload, created_at=now, expires_at=now + RETENTION_SECONDS))


def cleanup(factory, now=None):
    with factory() as db:
        now = int(time.time()) if now is None else now
        # A session summary may refer to events older than its final receipt FK.
        # Remove it at the earliest contributing event's expiry, not the last one's.
        db.execute(delete(RuntimeEventConsumption).where(
            RuntimeEventConsumption.expires_at <= now))
        removed = db.execute(delete(RuntimeEvent).where(RuntimeEvent.expires_at <= now)).rowcount
        db.commit()
        return removed
