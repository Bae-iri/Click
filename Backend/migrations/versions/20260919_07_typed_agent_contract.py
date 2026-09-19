"""Persist typed goal episodes and pinned graph references."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "20260919_07"
down_revision = "20260912_06"
branch_labels = depends_on = None


def upgrade():
    json_type = sa.JSON().with_variant(JSONB(), "postgresql")
    op.add_column("agent_sessions", sa.Column("episode_id", sa.String(200)))
    op.add_column("agent_sessions", sa.Column("accepted_goal_type", sa.String(200)))
    op.add_column("agent_sessions", sa.Column("goal", json_type))
    op.add_column("agent_sessions", sa.Column("device_context", json_type))
    op.add_column("agent_sessions", sa.Column("graph_version", sa.String(64)))
    op.add_column("agent_sessions", sa.Column("graph_hash", sa.String(64)))


def downgrade():
    for name in ("graph_hash", "graph_version", "device_context", "goal", "accepted_goal_type", "episode_id"):
        op.drop_column("agent_sessions", name)
