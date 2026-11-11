from sqlalchemy import BigInteger, Boolean, CheckConstraint, ForeignKey, Index, String, UniqueConstraint, text
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base
from app.core.data_encryption import EncryptedText


class VoiceQueueState(Base):
    __tablename__ = "voice_queue_states"
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    last_sequence: Mapped[int] = mapped_column(BigInteger, default=0)
    version: Mapped[int] = mapped_column(BigInteger, default=0)
    awaiting_replacement: Mapped[bool] = mapped_column(Boolean, default=False)


class VoiceRequest(Base):
    __tablename__ = "voice_requests"
    __table_args__ = (
        UniqueConstraint("user_id", "sequence", name="uq_voice_request_sequence"),
        CheckConstraint("priority IN ('NORMAL', 'VIP')", name="ck_voice_priority"),
        CheckConstraint("status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')", name="ck_voice_status"),
        Index("uq_voice_running_user", "user_id", unique=True,
              sqlite_where=text("status = 'RUNNING'"), postgresql_where=text("status = 'RUNNING'")),
        Index("ix_voice_user_status_sequence", "user_id", "status", "sequence"),
    )
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"))
    raw_goal: Mapped[str | None] = mapped_column(EncryptedText("voice_requests.raw_goal"))
    priority: Mapped[str] = mapped_column(String(10))
    status: Mapped[str] = mapped_column(String(12))
    agent_session_id: Mapped[str | None] = mapped_column(String(200), ForeignKey("agent_sessions.id", ondelete="SET NULL"), unique=True)
    sequence: Mapped[int] = mapped_column(BigInteger)
    payload_hash: Mapped[str] = mapped_column(String(64))
    failure_code: Mapped[str | None] = mapped_column(String(40))
    created_at: Mapped[int] = mapped_column(BigInteger)
    updated_at: Mapped[int] = mapped_column(BigInteger)
    expires_at: Mapped[int] = mapped_column(BigInteger)
