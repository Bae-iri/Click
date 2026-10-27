"""Record reviewed AI2 releases and rollback history."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20261027_14"
down_revision = "20261011_13"
branch_labels = depends_on = None


def upgrade():
    op.create_table("graph_release_audits",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("release_id", sa.String(200), sa.ForeignKey("approved_task_graphs.id"), nullable=False),
        sa.Column("previous_hash", sa.String(64), nullable=False),
        sa.Column("graph_hash", sa.String(64), nullable=False),
        sa.Column("action", sa.String(20), nullable=False),
        sa.Column("reviewer", sa.String(200), nullable=False),
        sa.Column("reviewed_at", sa.BigInteger(), nullable=False),
        sa.Column("details", sa.JSON().with_variant(postgresql.JSONB(), "postgresql"), nullable=False))
    op.create_index("ix_graph_release_audits_release_id", "graph_release_audits", ["release_id"])


def downgrade():
    op.drop_index("ix_graph_release_audits_release_id", table_name="graph_release_audits")
    op.drop_table("graph_release_audits")
