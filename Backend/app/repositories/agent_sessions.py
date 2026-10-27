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
from app.services.task_outcome import make_outcome, answer_outcome, follow_up

ACTIVE = ("IN_PROGRESS", "WAITING_CONFIRMATION", "WAITING_RESULT", "WAITING_ADJUSTMENT")


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
            outcome = deepcopy(row.task_outcome)
            if outcome and outcome.get("user", {}).get("status") is None:
                outcome = answer_outcome(outcome, "no_response", now)
            db.execute(update(AgentSession).where(AgentSession.id == row.id,
                AgentSession.status.in_(ACTIVE), AgentSession.expires_at <= now).values(
                status="EXPIRED", version=AgentSession.version + 1, lease_token=None,
                lease_until=None, updated_at=now, task_outcome=outcome))
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
                        episode_id=str(uuid4()),
                        accepted_goal_type=goal.goal_type if goal else None,
                        goal=goal.model_dump(mode="json") if goal else None,
                        device_context=(request.device_context.model_dump(mode="json")
                            if getattr(request, "device_context", None) else None))
                    db.add(row)
                    db.flush()
                if row.version == 0 and getattr(request, "goal", None) and row.goal is None:
                    row.episode_id = str(uuid4())
                    row.accepted_goal_type = request.goal.goal_type
                    row.goal = request.goal.model_dump(mode="json")
                    row.device_context = request.device_context.model_dump(mode="json")
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
                if episode_id is not None and row.episode_id != episode_id:
                    self.conflict("EPISODE_MISMATCH")
                if command_type == "start_task" and row.version > 0:
                    self.conflict("ACTIVE_TASK_EXISTS" if row.status in ACTIVE else "SESSION_TERMINATED")
                if row.status in {"WAITING_RESULT", "WAITING_ADJUSTMENT"}:
                    self.conflict("COMMAND_NOT_ALLOWED")
                if row.status not in ACTIVE:
                    self.conflict("SESSION_CLOSED")
                if row.status in {"WAITING_RESULT", "WAITING_ADJUSTMENT"}:
                    self.conflict("RESULT_RESPONSE_REQUIRED")
                if (row.version > 0 and request.expected_version != row.version) or (
                    row.version == 0 and request.expected_version not in (None, 0)
                ):
                    self.conflict("STALE_VERSION")
                if previous and previous.version != row.version + 1:
                    self.conflict("STALE_VERSION")
                # Queue claim creates an empty version-0 session before FE confirms
                # its goal. Initialize that session just like a direct typed start.
                if command_type == "start_task" and row.version == 0 and row.episode_id is None:
                    row.episode_id = str(uuid4())
                    row.accepted_goal_type = request.goal.goal_type
                    row.goal = request.goal.model_dump(mode="json")
                    row.device_context = request.device_context.model_dump(mode="json")
                    db.flush()
                token = str(uuid4())
                if command_type == "start_task" and getattr(request, "workflow_version", None):
                    row.workflow = {"version": request.workflow_version}
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
                    "episode_id": row.episode_id, "recovery": deepcopy(row.recovery or {}),
                    "workflow": deepcopy(row.workflow or {}),
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

    def finish(self, request, claim, response, checkpoint, schema_version, graph_version,
               graph_reference=None, recovery_plan=None, execution_event=None, execution_reference=None):
        now = int(self.clock())
        with self.factory() as db:
            self.guard_voice_request(db, request, now)
            row = self.owned(db, request.session_id, request.user_id)
            response.episode_id = (recovery_plan or {}).get("episode_id", row.episode_id)
            technical = response.metadata.get("technical_outcome") or {"status": "UNVERIFIABLE", "reason": "insufficient_evidence"}
            outcome = {"technical": deepcopy(technical), "user": {"status": None, "confirmed_at": None},
                       "final": None, "checklist": response.metadata.get("checklist", [])}
            if response.task_status == "WAITING_RESULT":
                direction = ((row.goal or {}).get("slots") or {}).get("direction", "increase")
                outcome = make_outcome(technical, now, direction, previous=outcome)
                response.guide_command.message = outcome["prompt"]["message"]
                response.metadata["result_prompt"] = outcome["prompt"]
            response.metadata["task_outcome"] = outcome
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
                    updated_at=now, expires_at=outcome.get("prompt", {}).get("expires_at", now + self.idle_seconds),
                    task_outcome=outcome,
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
            if recovery_plan and recovery_plan.get("episode_id"):
                db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == request.session_id))
            else:
                save_confirmation(db, request, response, now)
            turn.response = response.model_dump(mode="json")
            row = db.get(AgentSession, request.session_id)
            if recovery_plan:
                row.recovery = recovery_plan["recovery"]
                if recovery_plan.get("episode_id"):
                    row.episode_id = recovery_plan["episode_id"]
                    row.last_guide_command_id = None
            if response.task_status == "WAITING_RESULT":
                row.workflow = {**(row.workflow or {}), "result_prompt": response.metadata["result_prompt"]}
            if graph_reference is not None:
                row.graph_version = graph_reference["version"]
                row.graph_hash = graph_reference["sha256"]
            from app.repositories.runtime_events import append
            append(db, row, request.request_id, {"runtime": execution_event or {},
                "reselection": response.metadata.get("reselection")}, now=now,
                episode_id=claim["episode_id"], graph_hash=(execution_event or {}).get("graph_hash"),
                graph_reference=execution_reference,
                kind="reselection" if response.metadata.get("reselection") else "turn")
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
                    updated_at=now, lease_token=None, lease_until=None,
                    task_outcome=answer_outcome(row.task_outcome or make_outcome({}, now), "stopped", now)))
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
                "workflow": deepcopy(row.workflow or {}),
                "device_context": deepcopy(row.device_context),
                "status": row.status,
                "version": row.version,
                "task_id": row.task_id or "MVP-01",
                "graph_version": row.graph_version or "1.0.0",
                "graph_hash": row.graph_hash,
                "task_outcome": deepcopy(row.task_outcome),
            }

    def has_request(self, user_id, request_id, session_id=None):
        with self.factory() as db:
            query = select(AgentTurn.id).where(AgentTurn.user_id == user_id,
                AgentTurn.request_id == request_id)
            if session_id is not None:
                query = query.where(AgentTurn.session_id == session_id)
            return db.scalar(query) is not None

    def resolution_version(self, request):
        now = int(self.clock())
        with self.factory() as db:
            self.guard_voice_request(db, request, now)
            row = self.owned(db, request.session_id, request.user_id)
            self.expire(db, row, now)
            if row.version != request.expected_version:
                self.conflict("STALE_VERSION")
            if row.version != 0 or row.episode_id is not None or row.status != "IN_PROGRESS":
                self.conflict("COMMAND_NOT_ALLOWED")
            return row.version

    def acknowledge_result(self, request):
        canonical = request.model_dump(mode="json")
        canonical["result_response"] = request.result_response
        import json
        from hashlib import sha256
        digest = sha256(json.dumps(canonical, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        now = int(self.clock())
        with self.factory() as db:
            # Use the same user-first lock order as queue operations.
            if db.execute(update(User).where(User.id == request.user_id).values(id=request.user_id)).rowcount != 1:
                raise HTTPException(401)
            row = self.owned(db, request.session_id, request.user_id)
            previous = db.scalar(select(AgentTurn).where(AgentTurn.user_id == request.user_id,
                AgentTurn.request_id == request.request_id))
            if previous:
                if previous.session_id != row.id or previous.request_hash != digest:
                    self.conflict("REQUEST_ID_REUSED")
                if previous.version != row.version:
                    self.conflict("STALE_REPLAY")
                if previous.response and "_result" in previous.response:
                    return deepcopy(previous.response["_result"])
                self.conflict("COMMAND_NOT_ALLOWED")
            if row.episode_id != request.episode_id:
                self.conflict("EPISODE_MISMATCH")
            if row.version != request.expected_version:
                self.conflict("STALE_VERSION")
            if row.status != "COMPLETED" or not row.graph_hash or row.result_response is not None:
                self.conflict("COMMAND_NOT_ALLOWED")
            changed = db.execute(update(AgentSession).where(AgentSession.id == row.id,
                AgentSession.version == request.expected_version, AgentSession.status == "COMPLETED",
                AgentSession.result_response.is_(None)).values(result_response=request.result_response,
                version=row.version + 1, updated_at=now))
            if changed.rowcount != 1:
                self.conflict("STALE_VERSION")
            db.refresh(row)
            result = dict(episode_id=row.episode_id, goal_type=row.accepted_goal_type,
                status=row.status, version=row.version, task_id=row.task_id,
                graph_version=row.graph_version, graph_hash=row.graph_hash)
            db.add(AgentTurn(session_id=row.id, user_id=request.user_id, request_id=request.request_id,
                request_hash=digest, version=row.version, response={"_result": result}, created_at=now))
            from app.repositories.runtime_events import append
            append(db, row, request.request_id, {"command_type": "respond_result",
                "result_response": request.result_response, "status": row.status}, kind="control", now=now)
            db.commit()
            return result

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
                if previous.version != row.version or row.episode_id != request.episode_id:
                    self.conflict("STALE_REPLAY")
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
                    row.paused_from = row.status
                    if row.workflow:
                        row.workflow = {**row.workflow, "paused_status": row.status}
                    row.status = "PAUSED"
                    changed = True
                else:
                    self.conflict("COMMAND_NOT_ALLOWED")
            elif command == "resume_task":
                if row.status != "PAUSED":
                    self.conflict("COMMAND_NOT_ALLOWED")
                row.status = row.paused_from or (row.workflow or {}).get("paused_status", "IN_PROGRESS")
                if row.task_outcome and row.status == "WAITING_RESULT":
                    outcome = deepcopy(row.task_outcome)
                    outcome["prompt"]["expires_at"] = now + 180
                    row.task_outcome = outcome
                row.expires_at = now + (180 if row.status == "WAITING_RESULT" else self.idle_seconds)
                checkpoint = db.get(AgentCheckpoint, row.id)
                if checkpoint:
                    state = deepcopy(checkpoint.state)
                    state.update(pending_attempt=None, pending_transition_id=None, pending_confirmation_transition_id=None)
                    checkpoint.state = state
                if row.workflow:
                    row.workflow = {key: value for key, value in row.workflow.items() if key != "paused_status"}
                changed = True
            elif command == "cancel_task":
                if row.status == "CANCELLED":
                    changed = False
                elif row.status in ACTIVE or row.status == "PAUSED":
                    row.status = "CANCELLED"
                    row.task_outcome = answer_outcome(row.task_outcome or make_outcome({}, now), "stopped", now)
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
                "task_outcome": deepcopy(row.task_outcome),
                "workflow": deepcopy(row.workflow or {}),
            }
            db.add(AgentTurn(session_id=row.id, user_id=request.user_id,
                request_id=request.request_id, request_hash=request_hash,
                version=row.version, response={"_control": result}, created_at=now))
            if changed:
                from app.repositories.runtime_events import append
                append(db, row, request.request_id, {"command_type": command, "status": row.status}, kind="control", now=now)
            db.commit()
            return result

    def prepare_replace(self, request):
        now = int(self.clock())
        with self.factory() as db:
            self.guard_voice_request(db, request, now)
            row = self.owned(db, request.session_id, request.user_id)
            self.expire(db, row, now)
            if row.episode_id != request.episode_id:
                self.conflict("EPISODE_MISMATCH")
            if row.version != request.expected_version or row.status not in ACTIVE + ("PAUSED",):
                self.conflict("COMMAND_NOT_ALLOWED")
            if row.lease_token and row.lease_until and row.lease_until > now:
                self.conflict("TURN_IN_PROGRESS")
            new_episode = str(uuid4())
            row.episode_id = new_episode
            row.accepted_goal_type = request.goal.goal_type
            row.goal = request.goal.model_dump(mode="json")
            row.device_context = request.device_context.model_dump(mode="json")
            row.status = "IN_PROGRESS"
            row.task_id = row.state_id = row.last_guide_command_id = None
            row.graph_hash = row.graph_version = None
            row.result_response = None
            row.recovery = None
            if row.workflow:
                row.workflow = {"version": row.workflow["version"]}
            db.execute(delete(AgentCheckpoint).where(AgentCheckpoint.session_id == row.id))
            db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == row.id))
            db.commit()
            return new_episode

    def outcome_command(self, request, request_hash, *, goal=None, reference=None):
        """One versioned transaction records user answers without altering technical evidence."""
        now = int(self.clock())
        with self.factory() as db:
            row = self.owned(db, request.session_id, request.user_id)
            previous = db.scalar(select(AgentTurn).where(AgentTurn.user_id == request.user_id,
                AgentTurn.request_id == request.request_id))
            if previous:
                if previous.request_hash != request_hash or previous.session_id != row.id:
                    self.conflict("REQUEST_ID_REUSED")
                if previous.version != row.version or not previous.response:
                    self.conflict("STALE_REPLAY")
                return deepcopy(previous.response["_outcome"])
            if not (request.command_type == "respond_result" and request.user_result == "no_response"
                    and row.status == "WAITING_RESULT"):
                self.expire(db, row, now)
            if row.episode_id != request.episode_id:
                self.conflict("EPISODE_MISMATCH")
            if row.version != request.expected_version:
                self.conflict("STALE_VERSION")
            if row.status not in ACTIVE:
                self.conflict("SESSION_CLOSED")
            outcome = deepcopy(row.task_outcome) or make_outcome({}, now)
            status, episode = row.status, row.episode_id
            history = list(row.outcome_history or [])
            message = None
            if request.command_type == "request_result":
                if row.status not in {"IN_PROGRESS", "WAITING_CONFIRMATION"}:
                    self.conflict("COMMAND_NOT_ALLOWED")
                outcome = make_outcome(outcome["technical"], now,
                    row.goal["slots"]["direction"] if row.goal else "increase", previous=outcome)
                status = "WAITING_RESULT"
            elif request.command_type == "respond_result":
                prompt = outcome.get("prompt") or {}
                if row.status != "WAITING_RESULT" or request.result_prompt_id != prompt.get("id"):
                    self.conflict("RESULT_PROMPT_MISMATCH")
                if request.user_result == "no_response" and now < prompt["expires_at"]:
                    self.conflict("RESULT_TIMEOUT_NOT_REACHED")
                outcome = answer_outcome(outcome, request.user_result, now)
                status = {"satisfied": "COMPLETED", "needs_help": "WAITING_ADJUSTMENT",
                          "stopped": "CANCELLED", "no_response": "EXPIRED"}[request.user_result]
                message = {"satisfied": "원하는 결과를 얻었다고 기록했어요.",
                           "needs_help": follow_up(outcome)["message"],
                           "stopped": "작업을 중단했어요.", "no_response": "답변 없이 대기를 종료했어요."}[request.user_result]
            elif request.command_type == "adjust_task":
                if row.status != "WAITING_ADJUSTMENT":
                    self.conflict("COMMAND_NOT_ALLOWED")
                reason = request.adjustment_reason
                if reason in {"other", "unsure"}:
                    message = "현재는 글자 크기만 조절할 수 있어요. 글자가 작은지 큰지 확인하거나 그만할 수 있어요. 굵기·밝기·화면 전체 확대는 아직 지원하지 않아요."
                else:
                    direction = goal.slots["direction"]
                    if outcome["technical"].get("limit_direction") == direction:
                        self.conflict("SETTING_LIMIT_REACHED")
                    history.append({"episode_id": row.episode_id, "goal_type": row.accepted_goal_type,
                                    "outcome": outcome})
                    outcome = None
                    episode, status = str(uuid4()), "IN_PROGRESS"
                    message = "새 화면에서 조절 가능한 범위를 확인할게요."
            values = dict(status=status, version=row.version + 1, episode_id=episode,
                updated_at=now, expires_at=(outcome.get("prompt", {}).get("expires_at", now + self.idle_seconds)
                    if status == "WAITING_RESULT" and outcome else now + self.idle_seconds),
                task_outcome=outcome, outcome_history=history, lease_token=None, lease_until=None,
                last_guide_command_id=None)
            if episode != row.episode_id:
                values.update(goal=goal.model_dump(mode="json"), accepted_goal_type=goal.goal_type,
                    task_id=reference["task_id"], graph_version=reference["version"], graph_hash=reference["sha256"],
                    state_id=None, workflow=None, recovery=None)
                db.execute(delete(AgentCheckpoint).where(AgentCheckpoint.session_id == row.id))
            changed = db.execute(update(AgentSession).where(AgentSession.id == row.id,
                AgentSession.version == row.version, AgentSession.status == row.status).values(**values))
            if changed.rowcount != 1:
                self.conflict("TURN_SUPERSEDED")
            db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == row.id))
            result = {"episode_id": episode, "status": status, "version": values["version"],
                "goal_type": goal.goal_type if goal and episode != request.episode_id else row.accepted_goal_type,
                "task_id": values.get("task_id", row.task_id), "task_outcome": outcome, "message": message,
                "follow_up": follow_up(outcome) if status == "WAITING_ADJUSTMENT" else None}
            db.add(AgentTurn(session_id=row.id, user_id=request.user_id, request_id=request.request_id,
                request_hash=request_hash, version=values["version"], response={"_outcome": result}, created_at=now))
            from app.repositories.runtime_events import append
            append(db, row, request.request_id, {"command_type": request.command_type,
                "user_result": request.user_result, "adjustment_reason": request.adjustment_reason,
                "status": status}, kind="control", now=now)
            db.commit()
            return result

    @staticmethod
    def describe(db, row, now):
        return AgentSessionResponse(session_id=row.id, status=row.status, version=row.version,
            episode_id=row.episode_id,
            task_outcome=row.task_outcome, outcome_history=row.outcome_history or [],
            task_id=row.task_id, state_id=row.state_id, last_guide_command_id=row.last_guide_command_id,
            created_at=row.created_at, updated_at=row.updated_at, expires_at=row.expires_at,
            processing=bool(row.lease_token and row.lease_until and row.lease_until > now),
            requires_fresh_observation=row.status in {"IN_PROGRESS", "WAITING_CONFIRMATION"},
            requires_goal=row.status in {"IN_PROGRESS", "WAITING_CONFIRMATION"} and db.get(AgentCheckpoint, row.id) is None,
            result_response=row.result_response,
            result_prompt=((row.task_outcome or {}).get("prompt") or (row.workflow or {}).get("result_prompt"))
                if row.status == "WAITING_RESULT" else None,
            follow_up=(follow_up(row.task_outcome) if row.task_outcome else (row.workflow or {}).get("follow_up"))
                if row.status == "WAITING_ADJUSTMENT" else None)

    def cleanup(self, retention_seconds=86400):
        if retention_seconds < 0:
            raise ValueError("Retention must not be negative")
        now = int(self.clock())
        with self.factory() as db:
            rows = list(db.scalars(select(AgentSession).where(AgentSession.status.in_(ACTIVE),
                AgentSession.expires_at <= now)))
            for row in rows:
                self.expire(db, row, now)
            expired = len(rows)
            from app.models.agent_session import AgentGoalResolution
            db.execute(delete(AgentGoalResolution).where(AgentGoalResolution.expires_at <= now - retention_seconds))
            removed = db.execute(delete(AgentSession).where(AgentSession.status.not_in(ACTIVE),
                AgentSession.updated_at <= now - retention_seconds)).rowcount
            db.commit()
            return {"expired": expired, "removed": removed}
