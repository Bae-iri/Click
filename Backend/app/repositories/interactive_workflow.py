"""Fence result prompts and user-requested adjustments in short SQL transactions."""
from copy import deepcopy
from hashlib import sha256
import json
from uuid import uuid4

from sqlalchemy import delete, select
from app.models.agent_session import AgentCheckpoint, AgentTurn
from app.models.lifecycle import SafetyConfirmation


def follow_up():
    return {"message": "글자가 아직 작나요, 너무 큰가요? 다른 요청은 새로 입력해 주세요.",
            "choices": [{"id": "too_small", "label": "아직 작아요"},
                        {"id": "too_large", "label": "너무 커요"}]}


def apply(repository, request, graph_reference=None):
    digest = sha256(json.dumps(request.model_dump(mode="json"), sort_keys=True,
                              separators=(",", ":")).encode()).hexdigest()
    now = int(repository.clock())
    with repository.factory() as db:
        repository.guard_voice_request(db, request, now)
        row = repository.owned(db, request.session_id, request.user_id)
        prior = db.scalar(select(AgentTurn).where(AgentTurn.user_id == request.user_id,
                                                AgentTurn.request_id == request.request_id))
        if prior:
            if prior.session_id != row.id or prior.request_hash != digest:
                repository.conflict("REQUEST_ID_REUSED")
            if prior.version != row.version:
                repository.conflict("STALE_REPLAY")
            if prior.response and "_workflow" in prior.response:
                return deepcopy(prior.response["_workflow"])
            repository.conflict("COMMAND_NOT_ALLOWED")
        repository.expire(db, row, now)
        if row.episode_id != request.episode_id:
            repository.conflict("EPISODE_MISMATCH")
        if row.version != request.expected_version:
            repository.conflict("STALE_VERSION")
        if row.lease_token and row.lease_until and row.lease_until > now:
            repository.conflict("TURN_IN_PROGRESS")
        flow = deepcopy(row.workflow or {})
        if flow.get("version") != "interactive.v1":
            repository.conflict("COMMAND_NOT_ALLOWED")
        if request.command_type == "respond_result":
            prompt = flow.get("result_prompt") or {}
            if row.status != "WAITING_RESULT" or prompt.get("id") != request.result_prompt_id:
                repository.conflict("RESULT_PROMPT_INVALID")
            expired = prompt.get("expires_at", 0) <= now
            if (request.user_result == "no_response") != expired:
                repository.conflict("RESULT_PROMPT_EXPIRED" if expired else "RESULT_PROMPT_NOT_EXPIRED")
            flow.pop("result_prompt", None)
            flow["user_result"] = request.user_result
            row.status = {"satisfied": "COMPLETED", "needs_help": "WAITING_ADJUSTMENT",
                          "stopped": "CANCELLED", "no_response": "EXPIRED"}[request.user_result]
            if request.user_result == "needs_help":
                if row.accepted_goal_type.startswith("settings.font_size."):
                    flow["follow_up"] = follow_up()
                else:
                    row.status = "CANCELLED"
        elif request.adjustment_reason in {"other", "unsure"}:
            if row.status != "WAITING_ADJUSTMENT":
                repository.conflict("COMMAND_NOT_ALLOWED")
            flow["follow_up"] = follow_up()
        else:
            if row.status != "WAITING_ADJUSTMENT" or not row.accepted_goal_type.startswith("settings.font_size."):
                repository.conflict("COMMAND_NOT_ALLOWED")
            direction = "increase" if request.adjustment_reason == "too_small" else "decrease"
            row.goal = {**row.goal, "catalog_version": "2.1.0",
                        "goal_type": "settings.font_size." + direction,
                        "slots": {"setting": "font_size", "direction": direction}}
            row.accepted_goal_type = row.goal["goal_type"]
            row.episode_id = str(uuid4())
            row.state_id = row.last_guide_command_id = None
            row.task_id = graph_reference["task_id"]
            row.graph_hash = graph_reference["sha256"]
            row.graph_version = graph_reference["version"]
            row.status = "IN_PROGRESS"
            row.result_response = None
            flow = {"version": "interactive.v1"}
        row.workflow = flow
        row.version += 1
        row.updated_at = now
        row.expires_at = now + repository.idle_seconds
        row.lease_token = row.lease_until = None
        db.execute(delete(SafetyConfirmation).where(SafetyConfirmation.session_id == row.id))
        if row.status in {"IN_PROGRESS", "CANCELLED", "EXPIRED"}:
            db.execute(delete(AgentCheckpoint).where(AgentCheckpoint.session_id == row.id))
        result = dict(episode_id=row.episode_id, goal_type=row.accepted_goal_type,
                      status=row.status, version=row.version, task_id=row.task_id,
                      graph_version=row.graph_version, graph_hash=row.graph_hash, workflow=flow)
        db.add(AgentTurn(session_id=row.id, user_id=request.user_id, request_id=request.request_id,
                         request_hash=digest, version=row.version, response={"_workflow": result}, created_at=now))
        db.commit()
        return result
