"""Tie automatically generated graph drafts to expiring source receipts."""
from alembic import op
import sqlalchemy as sa

revision = '20261117_20'
down_revision = '20261111_19'
branch_labels = depends_on = None


def upgrade():
    with op.batch_alter_table('graph_proposals') as batch:
        batch.add_column(sa.Column('runtime_receipt_id', sa.String(36), nullable=True))
        batch.create_foreign_key('fk_graph_proposal_runtime_receipt', 'runtime_event_consumptions',
                                ['runtime_receipt_id'], ['id'], ondelete='CASCADE')
        batch.create_index('ix_graph_proposals_runtime_receipt_id', ['runtime_receipt_id'], unique=True)


def downgrade():
    op.execute(sa.text("DELETE FROM evaluation_reports WHERE proposal_id IN "
                       "(SELECT id FROM graph_proposals WHERE runtime_receipt_id IS NOT NULL)"))
    op.execute(sa.text("DELETE FROM graph_proposals WHERE runtime_receipt_id IS NOT NULL"))
    with op.batch_alter_table('graph_proposals') as batch:
        batch.drop_index('ix_graph_proposals_runtime_receipt_id')
        batch.drop_constraint('fk_graph_proposal_runtime_receipt', type_='foreignkey')
        batch.drop_column('runtime_receipt_id')
