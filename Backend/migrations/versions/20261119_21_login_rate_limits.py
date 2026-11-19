"""Persistent account-scoped login attempt limits."""
from alembic import op
import sqlalchemy as sa

revision = '20261119_21'
down_revision = '20261117_20'
branch_labels = depends_on = None


def upgrade():
    op.create_table('login_rate_limits',
        sa.Column('account_key',sa.String(64),primary_key=True),
        sa.Column('window_started_at',sa.BigInteger(),primary_key=True),
        sa.Column('attempts',sa.Integer(),nullable=False))


def downgrade():
    op.drop_table('login_rate_limits')
