from fastapi import APIRouter, BackgroundTasks, Depends
from app.schemas.public_guide import PublicGuideRequest, PublicGuideResponse

def build_router(service,principal,learner=None):
    router=APIRouter(prefix='/agent/public-guide',tags=['public-login-guidance'])
    @router.post('/commands',response_model=PublicGuideResponse)
    def command(request:PublicGuideRequest,background:BackgroundTasks,user=Depends(principal)):
        response = service.handle(request,user.user_id)
        if learner and request.command != 'snapshot' and response.status in {'COMPLETED', 'CANCELLED', 'BLOCKED'}:
            background.add_task(learner.run_safely, request.session_id, user.user_id)
        return response
    return router
