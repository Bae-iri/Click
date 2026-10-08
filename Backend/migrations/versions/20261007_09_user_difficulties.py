"""Persist Agent(2) user-specific state difficulty evidence."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "20261007_09"
down_revision = "20260919_08"
branch_labels = depends_on = None


def upgrade():
    op.add_column("user_ui_profiles", sa.Column("state_difficulties",
        sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False, server_default=sa.text("'{}'")))


def downgrade():
    op.drop_column("user_ui_profiles", "state_difficulties")
