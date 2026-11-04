from __future__ import annotations
from datetime import datetime, timezone

from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session

from app.models.user import Consent, User


class UserNotFoundError(KeyError):
    pass


class UserRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get_user(self, user_id: str) -> User:
        user = self.session.scalar(
            select(User).where(User.id == user_id)
        )
        if user is None:
            raise UserNotFoundError(user_id)
        return user

    def lock_user(self, user_id):
        changed = self.session.execute(update(User).where(User.id == user_id).values(id=user_id))
        if changed.rowcount != 1:
            raise UserNotFoundError(user_id)

    def record_consent(self, user_id: str, purpose: str, consented: bool, policy_version: str) -> Consent:
        self.lock_user(user_id)
        if purpose == "model_training" and not consented:
            from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
            self.session.execute(delete(RuntimeEventConsumption).where(RuntimeEventConsumption.event_id.in_(
                select(RuntimeEvent.id).where(RuntimeEvent.user_id == user_id))))
            # A cross-user route run may be anchored to another user's event.
            # Invalidate shared runs rather than retain withdrawn contributions.
            self.session.execute(delete(RuntimeEventConsumption).where(
                RuntimeEventConsumption.consumer == "ai2-routes.v1"))
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

    def delete_user(self, user_id: str) -> None:
        self.lock_user(user_id)
        from app.models.runtime_event import RuntimeEventConsumption
        self.session.execute(delete(RuntimeEventConsumption).where(
            RuntimeEventConsumption.consumer == "ai2-routes.v1"))
        self.session.execute(delete(User).where(User.id == user_id))
        self.session.commit()
