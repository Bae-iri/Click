from fastapi import APIRouter, Depends
from app.schemas.public_guide import PublicGuideRequest, PublicGuideResponse

def build_router(service,principal):
    router=APIRouter(prefix='/agent/public-guide',tags=['public-login-guidance'])
    @router.post('/commands',response_model=PublicGuideResponse)
    def command(request:PublicGuideRequest,user=Depends(principal)):
        return service.handle(request,user.user_id)
    return router
