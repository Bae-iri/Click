from __future__ import annotations

from collections.abc import Callable

from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.repositories.personalization import PersonalizationRepository, UserNotFoundError
from app.schemas.personalization import (
    ConsentResponse,
    CreateUIEventRequest,
    CreateUserRequest,
    RecordConsentRequest,
    UIEventResponse,
    UIProfileResponse,
    UpdateProfileRequest,
    UserResponse,
)
from app.services.personalization import ConsentRequiredError, PersonalizationService


def build_router(session_dependency: Callable, principal: Callable) -> APIRouter:
    router = APIRouter(prefix="/users", tags=["users"])

    def service(session=Depends(session_dependency)) -> PersonalizationService:
        return PersonalizationService(PersonalizationRepository(session))

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

    @router.patch("/{user_id}/profile", response_model=UIProfileResponse, dependencies=[Depends(owner)])
    def update_profile(
        user_id: str, request: UpdateProfileRequest, svc=Depends(service)
    ) -> UIProfileResponse:
        try:
            return svc.update_profile(user_id, request)
        except UserNotFoundError as error:
            raise HTTPException(status_code=404, detail="User not found.") from error

    @router.post("/{user_id}/consents", response_model=ConsentResponse, status_code=201, dependencies=[Depends(owner)])
    def record_consent(
        user_id: str, request: RecordConsentRequest, svc=Depends(service)
    ) -> ConsentResponse:
        try:
            return svc.record_consent(user_id, request)
        except UserNotFoundError as error:
            raise HTTPException(status_code=404, detail="User not found.") from error

    @router.post("/{user_id}/events", response_model=UIEventResponse, status_code=201, dependencies=[Depends(owner)])
    def record_event(
        user_id: str, request: CreateUIEventRequest, svc=Depends(service)
    ) -> UIEventResponse:
        try:
            return svc.record_event(user_id, request)
        except UserNotFoundError as error:
            raise HTTPException(status_code=404, detail="User not found.") from error
        except ConsentRequiredError as error:
            raise HTTPException(status_code=403, detail=str(error)) from error

    @router.delete("/{user_id}", status_code=204, dependencies=[Depends(owner)])
    def delete_user(user_id: str, svc=Depends(service)) -> Response:
        try:
            svc.delete_user(user_id)
        except UserNotFoundError as error:
            raise HTTPException(status_code=404, detail="User not found.") from error
        return Response(status_code=204)

    return router
