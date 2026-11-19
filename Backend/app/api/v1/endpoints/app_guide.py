from fastapi import APIRouter, Depends, BackgroundTasks
from app.schemas.app_guide import AppGuideRequest, AppGuideResponse
from app.schemas.app_task import AppTaskRequest, AppTaskResponse
from app.schemas.app_task_learning import TaskExperienceBatch, TaskExperienceReceipt, TaskMemoryResponse, TaskUserResult, TaskUserResultReceipt


def build_router(service, principal, tasks=None, experience=None, learner=None):
    router = APIRouter(prefix='/agent/app-guide', tags=['app-independent-guidance'])

    @router.post('/step', response_model=AppGuideResponse)
    def step(request: AppGuideRequest, user=Depends(principal)):
        return service.step(request)

    if tasks is not None:
        @router.post('/task',response_model=AppTaskResponse)
        def task(request:AppTaskRequest,user=Depends(principal)):
            return tasks.step(request, user_id=getattr(user,'user_id',None))

    if experience is not None:
        @router.post('/task/result',response_model=TaskUserResultReceipt)
        def result(request:TaskUserResult,user=Depends(principal)):
            return experience.user_result(request,user.user_id)

        @router.post('/task/events',response_model=TaskExperienceReceipt)
        def events(request:TaskExperienceBatch,background:BackgroundTasks,user=Depends(principal)):
            response=experience.ingest(request,user.user_id)
            if learner and response.status=='accepted':
                background.add_task(learner.run_safely,request.session_id,user.user_id)
            return response

        @router.post('/task/memory',response_model=TaskMemoryResponse)
        def memory(request:AppTaskRequest,user=Depends(principal)):
            return experience.recall(request,user.user_id)

    return router
