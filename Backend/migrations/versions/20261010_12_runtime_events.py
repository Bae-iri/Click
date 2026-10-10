"""Persist bounded recovery history and execution evidence with offline receipts."""
from alembic import op
import sqlalchemy as sa

revision = "20261010_12"
down_revision = "20261010_11"
branch_labels = depends_on = None


def upgrade():
    op.add_column("agent_sessions", sa.Column("recovery", sa.JSON(), nullable=True))
    op.create_table("runtime_events",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("session_id", sa.String(200), sa.ForeignKey("agent_sessions.id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("episode_id", sa.String(200)), sa.Column("sequence", sa.Integer(), nullable=False),
        sa.Column("request_id", sa.String(200), nullable=False), sa.Column("graph_hash", sa.String(64)),
        sa.Column("kind", sa.String(30), nullable=False), sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.BigInteger(), nullable=False), sa.Column("expires_at", sa.BigInteger(), nullable=False),
        sa.UniqueConstraint("session_id", "sequence", name="uq_runtime_event_sequence"))
    op.create_index("ix_runtime_events_session_id", "runtime_events", ["session_id"])
    op.create_index("ix_runtime_events_expires_at", "runtime_events", ["expires_at"])
    op.create_table("runtime_event_consumptions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("event_id", sa.String(36), sa.ForeignKey("runtime_events.id", ondelete="CASCADE"), nullable=False),
        sa.Column("consumer", sa.String(100), nullable=False), sa.Column("result", sa.JSON(), nullable=False),
        sa.Column("consumed_at", sa.BigInteger(), nullable=False),
        sa.UniqueConstraint("consumer", "event_id", name="uq_runtime_event_consumer"))
    op.create_index("ix_runtime_event_consumptions_event_id", "runtime_event_consumptions", ["event_id"])


def downgrade():
    op.drop_table("runtime_event_consumptions")
    op.drop_table("runtime_events")
    op.drop_column("agent_sessions", "recovery")
