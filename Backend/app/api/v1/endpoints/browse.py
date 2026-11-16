from fastapi import APIRouter, Depends
from app.schemas.browse import BrowseCommandRequest, BrowseResponse


def build_router(service, principal):
    router = APIRouter(prefix='/agent/browse',tags=['public-browsing'])

    @router.post('/commands',response_model=BrowseResponse)
    def command(request: BrowseCommandRequest,user=Depends(principal)):
        return service.handle(request,user.user_id)

    return router
