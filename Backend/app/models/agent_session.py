from uuid import uuid4

from sqlalchemy import BigInteger, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.personalization import JSONType


class AgentSession(Base):
    __tablename__ = "agent_sessions"
    id: Mapped[str] = mapped_column(String(200), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    status: Mapped[str] = mapped_column(String(30), default="IN_PROGRESS")
    version: Mapped[int] = mapped_column(Integer, default=0)
    task_id: Mapped[str | None] = mapped_column(String(100))
    state_id: Mapped[str | None] = mapped_column(String(100))
    last_guide_command_id: Mapped[str | None] = mapped_column(String(500))
    episode_id: Mapped[str | None] = mapped_column(String(200))
    accepted_goal_type: Mapped[str | None] = mapped_column(String(200))
    goal: Mapped[dict | None] = mapped_column(JSONType, nullable=True)
    device_context: Mapped[dict | None] = mapped_column(JSONType, nullable=True)
    graph_version: Mapped[str | None] = mapped_column(String(64))
    graph_hash: Mapped[str | None] = mapped_column(String(64))
    created_at: Mapped[int] = mapped_column(BigInteger)
    updated_at: Mapped[int] = mapped_column(BigInteger)
    expires_at: Mapped[int] = mapped_column(BigInteger, index=True)
    lease_token: Mapped[str | None] = mapped_column(String(36))
    lease_until: Mapped[int | None] = mapped_column(BigInteger)


class AgentCheckpoint(Base):
    __tablename__ = "agent_checkpoints"
    session_id: Mapped[str] = mapped_column(String(200), ForeignKey("agent_sessions.id", ondelete="CASCADE"), primary_key=True)
    schema_version: Mapped[int] = mapped_column(Integer)
    graph_version: Mapped[str] = mapped_column(String(64))
    state: Mapped[dict] = mapped_column(JSONType)


class AgentTurn(Base):
    __tablename__ = "agent_turns"
    __table_args__ = (UniqueConstraint("user_id", "request_id", name="uq_agent_turn_user_request"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    session_id: Mapped[str] = mapped_column(String(200), ForeignKey("agent_sessions.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"))
    request_id: Mapped[str] = mapped_column(String(200))
    request_hash: Mapped[str] = mapped_column(String(64))
    version: Mapped[int] = mapped_column(Integer)
    response: Mapped[dict | None] = mapped_column(JSONType, nullable=True)
    created_at: Mapped[int] = mapped_column(BigInteger)
