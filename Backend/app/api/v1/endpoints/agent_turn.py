from fastapi import APIRouter, HTTPException, Depends

from app.clients.agent import UnsupportedGoalError
from app.repositories.sessions import SessionNotFoundError
from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse
from app.services.agent_turn import AgentTurnService
from app.schemas.agent_session import AgentSessionResponse, CancelSessionRequest


def build_router(service: AgentTurnService, principal) -> APIRouter:
    router = APIRouter(prefix="/agent", tags=["agent"])

    @router.post("/turn", response_model=AgentTurnResponse)
    def handle_turn(request: AgentTurnRequest, user=Depends(principal)) -> AgentTurnResponse:
        if request.user_id is not None and request.user_id != user.user_id:
            raise HTTPException(403)
        request = request.model_copy(update={"user_id": user.user_id})
        try:
            return service.handle_turn(request)
        except SessionNotFoundError as error:
            raise HTTPException(
                status_code=404,
                detail="Unknown session_id; raw_goal is required on the first turn.",
            ) from error
        except (UnsupportedGoalError, ValueError) as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    @router.get("/sessions/{session_id}", response_model=AgentSessionResponse)
    def get_session(session_id: str, user=Depends(principal)):
        return service.repository.get(session_id, user.user_id)

    @router.post("/sessions/{session_id}/cancel", response_model=AgentSessionResponse)
    def cancel_session(session_id: str, request: CancelSessionRequest, user=Depends(principal)):
        return service.repository.cancel(session_id, user.user_id, request.expected_version)

    return router
