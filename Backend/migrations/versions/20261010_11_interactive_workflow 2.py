"""Persist result prompts and adjustment state separately from runtime checkpoints."""
from alembic import op
import sqlalchemy as sa

revision = "20261010_11"
down_revision = "20261009_10"
branch_labels = depends_on = None


def upgrade():
    op.add_column("agent_sessions", sa.Column("workflow", sa.JSON(), nullable=True))


def downgrade():
    op.drop_column("agent_sessions", "workflow")
