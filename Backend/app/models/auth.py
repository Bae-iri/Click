from uuid import uuid4

from sqlalchemy import ForeignKey, String, BigInteger, event
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.core.data_encryption import EncryptedText, cipher_for


class Account(Base):
    __tablename__ = "accounts"
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    email: Mapped[str | None] = mapped_column(EncryptedText("accounts.email"), unique=True)
    phone_number: Mapped[str | None] = mapped_column(EncryptedText("accounts.phone_number"), unique=True)
    phone_lookup: Mapped[str | None] = mapped_column(String(64), unique=True)
    email_lookup: Mapped[str | None] = mapped_column(String(64), unique=True)
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
    subject: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), unique=True)


@event.listens_for(Account, "before_insert")
@event.listens_for(Account, "before_update")
def account_lookup_indexes(_mapper, connection, account):
    cipher = cipher_for(connection.dialect)
    account.phone_lookup = cipher.lookup(account.phone_number, "accounts.phone_number")
    account.email_lookup = cipher.lookup(account.email, "accounts.email")
