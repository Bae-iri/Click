"""Persist goal decisions and separate technical/user task outcomes."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "20261011_12"
down_revision = "20261009_11"
branch_labels = depends_on = None


def upgrade():
    json_type = sa.JSON().with_variant(JSONB(), "postgresql")
    op.add_column("agent_sessions", sa.Column("task_outcome", json_type))
    op.add_column("agent_sessions", sa.Column("outcome_history", json_type, nullable=False, server_default=sa.text("'[]'")))
    op.add_column("agent_sessions", sa.Column("paused_from", sa.String(30)))
    op.create_table("agent_goal_resolutions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("session_id", sa.String(200), nullable=False),
        sa.Column("request_id", sa.String(200), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("candidates", json_type, nullable=False),
        sa.Column("response", json_type, nullable=False),
        sa.Column("selected_goal", sa.String(200)),
        sa.Column("selection_request_id", sa.String(200)),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("expires_at", sa.BigInteger, nullable=False),
        sa.UniqueConstraint("user_id", "request_id", name="uq_goal_resolution_request"))
    op.create_index("ix_agent_goal_resolutions_user_id", "agent_goal_resolutions", ["user_id"])
    op.create_index("ix_agent_goal_resolutions_session_id", "agent_goal_resolutions", ["session_id"])


def downgrade():
    op.drop_table("agent_goal_resolutions")
    for column in ("paused_from", "outcome_history", "task_outcome"):
        op.drop_column("agent_sessions", column)
