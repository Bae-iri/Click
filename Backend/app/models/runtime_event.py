from uuid import uuid4
from sqlalchemy import BigInteger, ForeignKey, Integer, JSON, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base


class RuntimeEvent(Base):
    __tablename__ = "runtime_events"
    __table_args__ = (UniqueConstraint("session_id", "sequence", name="uq_runtime_event_sequence"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    session_id: Mapped[str] = mapped_column(String(200), ForeignKey("agent_sessions.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"))
    episode_id: Mapped[str | None] = mapped_column(String(200))
    sequence: Mapped[int] = mapped_column(Integer)
    request_id: Mapped[str] = mapped_column(String(200))
    graph_hash: Mapped[str | None] = mapped_column(String(64))
    kind: Mapped[str] = mapped_column(String(30))
    payload: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[int] = mapped_column(BigInteger)
    expires_at: Mapped[int] = mapped_column(BigInteger, index=True)


class RuntimeEventConsumption(Base):
    __tablename__ = "runtime_event_consumptions"
    __table_args__ = (UniqueConstraint("consumer", "event_id", name="uq_runtime_event_consumer"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    event_id: Mapped[str] = mapped_column(String(36), ForeignKey("runtime_events.id", ondelete="CASCADE"), index=True)
    consumer: Mapped[str] = mapped_column(String(100))
    result: Mapped[dict] = mapped_column(JSON)
    consumed_at: Mapped[int] = mapped_column(BigInteger)
