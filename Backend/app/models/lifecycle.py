from uuid import uuid4
from sqlalchemy import BigInteger, ForeignKey, Integer, String, Boolean
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base
from app.models.user import JSONType


class SafetyConfirmation(Base):
    __tablename__ = "safety_confirmations"
    session_id: Mapped[str] = mapped_column(String(200), ForeignKey("agent_sessions.id", ondelete="CASCADE"), primary_key=True)
    id: Mapped[str] = mapped_column(String(36), unique=True, default=lambda: str(uuid4()))
    version: Mapped[int] = mapped_column(Integer)
    expires_at: Mapped[int] = mapped_column(BigInteger)
    screen_hash: Mapped[str] = mapped_column(String(64))
    command: Mapped[dict] = mapped_column(JSONType)


class TaskFeedback(Base):
    __tablename__ = "task_feedback"
    session_id: Mapped[str] = mapped_column(String(200), ForeignKey("agent_sessions.id", ondelete="CASCADE"), primary_key=True)
    rating: Mapped[int] = mapped_column(Integer)
    reason_code: Mapped[str | None] = mapped_column(String(40))
    created_at: Mapped[int] = mapped_column(BigInteger)


class AccountAction(Base):
    __tablename__ = "account_actions"
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.user_id", ondelete="CASCADE"), index=True)
    purpose: Mapped[str] = mapped_column(String(30))
    expires_at: Mapped[int] = mapped_column(BigInteger)
    credential_hash: Mapped[str] = mapped_column(String(64))


class EmailVerification(Base):
    __tablename__ = "email_verifications"
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("accounts.user_id", ondelete="CASCADE"), primary_key=True)
    verified_at: Mapped[int] = mapped_column(BigInteger)
