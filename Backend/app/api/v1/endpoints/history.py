import time
from typing import Literal
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select, delete, update
from sqlalchemy.exc import IntegrityError
from app.models.agent_session import AgentSession
from app.models.lifecycle import TaskFeedback
from app.repositories.agent_sessions import ACTIVE
from app.schemas.agent_session import AgentSessionResponse


class HistoryPage(BaseModel):
    items: list[AgentSessionResponse]
    next_cursor: str | None


class FeedbackResponse(BaseModel):
    session_id: str
    rating: int
    reason_code: str | None
    created_at: int


class FeedbackRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    rating: int = Field(ge=1, le=5, strict=True)
    reason_code: Literal["helpful", "wrong_target", "unclear", "too_slow", "other"] | None = None


def build_router(service, principal):
    router = APIRouter(prefix="/agent", tags=["history"])

    @router.get("/sessions", response_model=HistoryPage)
    def history(limit: int = Query(20, ge=1, le=100), after: str | None = Query(None, max_length=200), user=Depends(principal)):
        now = int(service.repository.clock())
        with service.session_factory() as db:
            query = select(AgentSession).where(AgentSession.user_id == user.user_id)
            if after:
                query = query.where(AgentSession.id > after)
            rows = db.scalars(query.order_by(AgentSession.id).limit(limit + 1)).all()
            items = []
            for row in rows[:limit]:
                service.repository.expire(db, row, now)
                items.append(service.repository.describe(db, row, now))
            return {"items": items, "next_cursor": rows[limit-1].id if len(rows) > limit else None}

    @router.put("/sessions/{session_id}/feedback", response_model=FeedbackResponse)
    def feedback(session_id: str, body: FeedbackRequest, user=Depends(principal)):
        with service.session_factory() as db:
            row = service.repository.owned(db, session_id, user.user_id)
            service.repository.expire(db, row, int(service.repository.clock()))
            if row.status in ACTIVE:
                raise HTTPException(409)
            existing = db.get(TaskFeedback, session_id)
            if existing is None:
                existing = TaskFeedback(session_id=session_id)
                db.add(existing)
            existing.rating, existing.reason_code = body.rating, body.reason_code
            existing.created_at = int(time.time())
            try:
                db.commit()
            except IntegrityError as error:
                db.rollback()
                raise HTTPException(409) from error
            return {"session_id": session_id, **body.model_dump(), "created_at": existing.created_at}

    @router.get("/sessions/{session_id}/feedback", response_model=FeedbackResponse)
    def get_feedback(session_id: str, user=Depends(principal)):
        with service.session_factory() as db:
            service.repository.owned(db, session_id, user.user_id)
            row = db.get(TaskFeedback, session_id)
            if row is None:
                raise HTTPException(404)
            return {"session_id": session_id, "rating": row.rating, "reason_code": row.reason_code, "created_at": row.created_at}

    @router.delete("/sessions/{session_id}", status_code=204)
    def remove(session_id: str, user=Depends(principal)):
        with service.session_factory() as db:
            row = service.repository.owned(db, session_id, user.user_id)
            service.repository.expire(db, row, int(service.repository.clock()))
            if row.status in ACTIVE:
                raise HTTPException(409)
            db.execute(delete(AgentSession).where(AgentSession.id == session_id, AgentSession.user_id == user.user_id,
                                                   AgentSession.status.not_in(ACTIVE)))
            db.commit()
        return Response(status_code=204)

    return router
