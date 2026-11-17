from fastapi import APIRouter, BackgroundTasks, Depends
from app.schemas.browse import BrowseCommandRequest, BrowseResponse


def build_router(service, principal, learner=None):
    router = APIRouter(prefix='/agent/browse',tags=['public-browsing'])

    @router.post('/commands',response_model=BrowseResponse)
    def command(request: BrowseCommandRequest, background: BackgroundTasks, user=Depends(principal)):
        response = service.handle(request,user.user_id)
        if learner and response.status == 'COMPLETED' and request.command != 'snapshot':
            background.add_task(learner.run_safely, request.session_id, user.user_id)
        return response

    return router
