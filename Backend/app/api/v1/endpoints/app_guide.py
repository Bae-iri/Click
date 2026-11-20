from contextlib import contextmanager
from time import monotonic
from fastapi import APIRouter, Depends, BackgroundTasks, HTTPException, Request
from ai_agent.runtime.budget import deadline
from app.schemas.app_guide import AppGuideRequest, AppGuideResponse
from app.schemas.app_task import AppTaskRequest, AppTaskResponse
from app.schemas.app_task_learning import TaskExperienceBatch, TaskExperienceReceipt, TaskMemoryResponse, TaskUserResult, TaskUserResultReceipt


@contextmanager
def task_request_budget(http_request):
    """Older clients get a cap; newer clients can only shorten the remaining work."""
    try:
        milliseconds=int(http_request.headers.get('X-Click-App-Task-Budget-Ms','10000'))
        if not 100<=milliseconds<=10000:raise ValueError()
    except (TypeError,ValueError) as error:
        raise HTTPException(400,detail='INVALID_APP_TASK_BUDGET') from error
    end=monotonic()+milliseconds/1000;outer=deadline.get()
    token=deadline.set(end if outer is None else min(end,outer))
    try:yield
    finally:deadline.reset(token)


def build_router(service, principal, tasks=None, experience=None, learner=None):
    router = APIRouter(prefix='/agent/app-guide', tags=['app-independent-guidance'])

    @router.post('/step', response_model=AppGuideResponse)
    def step(request: AppGuideRequest, user=Depends(principal)):
        return service.step(request)

    if tasks is not None:
        @router.post('/task',response_model=AppTaskResponse)
        def task(request:AppTaskRequest,http_request:Request,user=Depends(principal)):
            with task_request_budget(http_request):
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
