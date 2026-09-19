"""Approved immutable runtime task graph releases."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "20260919_08"
down_revision = "20260919_07"
branch_labels = depends_on = None


def upgrade():
    op.create_table("approved_task_graphs",
        sa.Column("id", sa.String(200), primary_key=True),
        sa.Column("graph_id", sa.String(100), nullable=False),
        sa.Column("task_id", sa.String(100), nullable=False),
        sa.Column("goal_type", sa.String(200), nullable=False),
        sa.Column("target_app", sa.String(100), nullable=False),
        sa.Column("graph_version", sa.String(64), nullable=False),
        sa.Column("graph_hash", sa.String(64), nullable=False),
        sa.Column("schema_version", sa.String(30), nullable=False),
        sa.Column("body", sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("approved", sa.Boolean(), nullable=False),
        sa.Column("priority", sa.Integer(), nullable=False),
        sa.UniqueConstraint("graph_id", "graph_version", name="uq_graph_release"))
    op.create_index("ix_approved_task_graphs_task_id", "approved_task_graphs", ["task_id"])
    op.create_index("ix_approved_task_graphs_goal_type", "approved_task_graphs", ["goal_type"])


def downgrade():
    op.drop_table("approved_task_graphs")
