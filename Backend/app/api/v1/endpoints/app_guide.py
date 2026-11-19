from fastapi import APIRouter, Depends
from app.schemas.app_guide import AppGuideRequest, AppGuideResponse


def build_router(service, principal):
    router = APIRouter(prefix='/agent/app-guide', tags=['app-independent-guidance'])

    @router.post('/step', response_model=AppGuideResponse)
    def step(request: AppGuideRequest, user=Depends(principal)):
        return service.step(request)

    return router
