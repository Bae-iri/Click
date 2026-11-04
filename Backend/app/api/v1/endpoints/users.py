from __future__ import annotations

from collections.abc import Callable

from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.repositories.users import UserRepository, UserNotFoundError
from app.schemas.users import ConsentResponse, RecordConsentRequest, UserResponse
from app.services.users import UserService


def build_router(session_dependency: Callable, principal: Callable) -> APIRouter:
    router = APIRouter(prefix="/users", tags=["users"])

    def service(session=Depends(session_dependency)) -> UserService:
        return UserService(UserRepository(session))

    def owner(user_id: str, user=Depends(principal)):
        if user_id != user.user_id:
            raise HTTPException(403)
        return user

    @router.get("/me", response_model=UserResponse)
    def me(user=Depends(principal), svc=Depends(service)):
        return svc.get_user(user.user_id)

    @router.get("/{user_id}", response_model=UserResponse, dependencies=[Depends(owner)])
    def get_user(user_id: str, svc=Depends(service)) -> UserResponse:
        try:
            return svc.get_user(user_id)
        except UserNotFoundError as error:
            raise HTTPException(status_code=404, detail="User not found.") from error

    # Old APKs receive an explicit retirement response, without recording data.
    @router.patch("/{user_id}/profile", dependencies=[Depends(owner)], deprecated=True)
    @router.post("/{user_id}/events", dependencies=[Depends(owner)], deprecated=True)
    def retired_personalization(user_id: str):
        raise HTTPException(410, detail="User personalization has been removed.")

    @router.post("/{user_id}/consents", response_model=ConsentResponse, status_code=201, dependencies=[Depends(owner)])
    def record_consent(
        user_id: str, request: RecordConsentRequest, svc=Depends(service)
    ) -> ConsentResponse:
        try:
            return svc.record_consent(user_id, request)
        except UserNotFoundError as error:
            raise HTTPException(status_code=404, detail="User not found.") from error

    @router.get("/{user_id}/consents", response_model=list[ConsentResponse], dependencies=[Depends(owner)])
    def current_consents(user_id: str, svc=Depends(service)):
        results = [svc.repository.latest_consent(user_id, purpose) for purpose in
                   ("conversation_storage", "model_training")]
        return [ConsentResponse.model_validate(row) for row in results if row is not None]

    @router.delete("/{user_id}", status_code=204, dependencies=[Depends(owner)])
    def delete_user(user_id: str, svc=Depends(service)) -> Response:
        try:
            svc.delete_user(user_id)
        except UserNotFoundError as error:
            raise HTTPException(status_code=404, detail="User not found.") from error
        return Response(status_code=204)

    return router
