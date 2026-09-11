from __future__ import annotations
from datetime import datetime, timezone

from sqlalchemy import delete, select
from sqlalchemy.orm import Session, joinedload

from app.models.personalization import Consent, UIEvent, User, UserUIProfile


class UserNotFoundError(KeyError):
    pass


class PersonalizationRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def create_user(self, age_group: str, occupation_category: str | None) -> User:
        user = User(age_group=age_group, occupation_category=occupation_category)
        user.profile = UserUIProfile()
        self.session.add(user)
        self.session.commit()
        self.session.refresh(user)
        return user

    def get_user(self, user_id: str) -> User:
        user = self.session.scalar(
            select(User).options(joinedload(User.profile)).where(User.id == user_id)
        )
        if user is None:
            raise UserNotFoundError(user_id)
        return user

    def get_profile(self, user_id: str) -> UserUIProfile:
        return self.get_user(user_id).profile

    def save_profile(self, profile: UserUIProfile) -> UserUIProfile:
        self.session.add(profile)
        self.session.commit()
        self.session.refresh(profile)
        return profile

    def record_consent(self, user_id: str, purpose: str, consented: bool, policy_version: str) -> Consent:
        self.get_user(user_id)
        consent = Consent(
            user_id=user_id,
            purpose=purpose,
            consented=consented,
            policy_version=policy_version,
            recorded_at=datetime.now(timezone.utc),
        )
        self.session.add(consent)
        self.session.commit()
        self.session.refresh(consent)
        return consent

    def latest_consent(self, user_id: str, purpose: str) -> Consent | None:
        self.get_user(user_id)
        return self.session.scalar(
            select(Consent)
            .where(Consent.user_id == user_id, Consent.purpose == purpose)
            .order_by(Consent.recorded_at.desc(), Consent.id.desc())
            .limit(1)
        )

    def add_event(
        self,
        user_id: str,
        event_type: str,
        session_id: str | None,
        screen_name: str | None,
        metadata: dict,
    ) -> UIEvent:
        self.get_user(user_id)
        event = UIEvent(
            user_id=user_id,
            event_type=event_type,
            session_id=session_id,
            screen_name=screen_name,
            event_metadata=metadata,
        )
        self.session.add(event)
        self.session.flush()
        return event

    def delete_user(self, user_id: str) -> None:
        if self.session.scalar(select(User.id).where(User.id == user_id)) is None:
            raise UserNotFoundError(user_id)
        self.session.execute(delete(User).where(User.id == user_id))
        self.session.commit()
