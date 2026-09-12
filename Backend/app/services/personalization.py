from __future__ import annotations

from app.repositories.personalization import PersonalizationRepository
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


EVENT_WEIGHTS = {
    "guide_step_completed": 1.0,
    "task_completed": 2.0,
    "guide_step_failed": -1.5,
    "help_opened": -1.0,
    "guide_repeated": -1.5,
    "user_confused": -2.0,
    "explanation_changed": 0.0,
}


class ConsentRequiredError(PermissionError):
    pass


class PersonalizationService:
    def __init__(self, repository: PersonalizationRepository) -> None:
        self.repository = repository

    def create_user(self, request: CreateUserRequest) -> UserResponse:
        user = self.repository.create_user(request.age_group, request.occupation_category)
        return self._user_response(user)

    def get_user(self, user_id: str) -> UserResponse:
        return self._user_response(self.repository.get_user(user_id))

    def update_profile(self, user_id: str, request: UpdateProfileRequest) -> UIProfileResponse:
        self.repository.lock_user(user_id)
        profile = self.repository.get_profile(user_id)
        for field, value in request.model_dump(exclude_none=True).items():
            setattr(profile, field, value)
        return UIProfileResponse.model_validate(self.repository.save_profile(profile))

    def record_consent(self, user_id: str, request: RecordConsentRequest) -> ConsentResponse:
        consent = self.repository.record_consent(
            user_id, request.purpose, request.consented, request.policy_version
        )
        return ConsentResponse.model_validate(consent)

    def record_event(self, user_id: str, request: CreateUIEventRequest) -> UIEventResponse:
        self.repository.lock_user(user_id)
        if request.session_id:
            from app.models.agent_session import AgentSession
            from fastapi import HTTPException
            row = self.repository.session.get(AgentSession, request.session_id)
            if row is None or row.user_id != user_id:
                raise HTTPException(403)
        consent = self.repository.latest_consent(user_id, "personalization")
        if consent is None or not consent.consented:
            raise ConsentRequiredError("Personalization consent is required before recording UI events.")

        event = self.repository.add_event(
            user_id,
            request.event_type,
            request.session_id,
            request.screen_name,
            request.metadata,
        )
        profile = self.repository.get_profile(user_id)
        profile.confidence_score = max(
            -10.0,
            min(10.0, profile.confidence_score + EVENT_WEIGHTS[request.event_type]),
        )
        profile.evidence_count += 1
        if request.event_type == "explanation_changed":
            requested_depth = request.metadata.get("explanation_depth")
            if requested_depth in {"concise", "normal", "detailed"}:
                profile.explanation_depth = requested_depth
        if profile.evidence_count >= 3:
            if profile.confidence_score <= -3:
                profile.comprehension_level = "beginner"
            elif profile.confidence_score >= 4:
                profile.comprehension_level = "advanced"
            else:
                profile.comprehension_level = "intermediate"
        profile = self.repository.save_profile(profile)
        return UIEventResponse(
            id=event.id,
            user_id=user_id,
            event_type=event.event_type,
            profile=UIProfileResponse.model_validate(profile),
        )

    def delete_user(self, user_id: str) -> None:
        self.repository.delete_user(user_id)

    @staticmethod
    def _user_response(user) -> UserResponse:
        return UserResponse(
            id=user.id,
            age_group=user.age_group,
            occupation_category=user.occupation_category,
            created_at=user.created_at,
            profile=UIProfileResponse.model_validate(user.profile),
        )
