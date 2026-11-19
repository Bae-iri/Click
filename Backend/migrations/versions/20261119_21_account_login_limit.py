"""Persist account login attempt windows across workers and restarts."""
from alembic import op
import sqlalchemy as sa

revision = '20261119_21'
down_revision = '20261117_20'
branch_labels = depends_on = None


def upgrade():
    op.create_table('auth_login_windows',
        sa.Column('identity_hash', sa.String(64), nullable=False),
        sa.Column('window_start', sa.BigInteger(), nullable=False),
        sa.Column('attempts', sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint('identity_hash'))
    op.create_index('ix_auth_login_windows_window_start', 'auth_login_windows', ['window_start'])


def downgrade():
    op.drop_index('ix_auth_login_windows_window_start', table_name='auth_login_windows')
    op.drop_table('auth_login_windows')
