"""AI-Agent(2) graph proposal and evaluation report storage."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB
revision = "20260912_06"
down_revision = "20260912_05"
branch_labels = depends_on = None


def upgrade():
    op.create_table("graph_proposals",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("task_id", sa.String(100), nullable=False),
        sa.Column("base_graph_version", sa.String(50), nullable=False),
        sa.Column("proposal_type", sa.String(50), nullable=False),
        sa.Column("change", sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False),
        sa.Column("evidence", sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False),
        sa.Column("proposal_confidence", sa.Float(), nullable=False),
        sa.Column("requires_human_review", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False))
    op.create_table("evaluation_reports",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("proposal_id", sa.String(64), sa.ForeignKey("graph_proposals.id", ondelete="CASCADE"), nullable=False),
        sa.Column("decision", sa.String(20), nullable=False),
        sa.Column("has_regression", sa.Boolean(), nullable=False),
        sa.Column("failed_cases", sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
        sa.CheckConstraint("decision IN ('PASS', 'FAIL', 'NEEDS_DATA', 'ERROR')", name="ck_evaluation_decision"))
    op.create_index("ix_evaluation_reports_proposal_id", "evaluation_reports", ["proposal_id"])


def downgrade():
    op.drop_table("evaluation_reports")
    op.drop_table("graph_proposals")
