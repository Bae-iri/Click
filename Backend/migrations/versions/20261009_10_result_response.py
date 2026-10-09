"""Persist the user's result acknowledgement separately from action approval."""
from alembic import op
import sqlalchemy as sa

revision = "20261009_10"
down_revision = "20261007_09"
branch_labels = depends_on = None


def upgrade():
    op.add_column("agent_sessions", sa.Column("result_response", sa.String(10)))


def downgrade():
    op.drop_column("agent_sessions", "result_response")
