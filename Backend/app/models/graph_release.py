"""Append-only operator review/activation audit, independent of user telemetry."""
from uuid import uuid4
from sqlalchemy import BigInteger, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base
from app.models.user import JSONType
from app.core.data_encryption import EncryptedJSON, EncryptedText


class GraphReleaseAudit(Base):
    __tablename__ = "graph_release_audits"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    release_id: Mapped[str] = mapped_column(String(200), ForeignKey("approved_task_graphs.id"), index=True)
    previous_hash: Mapped[str] = mapped_column(String(64))
    graph_hash: Mapped[str] = mapped_column(String(64))
    action: Mapped[str] = mapped_column(String(20))
    reviewer: Mapped[str] = mapped_column(EncryptedText("graph_release_audits.reviewer"))
    reviewed_at: Mapped[int] = mapped_column(BigInteger)
    details: Mapped[dict] = mapped_column(EncryptedJSON("graph_release_audits.details"))
