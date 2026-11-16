"""Product feedback is independent of the agent session lifecycle."""
from sqlalchemy import BigInteger, CheckConstraint, ForeignKey, Index, String
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base
from app.core.data_encryption import EncryptedText


class ProductFeedback(Base):
    __tablename__ = 'product_feedback'
    __table_args__ = (
        CheckConstraint("sentiment IN ('negative', 'positive')", name='ck_product_feedback_sentiment'),
        Index('ix_product_feedback_created_id', 'created_at', 'id'),
    )
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey('users.id', ondelete='CASCADE'), index=True)
    session_id: Mapped[str | None] = mapped_column(String(200), ForeignKey('agent_sessions.id', ondelete='SET NULL'))
    sentiment: Mapped[str] = mapped_column(String(8))
    source_text: Mapped[str] = mapped_column(EncryptedText('product_feedback.source_text'))
    detail: Mapped[str | None] = mapped_column(EncryptedText('product_feedback.detail'))
    created_at: Mapped[int] = mapped_column(BigInteger)
    updated_at: Mapped[int] = mapped_column(BigInteger)
