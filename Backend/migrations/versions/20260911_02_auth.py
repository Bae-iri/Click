"""Add password accounts and revocable hashed token sessions."""
from alembic import op
import sqlalchemy as sa

revision = "20260911_02"
down_revision = "20260830_01"
branch_labels = None
depends_on = None


def upgrade():
    if op.get_bind().dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import JSONB
        op.alter_column("ui_events", "metadata", type_=JSONB(), postgresql_using="metadata::jsonb")
    op.create_table("accounts",
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("email", sa.String(254), nullable=False, unique=True),
        sa.Column("password_hash", sa.String(256), nullable=False))
    op.create_table("auth_sessions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("accounts.user_id", ondelete="CASCADE"), nullable=False),
        sa.Column("access_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("refresh_hash", sa.String(64), nullable=False, unique=True),
        sa.Column("access_expires_at", sa.BigInteger(), nullable=False),
        sa.Column("refresh_expires_at", sa.BigInteger(), nullable=False))
    op.create_index("ix_auth_sessions_user_id", "auth_sessions", ["user_id"])


def downgrade():
    op.drop_table("auth_sessions")
    op.drop_table("accounts")
    if op.get_bind().dialect.name == "postgresql":
        op.alter_column("ui_events", "metadata", type_=sa.JSON(), postgresql_using="metadata::json")
