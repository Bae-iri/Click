"""User-row locking gives one serialization order to queue and session claims."""
from contextlib import contextmanager
from hashlib import sha256
import json
import time
from uuid import uuid4
from fastapi import HTTPException
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError, OperationalError
from app.models.personalization import User
from app.models.voice_queue import VoiceQueueState, VoiceRequest
from app.models.agent_session import AgentSession, AgentTurn, AgentCheckpoint
from app.models.lifecycle import SafetyConfirmation
from app.schemas.voice_queue import VoiceQueueResponse, VoiceRequestResponse

ACTIVE_SESSION = ("IN_PROGRESS", "WAITING_CONFIRMATION", "PAUSED")
TERMINAL = ("COMPLETED", "CANCELLED", "FAILED")


def conflict(code):
    raise HTTPException(409, headers={"X-Conflict-Code": code})


class VoiceQueueService:
    def __init__(self, factory, idle_seconds=1800, clock=time.time):
        self.factory, self.idle_seconds, self.clock = factory, idle_seconds, clock

    @contextmanager
    def transaction(self, user_id):
        with self.factory() as db:
            try:
                # UPDATE also obtains a write lock on SQLite, where FOR UPDATE is ignored.
                if db.execute(update(User).where(User.id == user_id).values(id=user_id)).rowcount != 1:
                    raise HTTPException(401)
                state = db.get(VoiceQueueState, user_id)
                if state is None:
                    state = VoiceQueueState(user_id=user_id, last_sequence=0, version=0, awaiting_replacement=False)
                    db.add(state)
                    db.flush()
                yield db, state, int(self.clock())
                db.commit()
            except IntegrityError as error:
                db.rollback()
                raise HTTPException(409, headers={"X-Conflict-Code": "QUEUE_CONFLICT"}) from error
            except OperationalError as error:
                db.rollback()
                if (db.bind.dialect.name == "sqlite" and "locked" in str(error.orig).lower()) or getattr(error.orig, "sqlstate", None) in {"40001", "40P01"}:
                    raise HTTPException(409, headers={"X-Conflict-Code": "QUEUE_BUSY"}) from error
                raise

    @staticmethod
    def owned(db, user_id, request_id):
        row = db.get(VoiceRequest, str(request_id))
        if row is None or row.user_id != user_id:
            raise HTTPException(404)
        return row

    @staticmethod
    def running(db, user_id):
        return db.scalar(select(VoiceRequest).where(VoiceRequest.user_id == user_id, VoiceRequest.status == "RUNNING"))

    @staticmethod
    def end(row, status, now, failure=None):
        row.status, row.updated_at, row.failure_code, row.raw_goal = status, now, failure, None

    @staticmethod
    def cancel_session(db, session_id, now):
        if session_id is None:
            return
        db.execute(update(AgentSession).where(AgentSession.id == session_id, AgentSession.status.in_(ACTIVE_SESSION)).values(
            status="CANCELLED", version=AgentSession.version + 1, updated_at=now, lease_token=None, lease_until=None))
        db.execute(update(AgentTurn).where(AgentTurn.session_id == session_id).values(response=None))
        db.execute(delete(AgentCheckpoint).where(AgentCheckpoint.session_id == session_id))
        db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == session_id))

    def clear_normal(self, db, state, now):
        db.execute(update(VoiceRequest).where(VoiceRequest.user_id == state.user_id,
            VoiceRequest.status == "QUEUED", VoiceRequest.priority == "NORMAL").values(status="CANCELLED", raw_goal=None, updated_at=now))

    def interrupt(self, db, state, now, immediate=False):
        running = self.running(db, state.user_id)
        if running:
            self.cancel_session(db, running.agent_session_id, now)
            self.end(running, "CANCELLED", now)
        if immediate:
            self.clear_normal(db, state, now)
        state.awaiting_replacement = True
        db.flush()

    def expire(self, db, state, now):
        rows = db.scalars(select(VoiceRequest).where(VoiceRequest.user_id == state.user_id,
            VoiceRequest.status.in_(("QUEUED", "RUNNING")), VoiceRequest.expires_at <= now)).all()
        for row in rows:
            self.cancel_session(db, row.agent_session_id, now)
            self.end(row, "FAILED", now, "REQUEST_EXPIRED")
        if rows:
            state.version += 1
            db.flush()

    def snapshot(self, db, state, row=None, claimed=False):
        db.flush()
        queued = db.scalars(select(VoiceRequest).where(VoiceRequest.user_id == state.user_id, VoiceRequest.status == "QUEUED")
            .order_by((VoiceRequest.priority == "VIP").desc(), VoiceRequest.sequence)).all()
        current = self.running(db, state.user_id)
        return VoiceQueueResponse(queue_version=state.version, awaiting_replacement=state.awaiting_replacement,
            request=VoiceRequestResponse.model_validate(row) if row else None,
            running=VoiceRequestResponse.model_validate(current) if current else None,
            queued=[VoiceRequestResponse.model_validate(item) for item in queued], claimed=claimed)

    def enqueue(self, user_id, request, *, interrupt=False):
        data = request.model_dump(mode="json")
        digest = sha256(json.dumps({"operation": "interrupt" if interrupt else "enqueue", **data}, sort_keys=True).encode()).hexdigest()
        with self.transaction(user_id) as (db, state, now):
            self.expire(db, state, now)
            existing = db.get(VoiceRequest, str(request.id))
            if existing:
                if existing.user_id != user_id:
                    raise HTTPException(404)
                if existing.payload_hash != digest:
                    conflict("REQUEST_ID_REUSED")
                return self.snapshot(db, state, existing)
            priority = "VIP" if interrupt else request.priority
            if priority == "NORMAL" and state.awaiting_replacement:
                self.clear_normal(db, state, now)
                state.awaiting_replacement = False
            count = len(db.scalars(select(VoiceRequest.id).where(VoiceRequest.user_id == user_id,
                VoiceRequest.status.in_(("QUEUED", "RUNNING")))).all())
            # An interrupt must remain possible when the normal queue is full.
            if count >= 100 and not interrupt:
                raise HTTPException(429, headers={"Retry-After": "5"})
            state.last_sequence += 1
            row = VoiceRequest(id=str(request.id), user_id=user_id, raw_goal=request.raw_goal, priority=priority,
                status="QUEUED", sequence=state.last_sequence, payload_hash=digest,
                created_at=now, updated_at=now, expires_at=now + 86400)
            db.add(row)
            db.flush()
            if interrupt:
                self.process_vips(db, state, now, request.clear_queued)
            state.version += 1
            return self.snapshot(db, state, row)

    def process_vips(self, db, state, now, immediate=False):
        vips = db.scalars(select(VoiceRequest).where(VoiceRequest.user_id == state.user_id,
            VoiceRequest.status == "QUEUED", VoiceRequest.priority == "VIP").order_by(VoiceRequest.sequence)).all()
        if vips:
            self.interrupt(db, state, now, immediate)
            for row in vips:
                # VIP is a server-side stop command, not an AI task.
                self.end(row, "COMPLETED", now)
            state.version += 1
            db.flush()
        return bool(vips)

    def advance(self, db, state, now):
        if self.process_vips(db, state, now):
            return False
        running = self.running(db, state.user_id)
        if running:
            session = db.get(AgentSession, running.agent_session_id) if running.agent_session_id else None
            if session and session.status in ACTIVE_SESSION and session.expires_at > now:
                return False
            status = "COMPLETED" if session and session.status == "COMPLETED" else "CANCELLED" if session and session.status == "CANCELLED" else "FAILED"
            self.cancel_session(db, running.agent_session_id, now) if status != "COMPLETED" else None
            self.end(running, status, now, "SESSION_CLOSED" if status == "FAILED" else None)
            state.version += 1
            db.flush()
        if state.awaiting_replacement:
            return False
        row = db.scalar(select(VoiceRequest).where(VoiceRequest.user_id == state.user_id,
            VoiceRequest.priority == "NORMAL", VoiceRequest.status == "QUEUED").order_by(VoiceRequest.sequence).limit(1))
        if row is None:
            return False
        session = AgentSession(id=str(uuid4()), user_id=state.user_id, status="IN_PROGRESS", version=0,
            created_at=now, updated_at=now, expires_at=now + self.idle_seconds)
        db.add(session)
        db.flush()
        row.status, row.agent_session_id, row.updated_at = "RUNNING", session.id, now
        state.version += 1
        db.flush()
        return True

    def claim(self, user_id):
        with self.transaction(user_id) as (db, state, now):
            self.expire(db, state, now)
            claimed = self.advance(db, state, now)
            return self.snapshot(db, state, claimed=claimed)

    def finish(self, user_id, request_id, *, failure=None):
        desired = "FAILED" if failure else "COMPLETED"
        with self.transaction(user_id) as (db, state, now):
            self.expire(db, state, now)
            row = self.owned(db, user_id, request_id)
            if row.priority != "NORMAL":
                conflict("VIP_IS_INTERRUPT")
            if row.status == desired:
                if failure and row.failure_code != failure:
                    conflict("REQUEST_ALREADY_FINISHED")
                return self.snapshot(db, state, row)
            if row.status != "RUNNING":
                conflict("REQUEST_NOT_RUNNING")
            session = db.get(AgentSession, row.agent_session_id) if row.agent_session_id else None
            if not failure and (session is None or session.status != "COMPLETED"):
                conflict("AGENT_SESSION_NOT_COMPLETED")
            if failure:
                if session and session.status == "COMPLETED":
                    conflict("AGENT_SESSION_ALREADY_COMPLETED")
                self.cancel_session(db, row.agent_session_id, now)
            self.end(row, desired, now, failure)
            state.version += 1
            db.flush()
            claimed = self.advance(db, state, now)
            return self.snapshot(db, state, row, claimed)

    def get(self, user_id, request_id=None):
        with self.transaction(user_id) as (db, state, now):
            self.expire(db, state, now)
            row = self.owned(db, user_id, request_id) if request_id else None
            return self.snapshot(db, state, row)

    def cleanup(self, retention=86400):
        if retention <= 0:
            raise ValueError("Retention must be positive")
        with self.factory() as db:
            user_ids = db.scalars(select(VoiceQueueState.user_id)).all()
        removed = 0
        for user_id in user_ids:
            try:
                with self.transaction(user_id) as (db, state, now):
                    self.expire(db, state, now)
                    removed += db.execute(delete(VoiceRequest).where(VoiceRequest.user_id == user_id,
                        VoiceRequest.status.in_(TERMINAL), VoiceRequest.updated_at <= now - retention)).rowcount
            except HTTPException as error:
                if error.status_code != 401:
                    raise
        return removed
