from fastapi import APIRouter, HTTPException, Depends

from app.clients.agent import UnsupportedGoalError
from app.repositories.sessions import SessionNotFoundError
from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse
from app.services.agent_turn import AgentTurnService, RequestIdConflictError


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
        except RequestIdConflictError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        except (UnsupportedGoalError, ValueError) as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    return router
