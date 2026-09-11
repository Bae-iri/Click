from fastapi import APIRouter, HTTPException, status, Depends

from app.clients.agent import UnsupportedGoalError
from app.repositories.sessions import SessionNotFoundError
from app.schemas.guidance import (
    CreateSessionRequest,
    CreateSessionResponse,
    ObserveRequest,
    ObserveResponse,
    SessionResponse,
)
from app.services.guidance import GuidanceService


def build_router(service: GuidanceService, principal) -> APIRouter:
    router = APIRouter(prefix="/sessions", tags=["guidance"])
    owners = {}

    def owner(session_id: str, user=Depends(principal)):
        if owners.get(session_id) != user.user_id:
            raise HTTPException(404)

    @router.post(
        "",
        response_model=CreateSessionResponse,
        status_code=status.HTTP_201_CREATED,
        deprecated=True,
        description="Legacy API. New frontend integrations must use POST /api/v1/agent/turn.",
    )
    def create_session(request: CreateSessionRequest, user=Depends(principal)) -> CreateSessionResponse:
        try:
            result = service.create_session(request)
            owners[result.session_id] = user.user_id
            return result
        except UnsupportedGoalError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    @router.post(
        "/{session_id}/observations",
        response_model=ObserveResponse,
        dependencies=[Depends(owner)],
        deprecated=True,
        description="Legacy API. New frontend integrations must use POST /api/v1/agent/turn.",
    )
    def observe(session_id: str, request: ObserveRequest) -> ObserveResponse:
        try:
            return service.observe(session_id, request)
        except SessionNotFoundError as error:
            raise HTTPException(status_code=404, detail="세션을 찾을 수 없습니다.") from error

    @router.get("/{session_id}", response_model=SessionResponse, deprecated=True, dependencies=[Depends(owner)])
    def get_session(session_id: str) -> SessionResponse:
        try:
            return service.get_session(session_id)
        except SessionNotFoundError as error:
            raise HTTPException(status_code=404, detail="세션을 찾을 수 없습니다.") from error

    return router
