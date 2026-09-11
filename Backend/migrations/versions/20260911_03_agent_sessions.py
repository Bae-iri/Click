"""Persist agent sessions, checkpoint and idempotency records."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "20260911_03"
down_revision = "20260911_02"
branch_labels = None
depends_on = None


def upgrade():
    json_type = sa.JSON().with_variant(JSONB(), "postgresql")
    op.create_table("agent_sessions",
        sa.Column("id", sa.String(200), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("status", sa.String(30), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("task_id", sa.String(100)), sa.Column("state_id", sa.String(100)),
        sa.Column("last_guide_command_id", sa.String(500)),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("expires_at", sa.BigInteger(), nullable=False),
        sa.Column("lease_token", sa.String(36)), sa.Column("lease_until", sa.BigInteger()))
    op.create_index("ix_agent_sessions_user_id", "agent_sessions", ["user_id"])
    op.create_index("ix_agent_sessions_expires_at", "agent_sessions", ["expires_at"])
    op.create_table("agent_checkpoints",
        sa.Column("session_id", sa.String(200), sa.ForeignKey("agent_sessions.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("schema_version", sa.Integer(), nullable=False),
        sa.Column("graph_version", sa.String(64), nullable=False),
        sa.Column("state", json_type, nullable=False))
    op.create_table("agent_turns",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("session_id", sa.String(200), sa.ForeignKey("agent_sessions.id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("request_id", sa.String(200), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("response", json_type),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
        sa.UniqueConstraint("user_id", "request_id", name="uq_agent_turn_user_request"))
    op.create_index("ix_agent_turns_session_id", "agent_turns", ["session_id"])


def downgrade():
    op.drop_table("agent_turns")
    op.drop_table("agent_checkpoints")
    op.drop_table("agent_sessions")
