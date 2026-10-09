from fastapi import APIRouter, Depends, Request, Response
from pydantic import BaseModel, ConfigDict, Field, SecretStr, field_validator
from app.schemas.auth import Credentials
from app.models.lifecycle import EmailVerification


class EmailRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(min_length=3, max_length=254)
    normalize_email = field_validator("email")(Credentials.normalize_email.__func__)


class ActionToken(BaseModel):
    model_config = ConfigDict(extra="forbid")
    token: SecretStr = Field(min_length=40, max_length=128)


class ResetPassword(ActionToken):
    new_password: SecretStr = Field(min_length=8, max_length=128)


def build_router(service, auth, principal):
    def throttle(request: Request):
        auth.throttle(request.client.host if request.client else "unknown")
    router = APIRouter(prefix="/auth", tags=["account-actions"], dependencies=[Depends(throttle)])

    @router.post("/password/forgot", status_code=202)
    def forgot(body: EmailRequest):
        service.request("reset_password", email=body.email)
        return {"message": "If the account exists, instructions have been sent."}

    @router.post("/password/reset", status_code=204)
    def reset(body: ResetPassword):
        service.consume(body.token.get_secret_value(), "reset_password", body.new_password.get_secret_value())
        return Response(status_code=204)

    @router.post("/email/request", status_code=202)
    def request_email(user=Depends(principal)):
        service.request("verify_email", user_id=user.user_id)
        return {"message": "Verification instructions have been sent."}

    @router.post("/email/verify", status_code=204)
    def verify(body: ActionToken):
        service.consume(body.token.get_secret_value(), "verify_email")
        return Response(status_code=204)

    @router.get("/email/status")
    def status(user=Depends(principal)):
        with service.factory() as db:
            return {"verified": db.get(EmailVerification, user.user_id) is not None}

    return router
