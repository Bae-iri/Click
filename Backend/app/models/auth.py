from uuid import uuid4

from sqlalchemy import ForeignKey, String, BigInteger
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class Account(Base):
    __tablename__ = "accounts"
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    email: Mapped[str] = mapped_column(String(254), unique=True)
    password_hash: Mapped[str] = mapped_column(String(256))


class AuthSession(Base):
    __tablename__ = "auth_sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), index=True)
    access_hash: Mapped[str] = mapped_column(String(64), unique=True)
    refresh_hash: Mapped[str] = mapped_column(String(64), unique=True)
    access_expires_at: Mapped[int] = mapped_column(BigInteger)
    refresh_expires_at: Mapped[int] = mapped_column(BigInteger)


class KakaoIdentity(Base):
    __tablename__ = "kakao_identities"
    subject: Mapped[str] = mapped_column(String(40), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), unique=True)
