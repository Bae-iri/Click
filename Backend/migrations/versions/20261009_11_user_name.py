"""Store the user's display name collected during signup."""
from alembic import op
import sqlalchemy as sa

revision = "20261009_11"
down_revision = "20261009_10"
branch_labels = depends_on = None


def upgrade():
    op.add_column("users", sa.Column("name", sa.String(50)))


def downgrade():
    op.drop_column("users", "name")
