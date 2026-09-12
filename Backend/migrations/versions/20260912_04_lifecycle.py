"""Approval, feedback and account action records."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB
revision = "20260912_04"
down_revision = "20260911_03"
branch_labels = depends_on = None


def upgrade():
    op.create_table("safety_confirmations",
        sa.Column("session_id", sa.String(200), sa.ForeignKey("agent_sessions.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("id", sa.String(36), nullable=False, unique=True),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("expires_at", sa.BigInteger(), nullable=False),
        sa.Column("screen_hash", sa.String(64), nullable=False),
        sa.Column("command", sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False))
    op.create_table("task_feedback",
        sa.Column("session_id", sa.String(200), sa.ForeignKey("agent_sessions.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("rating", sa.Integer(), nullable=False), sa.Column("reason_code", sa.String(40)),
        sa.Column("created_at", sa.BigInteger(), nullable=False))
    op.create_table("account_actions",
        sa.Column("token_hash", sa.String(64), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("accounts.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("purpose", sa.String(30), nullable=False),
        sa.Column("expires_at", sa.BigInteger(), nullable=False),
        sa.Column("credential_hash", sa.String(64), nullable=False))
    op.create_index("ix_account_actions_user_id", "account_actions", ["user_id"])
    op.create_table("email_verifications",
        sa.Column("user_id", sa.String(36), sa.ForeignKey("accounts.user_id", ondelete="CASCADE"), primary_key=True),
        sa.Column("verified_at", sa.BigInteger(), nullable=False))


def downgrade():
    for table in ("email_verifications", "account_actions", "task_feedback", "safety_confirmations"):
        op.drop_table(table)
