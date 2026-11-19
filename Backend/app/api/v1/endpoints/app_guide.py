from fastapi import APIRouter, Depends
from app.schemas.app_guide import AppGuideRequest, AppGuideResponse
from app.schemas.app_task import AppTaskRequest, AppTaskResponse


def build_router(service, principal, tasks=None):
    router = APIRouter(prefix='/agent/app-guide', tags=['app-independent-guidance'])

    @router.post('/step', response_model=AppGuideResponse)
    def step(request: AppGuideRequest, user=Depends(principal)):
        return service.step(request)

    if tasks is not None:
        @router.post('/task',response_model=AppTaskResponse)
        def task(request:AppTaskRequest,user=Depends(principal)):
            return tasks.step(request)

    return router
