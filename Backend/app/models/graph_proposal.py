from uuid import uuid4
from sqlalchemy import BigInteger, Boolean, CheckConstraint, Float, ForeignKey, Index, String
from sqlalchemy.orm import Mapped, mapped_column
from app.database import Base
from app.models.user import JSONType
from app.core.data_encryption import EncryptedJSON

BROWSE_GRAPH_CONSUMER = 'ai2-browse-graphs.v1'


class GraphProposal(Base):
    """Mirrors ai_agent.graph_proposal.schemas.GraphUpdateProposal."""
    __tablename__ = "graph_proposals"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    task_id: Mapped[str] = mapped_column(String(100))
    base_graph_version: Mapped[str] = mapped_column(String(50))
    proposal_type: Mapped[str] = mapped_column(String(50))
    change: Mapped[dict] = mapped_column(EncryptedJSON("graph_proposals.change"))
    evidence: Mapped[dict] = mapped_column(EncryptedJSON("graph_proposals.evidence"))
    proposal_confidence: Mapped[float] = mapped_column(Float)
    requires_human_review: Mapped[bool] = mapped_column(Boolean)
    created_at: Mapped[int] = mapped_column(BigInteger)
    runtime_receipt_id: Mapped[str | None] = mapped_column(String(36),
        ForeignKey('runtime_event_consumptions.id', ondelete='CASCADE'), unique=True, index=True)


class EvaluationReport(Base):
    """Mirrors GraphValidator.validate()'s return shape."""
    __tablename__ = "evaluation_reports"
    __table_args__ = (
        CheckConstraint("decision IN ('PASS', 'FAIL', 'NEEDS_DATA', 'ERROR')", name="ck_evaluation_decision"),
        Index("ix_evaluation_reports_proposal_id", "proposal_id"),
    )
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    proposal_id: Mapped[str] = mapped_column(String(64), ForeignKey("graph_proposals.id", ondelete="CASCADE"))
    decision: Mapped[str] = mapped_column(String(20))
    has_regression: Mapped[bool] = mapped_column(Boolean)
    failed_cases: Mapped[list] = mapped_column(EncryptedJSON("evaluation_reports.failed_cases"))
    created_at: Mapped[int] = mapped_column(BigInteger)
