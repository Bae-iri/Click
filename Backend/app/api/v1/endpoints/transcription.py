from fastapi import APIRouter, Depends, HTTPException

from app.schemas.transcription import TranscriptionRequest, TranscriptionResponse
from app.services.transcription import (
    InvalidAudio,
    InvalidTranscriptionResponse,
    TranscriptionService,
    TranscriptionTimedOut,
    TranscriptionUnavailable,
)


def build_router(service: TranscriptionService, principal) -> APIRouter:
    router = APIRouter(prefix="/speech", tags=["speech"])

    @router.post("/transcriptions", response_model=TranscriptionResponse)
    def transcribe(body: TranscriptionRequest, user=Depends(principal)) -> TranscriptionResponse:
        del user
        try:
            text = service.transcribe(body)
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
