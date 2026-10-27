from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse

from app.core.http import ErrorEnvelope, error_response
from app.schemas.transcription import TranscriptionRequest, TranscriptionResponse
from app.services.transcription import (
    InvalidAudio,
    InvalidTranscriptionResponse,
    NoSpeechDetected,
    TranscriptionService,
    TranscriptionTimedOut,
    TranscriptionUnavailable,
)


def build_router(service: TranscriptionService, principal) -> APIRouter:
    router = APIRouter(prefix="/speech", tags=["speech"])

    @router.post("/transcriptions", response_model=TranscriptionResponse,
                 responses={422: {"model": ErrorEnvelope,
                                  "description": "INVALID_REQUEST for invalid audio; NO_SPEECH for no recognizable speech (error.code)."}})
    def transcribe(body: TranscriptionRequest, request: Request,
                   user=Depends(principal)) -> TranscriptionResponse | JSONResponse:
        del user
        try:
            text = service.transcribe(body)
        except NoSpeechDetected:
            return error_response(422, "NO_SPEECH", "No recognizable speech. Please record again.",
                                  request.scope.get("trace_id"))
        except InvalidAudio as error:
            raise HTTPException(422) from error
        except TranscriptionTimedOut as error:
            raise HTTPException(504) from error
        except TranscriptionUnavailable as error:
            raise HTTPException(503) from error
        except InvalidTranscriptionResponse as error:
            raise HTTPException(502) from error
        return TranscriptionResponse(text=text)

    return router
