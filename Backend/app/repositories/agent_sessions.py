"""Short SQL transactions and expiring leases fence concurrent runtime calls."""
from copy import deepcopy
import time
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import delete, or_, select, update
from sqlalchemy.exc import IntegrityError, OperationalError

from app.models.agent_session import AgentCheckpoint, AgentSession, AgentTurn
from app.schemas.agent_session import AgentSessionResponse
from app.services.safety import save_confirmation
from app.models.lifecycle import SafetyConfirmation
from app.models.voice_queue import VoiceRequest
from app.models.personalization import User

ACTIVE = ("IN_PROGRESS", "WAITING_CONFIRMATION")


class AgentSessionRepository:
    def __init__(self, factory, idle_seconds=1800, lease_seconds=60, clock=time.time):
        if idle_seconds <= 0 or lease_seconds <= 0:
            raise ValueError("Session timeouts must be positive")
        self.factory = factory
        self.idle_seconds = idle_seconds
        self.lease_seconds = lease_seconds
        self.clock = clock

    @staticmethod
    def conflict(code):
        raise HTTPException(409, headers={"X-Conflict-Code": code})

    @staticmethod
    def owned(db, session_id, user_id):
        row = db.get(AgentSession, session_id)
        if row is None:
            raise HTTPException(404)
        if row.user_id != user_id:
            raise HTTPException(403)
        return row

    def expire(self, db, row, now):
        if row.status in ACTIVE and row.expires_at <= now:
            db.execute(update(AgentSession).where(AgentSession.id == row.id,
                AgentSession.status.in_(ACTIVE), AgentSession.expires_at <= now).values(
                status="EXPIRED", version=AgentSession.version + 1, lease_token=None,
                lease_until=None, updated_at=now))
            db.commit()
            db.refresh(row)

    def claim(self, request, request_hash):
        now = int(self.clock())
        with self.factory() as db:
            try:
                self.guard_voice_request(db, request, now)
                row = db.get(AgentSession, request.session_id)
                if row is not None and row.user_id != request.user_id:
                    raise HTTPException(403)
                command_type = getattr(request, "command_type", None)
                episode_id = getattr(request, "episode_id", None)
                if row is not None and episode_id is not None and row.episode_id != episode_id:
                    self.conflict("EPISODE_MISMATCH")
                previous = db.scalar(select(AgentTurn).where(AgentTurn.user_id == request.user_id,
                                                            AgentTurn.request_id == request.request_id))
                if previous and (previous.session_id != request.session_id or previous.request_hash != request_hash):
                    self.conflict("REQUEST_ID_REUSED")
                if row is None:
                    raw_goal = getattr(request, "raw_goal", None)
                    goal = getattr(request, "goal", None)
                    if raw_goal is None and goal is None:
                        raise HTTPException(404)
                    if request.expected_version not in (None, 0):
                        self.conflict("STALE_VERSION")
                    row = AgentSession(id=request.session_id, user_id=request.user_id,
                        status="IN_PROGRESS", version=0, created_at=now, updated_at=now,
                        expires_at=now + self.idle_seconds,
                        episode_id=str(uuid4()) if goal else None,
                        accepted_goal_type=goal.goal_type if goal else None,
                        goal=goal.model_dump(mode="json") if goal else None,
                        device_context=(request.device_context.model_dump(mode="json")
                            if getattr(request, "device_context", None) else None))
                    db.add(row)
                    db.flush()
                self.expire(db, row, now)
                if row.status == "EXPIRED":
                    raise HTTPException(410)
                if row.status == "CANCELLED":
                    self.conflict("SESSION_CANCELLED")
                if previous and previous.response is not None:
                    if previous.version != row.version:
                        self.conflict("STALE_REPLAY")
                    saved = db.get(AgentCheckpoint, row.id)
                    return {"cached": deepcopy(previous.response),
                        "schema_version": saved.schema_version if saved else None,
                        "graph_version": saved.graph_version if saved else None}
                if command_type == "start_task" and row.version > 0:
                    self.conflict("ACTIVE_TASK_EXISTS" if row.status in ACTIVE else "SESSION_TERMINATED")
                if row.status not in ACTIVE:
                    self.conflict("SESSION_CLOSED")
                if (row.version > 0 and request.expected_version != row.version) or (
                    row.version == 0 and request.expected_version not in (None, 0)
                ):
                    self.conflict("STALE_VERSION")
                if previous and previous.version != row.version + 1:
                    self.conflict("STALE_VERSION")
                token = str(uuid4())
                version = row.version
                changed = db.execute(update(AgentSession).where(AgentSession.id == row.id,
                    AgentSession.version == version, AgentSession.status.in_(ACTIVE),
                    AgentSession.expires_at > now,
                    or_(AgentSession.lease_token.is_(None), AgentSession.lease_until <= now)).values(
                        lease_token=token, lease_until=now + self.lease_seconds))
                if changed.rowcount != 1:
                    self.conflict("TURN_IN_PROGRESS")
                if previous is None:
                    previous = AgentTurn(session_id=row.id, user_id=request.user_id,
                        request_id=request.request_id, request_hash=request_hash,
                        version=version + 1, created_at=now)
                    db.add(previous)
                checkpoint = db.get(AgentCheckpoint, row.id)
                result = {"token": token, "version": version,
                    "checkpoint": deepcopy(checkpoint.state) if checkpoint else None,
                    "schema_version": checkpoint.schema_version if checkpoint else None,
                    "graph_version": checkpoint.graph_version if checkpoint else None}
                db.commit()
                return result
            except IntegrityError as error:
                db.rollback()
                # Concurrent creation/ID reservation. Retry the identical request.
                raise HTTPException(409, headers={"X-Conflict-Code": "TURN_IN_PROGRESS"}) from error
            except OperationalError as error:
                db.rollback()
                if (db.bind.dialect.name == "sqlite" and "locked" in str(error.orig).lower()) or (
                    getattr(error.orig, "sqlstate", None) in {"40001", "40P01"}
                ):
                    raise HTTPException(409, headers={"X-Conflict-Code": "TURN_IN_PROGRESS"}) from error
                raise

    def finish(self, request, claim, response, checkpoint, schema_version, graph_version):
        now = int(self.clock())
        with self.factory() as db:
            self.guard_voice_request(db, request, now)
            if request.user_confirmation == "accepted":
                approval = db.get(SafetyConfirmation, request.session_id)
                if approval is None or approval.id != request.confirmation_id or approval.expires_at <= now:
                    self.conflict("CONFIRMATION_INVALID")
            updated = db.execute(update(AgentSession).where(AgentSession.id == request.session_id,
                AgentSession.user_id == request.user_id, AgentSession.version == claim["version"],
                AgentSession.lease_token == claim["token"], AgentSession.lease_until > now,
                AgentSession.expires_at > now, AgentSession.status.in_(ACTIVE)).values(
                    version=claim["version"] + 1, status=response.task_status,
                    task_id=response.task_id, state_id=response.state_id,
                    last_guide_command_id=response.guide_command.guide_command_id,
                    updated_at=now, expires_at=now + self.idle_seconds,
                    lease_token=None, lease_until=None))
            if updated.rowcount != 1:
                self.conflict("TURN_SUPERSEDED")
            stored = db.get(AgentCheckpoint, request.session_id)
            if checkpoint is not None:
                if stored is None:
                    db.add(AgentCheckpoint(session_id=request.session_id, schema_version=schema_version,
                                           graph_version=graph_version, state=checkpoint))
                else:
                    stored.state = checkpoint
                    stored.schema_version = schema_version
                    stored.graph_version = graph_version
            elif stored is not None:
                db.delete(stored)
            turn = db.scalar(select(AgentTurn).where(AgentTurn.user_id == request.user_id,
                                                     AgentTurn.request_id == request.request_id))
            save_confirmation(db, request, response, now)
            turn.response = response.model_dump(mode="json")
            row = db.get(AgentSession, request.session_id)
            db.commit()

    def pin_graph(self, session_id, user_id, reference):
        with self.factory() as db:
            row = self.owned(db, session_id, user_id)
            row.graph_version = reference["version"]
            row.graph_hash = reference["sha256"]
            db.commit()

    def release(self, session_id, token):
        with self.factory() as db:
            db.execute(update(AgentSession).where(AgentSession.id == session_id,
                AgentSession.lease_token == token).values(lease_token=None, lease_until=None))
            db.commit()

    def guard_voice_request(self, db, request, now):
        # Same lock order as VIP: user -> session. No lock is held during AI work.
        if db.execute(update(User).where(User.id == request.user_id).values(id=request.user_id)).rowcount != 1:
            raise HTTPException(401)
        queued = db.scalar(select(VoiceRequest).where(VoiceRequest.agent_session_id == request.session_id,
                                                     VoiceRequest.user_id == request.user_id))
        if queued and (queued.status != "RUNNING" or queued.expires_at <= now):
            self.conflict("VOICE_REQUEST_NOT_RUNNING")
        raw_goal = getattr(request, "raw_goal", None)
        if queued and raw_goal is not None and raw_goal != queued.raw_goal:
            self.conflict("VOICE_GOAL_MISMATCH")

    def get(self, session_id, user_id):
        now = int(self.clock())
        with self.factory() as db:
            row = self.owned(db, session_id, user_id)
            self.expire(db, row, now)
            return self.describe(db, row, now)

    def cancel(self, session_id, user_id, expected_version):
        now = int(self.clock())
        with self.factory() as db:
            row = self.owned(db, session_id, user_id)
            self.expire(db, row, now)
            if row.status == "CANCELLED":
                return self.describe(db, row, now)
            if row.status == "EXPIRED":
                raise HTTPException(410)
            if row.status not in ACTIVE or row.version != expected_version:
                self.conflict("STALE_VERSION")
            changed = db.execute(update(AgentSession).where(AgentSession.id == session_id,
                AgentSession.version == expected_version, AgentSession.status.in_(ACTIVE),
                AgentSession.expires_at > now).values(status="CANCELLED", version=expected_version + 1,
                    updated_at=now, lease_token=None, lease_until=None))
            if changed.rowcount != 1:
                self.conflict("STALE_VERSION")
            # Cancelled guides must never be replayed; clear payloads immediately.
            db.execute(update(AgentTurn).where(AgentTurn.session_id == session_id).values(response=None))
            db.execute(delete(AgentCheckpoint).where(AgentCheckpoint.session_id == session_id))
            db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == session_id))
            db.commit()
            db.refresh(row)
            return self.describe(db, row, now)

    def typed_details(self, session_id, user_id):
        with self.factory() as db:
            row = self.owned(db, session_id, user_id)
            return {
                "episode_id": row.episode_id,
                "goal_type": row.accepted_goal_type,
                "goal": deepcopy(row.goal),
                "device_context": deepcopy(row.device_context),
                "status": row.status,
                "version": row.version,
                "task_id": row.task_id or "MVP-01",
                "graph_version": row.graph_version or "1.0.0",
                "graph_hash": row.graph_hash,
            }

    def has_request(self, user_id, request_id):
        with self.factory() as db:
            return db.scalar(select(AgentTurn.id).where(AgentTurn.user_id == user_id,
                AgentTurn.request_id == request_id)) is not None

    def apply_typed_control(self, request, graph_hash, request_hash):
        now = int(self.clock())
        with self.factory() as db:
            row = self.owned(db, request.session_id, request.user_id)
            previous = db.scalar(select(AgentTurn).where(AgentTurn.user_id == request.user_id,
                AgentTurn.request_id == request.request_id))
            if previous and (previous.session_id != request.session_id
                    or previous.request_hash != request_hash):
                self.conflict("REQUEST_ID_REUSED")
            if previous and previous.response and "_control" in previous.response:
                return deepcopy(previous.response["_control"])
            self.expire(db, row, now)
            if row.episode_id != request.episode_id:
                self.conflict("EPISODE_MISMATCH")
            if row.version != request.expected_version:
                self.conflict("STALE_VERSION")
            command = request.command_type
            if command == "pause_task":
                if row.status == "PAUSED":
                    changed = False
                elif row.status in ACTIVE:
                    row.status = "PAUSED"
                    changed = True
                else:
                    self.conflict("COMMAND_NOT_ALLOWED")
            elif command == "resume_task":
                if row.status != "PAUSED":
                    self.conflict("COMMAND_NOT_ALLOWED")
                row.status = "IN_PROGRESS"
                changed = True
            elif command == "cancel_task":
                if row.status == "CANCELLED":
                    changed = False
                elif row.status in ACTIVE or row.status == "PAUSED":
                    row.status = "CANCELLED"
                    changed = True
                else:
                    self.conflict("SESSION_TERMINATED")
            else:
                raise ValueError("Not a control command")
            if changed:
                row.version += 1
                row.updated_at = now
            row.lease_token = row.lease_until = None
            db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == row.id))
            if command in {"pause_task", "cancel_task"}:
                row.last_guide_command_id = None
            if command == "cancel_task":
                db.execute(delete(AgentCheckpoint).where(AgentCheckpoint.session_id == row.id))
                db.execute(update(AgentTurn).where(AgentTurn.session_id == row.id).values(response=None))
            result = {
                "episode_id": row.episode_id,
                "goal_type": row.accepted_goal_type,
                "status": row.status,
                "version": row.version,
                "task_id": row.task_id or "MVP-01",
                "graph_version": row.graph_version or "1.0.0",
                "graph_hash": row.graph_hash or graph_hash,
                "changed": changed,
            }
            db.add(AgentTurn(session_id=row.id, user_id=request.user_id,
                request_id=request.request_id, request_hash=request_hash,
                version=row.version, response={"_control": result}, created_at=now))
            db.commit()
            return result

    def prepare_replace(self, request):
        now = int(self.clock())
        with self.factory() as db:
            row = self.owned(db, request.session_id, request.user_id)
            self.expire(db, row, now)
            if row.episode_id != request.episode_id:
                self.conflict("EPISODE_MISMATCH")
            if row.version != request.expected_version or row.status not in ACTIVE + ("PAUSED",):
                self.conflict("COMMAND_NOT_ALLOWED")
            new_episode = str(uuid4())
            row.episode_id = new_episode
            row.accepted_goal_type = request.goal.goal_type
            row.goal = request.goal.model_dump(mode="json")
            row.device_context = request.device_context.model_dump(mode="json")
            row.status = "IN_PROGRESS"
            row.task_id = row.state_id = row.last_guide_command_id = None
            db.execute(delete(AgentCheckpoint).where(AgentCheckpoint.session_id == row.id))
            db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == row.id))
            db.commit()
            return new_episode

    @staticmethod
    def describe(db, row, now):
        return AgentSessionResponse(session_id=row.id, status=row.status, version=row.version,
            task_id=row.task_id, state_id=row.state_id, last_guide_command_id=row.last_guide_command_id,
            created_at=row.created_at, updated_at=row.updated_at, expires_at=row.expires_at,
            processing=bool(row.lease_token and row.lease_until and row.lease_until > now),
            requires_fresh_observation=row.status in ACTIVE,
            requires_goal=row.status in ACTIVE and db.get(AgentCheckpoint, row.id) is None)

    def cleanup(self, retention_seconds=86400):
        if retention_seconds < 0:
            raise ValueError("Retention must not be negative")
        now = int(self.clock())
        with self.factory() as db:
            expired = db.execute(update(AgentSession).where(AgentSession.status.in_(ACTIVE),
                AgentSession.expires_at <= now).values(status="EXPIRED", version=AgentSession.version + 1,
                    lease_token=None, lease_until=None, updated_at=now)).rowcount
            removed = db.execute(delete(AgentSession).where(AgentSession.status.not_in(ACTIVE),
                AgentSession.updated_at <= now - retention_seconds)).rowcount
            db.commit()
            return {"expired": expired, "removed": removed}
