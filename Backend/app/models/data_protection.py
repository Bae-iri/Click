"""A key check containing no user data, used before serving any stored content."""
from sqlalchemy import Integer, JSON, String
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base


class DataProtectionState(Base):
    __tablename__ = "data_protection_state"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    payload: Mapped[dict] = mapped_column(JSON)
    lookup_check: Mapped[str] = mapped_column(String(64))
