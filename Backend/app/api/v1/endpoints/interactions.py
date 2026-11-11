import os
import secrets
from uuid import UUID
from fastapi import APIRouter, Depends, Header, HTTPException, Query
from app.schemas.interaction import ClassifyInteraction, FeedbackDetail, ProductFeedbackResponse, InteractionResponse, FeedbackPage, FeedbackSentiment


def build_router(service, principal):
    router = APIRouter(tags=['interactions'])

    @router.post('/interactions/classify', response_model=InteractionResponse)
    def classify(body: ClassifyInteraction, user=Depends(principal)):
        return service.classify(user.user_id, body)

    @router.post('/feedback/{identifier}/detail', response_model=ProductFeedbackResponse)
    def detail(identifier: UUID, body: FeedbackDetail, user=Depends(principal)):
        return service.submit(user.user_id, identifier, body)

    @router.get('/feedback/{identifier}', response_model=ProductFeedbackResponse)
    def get(identifier: UUID, user=Depends(principal)):
        return service.get(user.user_id, identifier)

    def operator(authorization: str | None = Header(None)):
        token = os.getenv('OPS_TOKEN', '')
        if len(token) < 32:
            raise HTTPException(404)
        if not secrets.compare_digest((authorization or '').encode(), ('Bearer ' + token).encode()):
            raise HTTPException(401)

    @router.get('/ops/feedback', response_model=FeedbackPage, dependencies=[Depends(operator)])
    def review(limit: int = Query(20, ge=1, le=100), sentiment: FeedbackSentiment | None = None,
               before: UUID | None = None):
        return service.review(limit, sentiment, before)

    return router
