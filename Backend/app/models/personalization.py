from __future__ import annotations

from datetime import datetime
from uuid import uuid4

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.types import JSON

from app.database import Base


JSONType = JSON().with_variant(JSONB(), "postgresql")


class User(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    age_group: Mapped[str | None] = mapped_column(String(20))
    occupation_category: Mapped[str | None] = mapped_column(String(50))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    profile: Mapped[UserUIProfile] = relationship(
        back_populates="user", cascade="all, delete-orphan", uselist=False
    )
    consents: Mapped[list[Consent]] = relationship(back_populates="user", cascade="all, delete-orphan")
    events: Mapped[list[UIEvent]] = relationship(back_populates="user", cascade="all, delete-orphan")


class UserUIProfile(Base):
    __tablename__ = "user_ui_profiles"

    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    comprehension_level: Mapped[str] = mapped_column(String(20), default="unknown")
    explanation_depth: Mapped[str] = mapped_column(String(20), default="normal")
    prefers_step_by_step: Mapped[bool] = mapped_column(Boolean, default=True)
    confidence_score: Mapped[float] = mapped_column(Float, default=0.0)
    evidence_count: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    user: Mapped[User] = relationship(back_populates="profile")


class Consent(Base):
    __tablename__ = "consents"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    purpose: Mapped[str] = mapped_column(String(50))
    consented: Mapped[bool] = mapped_column(Boolean)
    policy_version: Mapped[str] = mapped_column(String(30))
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    user: Mapped[User] = relationship(back_populates="consents")


class UIEvent(Base):
    __tablename__ = "ui_events"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    session_id: Mapped[str | None] = mapped_column(String(200), index=True)
    event_type: Mapped[str] = mapped_column(String(50), index=True)
    screen_name: Mapped[str | None] = mapped_column(String(200))
    event_metadata: Mapped[dict] = mapped_column("metadata", JSONType, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    user: Mapped[User] = relationship(back_populates="events")
