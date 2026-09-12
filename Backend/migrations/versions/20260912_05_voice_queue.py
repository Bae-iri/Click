"""Persistent per-user voice FIFO and interrupt state."""
from alembic import op
import sqlalchemy as sa
revision = "20260912_05"
down_revision = "20260912_04"
branch_labels = depends_on = None


def upgrade():
    op.create_table("voice_queue_states",
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("last_sequence", sa.BigInteger(), nullable=False),
        sa.Column("version", sa.BigInteger(), nullable=False),
        sa.Column("awaiting_replacement", sa.Boolean(), nullable=False))
    op.create_table("voice_requests",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("raw_goal", sa.String(500)),
        sa.Column("priority", sa.String(10), nullable=False),
        sa.Column("status", sa.String(12), nullable=False),
        sa.Column("agent_session_id", sa.String(200), sa.ForeignKey("agent_sessions.id", ondelete="SET NULL"), unique=True),
        sa.Column("sequence", sa.BigInteger(), nullable=False),
        sa.Column("payload_hash", sa.String(64), nullable=False),
        sa.Column("failure_code", sa.String(40)),
        sa.Column("created_at", sa.BigInteger(), nullable=False),
        sa.Column("updated_at", sa.BigInteger(), nullable=False),
        sa.Column("expires_at", sa.BigInteger(), nullable=False),
        sa.UniqueConstraint("user_id", "sequence", name="uq_voice_request_sequence"),
        sa.CheckConstraint("priority IN ('NORMAL', 'VIP')", name="ck_voice_priority"),
        sa.CheckConstraint("status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')", name="ck_voice_status"))
    op.create_index("uq_voice_running_user", "voice_requests", ["user_id"], unique=True,
        postgresql_where=sa.text("status = 'RUNNING'"), sqlite_where=sa.text("status = 'RUNNING'"))
    op.create_index("ix_voice_user_status_sequence", "voice_requests", ["user_id", "status", "sequence"])


def downgrade():
    op.drop_table("voice_requests")
    op.drop_table("voice_queue_states")
