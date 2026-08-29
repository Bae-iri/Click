from fastapi import APIRouter, HTTPException, status

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


def build_router(service: GuidanceService) -> APIRouter:
    router = APIRouter(prefix="/sessions", tags=["guidance"])

    @router.post("", response_model=CreateSessionResponse, status_code=status.HTTP_201_CREATED)
    def create_session(request: CreateSessionRequest) -> CreateSessionResponse:
        try:
            return service.create_session(request)
        except UnsupportedGoalError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    @router.post("/{session_id}/observations", response_model=ObserveResponse)
    def observe(session_id: str, request: ObserveRequest) -> ObserveResponse:
        try:
            return service.observe(session_id, request)
        except SessionNotFoundError as error:
            raise HTTPException(status_code=404, detail="세션을 찾을 수 없습니다.") from error

    @router.get("/{session_id}", response_model=SessionResponse)
    def get_session(session_id: str) -> SessionResponse:
        try:
            return service.get_session(session_id)
        except SessionNotFoundError as error:
            raise HTTPException(status_code=404, detail="세션을 찾을 수 없습니다.") from error

    return router

