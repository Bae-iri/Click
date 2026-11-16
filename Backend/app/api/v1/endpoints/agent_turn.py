from fastapi import APIRouter, HTTPException, Depends, Request
from contextlib import contextmanager
from time import monotonic
import os
from ai_agent.runtime.execution_contract import policy
from ai_agent.runtime.budget import deadline

@contextmanager
def request_budget(http_request):
    value = http_request.headers.get("X-Click-Turn-Budget-Ms", "20000")
    try:
        milliseconds = int(value)
        if not 100 <= milliseconds <= 20000: raise ValueError()
    except ValueError as error:
        raise HTTPException(400, detail="INVALID_TURN_BUDGET") from error
    token = deadline.set(monotonic() + milliseconds / 1000)
    try: yield
    finally: deadline.reset(token)

def require_execution_context(http_request,request):
    negotiated=http_request.headers.get("X-Click-Execution-State")
    required=negotiated is not None or os.getenv("CLICK_REQUIRE_EXECUTION_CONTEXT","0")=="1"
    if required and (negotiated not in {None,policy()['version']} or request.execution_context is None):
        raise HTTPException(409,detail="EXECUTION_CONTRACT_REQUIRED",headers={"X-Conflict-Code":"CONTRACT_MISMATCH"})

from app.core.validation import log_turn
from app.services.execution_state import attach

from app.clients.agent import UnsupportedGoalError
from app.repositories.sessions import SessionNotFoundError
from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse, AgentCommandRequest, AgentCommandResponse, AgentGoalResolutionResponse
from app.services.agent_turn import AgentTurnService
from app.schemas.agent_session import AgentSessionResponse, CancelSessionRequest


def build_router(service: AgentTurnService, principal) -> APIRouter:
    router = APIRouter(prefix="/agent", tags=["agent"])

    @router.post("/turn", response_model=AgentTurnResponse)
    def handle_turn(request: AgentTurnRequest, http_request: Request, user=Depends(principal)) -> AgentTurnResponse:
        if request.user_id is not None and request.user_id != user.user_id:
            raise HTTPException(403)
        request = request.model_copy(update={"user_id": user.user_id})
        try:
            require_execution_context(http_request,request)
            with request_budget(http_request):
                return attach(service.handle_turn(request),request)
        except SessionNotFoundError as error:
            raise HTTPException(
                status_code=404,
                detail="Unknown session_id; raw_goal is required on the first turn.",
            ) from error
        except (UnsupportedGoalError, ValueError) as error:
            raise HTTPException(status_code=422, detail=str(error)) from error

    @router.post("/commands", response_model=AgentCommandResponse | AgentGoalResolutionResponse)
    def handle_command(request: AgentCommandRequest, http_request: Request,
                       user=Depends(principal)) -> AgentCommandResponse | AgentGoalResolutionResponse:
        request = request.model_copy(update={"user_id": user.user_id})
        try:
            require_execution_context(http_request,request)
            with request_budget(http_request):
                response = attach(service.handle_command(request),request)
            log_turn(http_request, request, response)
            return response
        except SessionNotFoundError as error:
            log_turn(http_request, request, failed=True)
            raise HTTPException(status_code=404, detail="SESSION_NOT_FOUND") from error
        except (UnsupportedGoalError, ValueError) as error:
            log_turn(http_request, request, failed=True)
            raise HTTPException(status_code=422, detail=str(error)) from error
        except Exception:
            log_turn(http_request, request, failed=True)
            raise

    @router.get("/sessions/{session_id}", response_model=AgentSessionResponse)
    def get_session(session_id: str, user=Depends(principal)):
        return service.repository.get(session_id, user.user_id)

    @router.post("/sessions/{session_id}/cancel", response_model=AgentSessionResponse)
    def cancel_session(session_id: str, request: CancelSessionRequest, user=Depends(principal)):
        return service.repository.cancel(session_id, user.user_id, request.expected_version)

    return router
