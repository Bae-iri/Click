"""Store product feedback independently from executable tasks."""
from alembic import op
import sqlalchemy as sa

revision = '20261111_19'
down_revision = '20261107_18'
branch_labels = depends_on = None


def upgrade():
    op.create_table('product_feedback',
        sa.Column('id', sa.String(36), primary_key=True),
        sa.Column('user_id', sa.String(36), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
        sa.Column('session_id', sa.String(200), sa.ForeignKey('agent_sessions.id', ondelete='SET NULL')),
        sa.Column('sentiment', sa.String(8), nullable=False),
        sa.Column('source_text', sa.Text(), nullable=False),
        sa.Column('detail', sa.Text()),
        sa.Column('created_at', sa.BigInteger(), nullable=False),
        sa.Column('updated_at', sa.BigInteger(), nullable=False),
        sa.CheckConstraint("sentiment IN ('negative', 'positive')", name='ck_product_feedback_sentiment'))
    op.create_index('ix_product_feedback_user_id', 'product_feedback', ['user_id'])
    op.create_index('ix_product_feedback_created_id', 'product_feedback', ['created_at', 'id'])


def downgrade():
    op.drop_table('product_feedback')
