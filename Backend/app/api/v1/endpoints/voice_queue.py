from uuid import UUID
from fastapi import APIRouter, Depends
from app.schemas.voice_queue import EnqueueVoiceRequest, InterruptVoiceRequest, FailVoiceRequest, CancelVoiceRequest, ReviseVoiceRequest, VoiceQueueResponse


def build_router(service, principal):
    router = APIRouter(prefix="/voice-requests", tags=["voice-queue"])

    @router.post("/enqueue", response_model=VoiceQueueResponse)
    def enqueue(body: EnqueueVoiceRequest, user=Depends(principal)):
        return service.enqueue(user.user_id, body)

    @router.post("/next/claim", response_model=VoiceQueueResponse)
    def claim(user=Depends(principal)):
        return service.claim(user.user_id)

    @router.post("/interrupt-and-clear", response_model=VoiceQueueResponse)
    def interrupt(body: InterruptVoiceRequest, user=Depends(principal)):
        return service.enqueue(user.user_id, body, interrupt=True)

    @router.get("", response_model=VoiceQueueResponse)
    def queue(user=Depends(principal)):
        return service.get(user.user_id)

    @router.get("/{request_id}", response_model=VoiceQueueResponse)
    def request(request_id: UUID, user=Depends(principal)):
        return service.get(user.user_id, request_id)

    @router.post("/{request_id}/complete", response_model=VoiceQueueResponse)
    def complete(request_id: UUID, user=Depends(principal)):
        return service.finish(user.user_id, request_id)

    @router.post("/{request_id}/revise", response_model=VoiceQueueResponse)
    def revise(request_id: UUID, body: ReviseVoiceRequest, user=Depends(principal)):
        return service.revise(user.user_id, request_id, body)

    @router.post("/{request_id}/cancel", response_model=VoiceQueueResponse)
    def cancel(request_id: UUID, body: CancelVoiceRequest, user=Depends(principal)):
        return service.finish(user.user_id, request_id, failure=body.reason)

    @router.post("/{request_id}/fail", response_model=VoiceQueueResponse)
    def fail(request_id: UUID, body: FailVoiceRequest, user=Depends(principal)):
        return service.finish(user.user_id, request_id, failure=body.failure_code)

    return router
