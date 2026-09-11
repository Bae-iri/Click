from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.schemas.auth import Credentials, RegisterRequest, RefreshRequest, TokenResponse, ChangePasswordRequest


def build_auth(service):
    bearer = HTTPBearer(auto_error=False)

    def principal(credentials: HTTPAuthorizationCredentials = Depends(bearer)):
        if not credentials or credentials.scheme.lower() != "bearer":
            raise HTTPException(401, headers={"WWW-Authenticate": "Bearer"})
        return service.authenticate(credentials.credentials)

    def throttle(request: Request):
        service.throttle(request.client.host if request.client else "unknown")

    router = APIRouter(prefix="/auth", tags=["auth"])

    @router.post("/register", response_model=TokenResponse, status_code=201, dependencies=[Depends(throttle)])
    def register(body: RegisterRequest, response: Response):
        response.headers["Cache-Control"] = "no-store"
        return service.register(body)

    @router.post("/login", response_model=TokenResponse, dependencies=[Depends(throttle)])
    def login(body: Credentials, response: Response):
        response.headers["Cache-Control"] = "no-store"
        return service.login(body)

    @router.post("/refresh", response_model=TokenResponse, dependencies=[Depends(throttle)])
    def refresh(body: RefreshRequest, response: Response):
        response.headers["Cache-Control"] = "no-store"
        return service.refresh(body.refresh_token.get_secret_value())

    @router.post("/logout", status_code=204)
    def logout(user=Depends(principal)):
        service.logout(user)
        return Response(status_code=204)

    @router.post("/password", status_code=204, dependencies=[Depends(throttle)])
    def change_password(body: ChangePasswordRequest, user=Depends(principal)):
        service.change_password(user, body)
        return Response(status_code=204)

    return router, principal
