"""Remove the Goal-Type-to-Graph ownership link from the approved registry."""

from alembic import op
import sqlalchemy as sa


revision = "20260920_09"
down_revision = "20260919_08"
branch_labels = depends_on = None


def upgrade():
    op.execute("UPDATE approved_task_graphs SET active = false, approved = false")
    with op.batch_alter_table("approved_task_graphs") as batch:
        batch.drop_index("ix_approved_task_graphs_goal_type")
        batch.drop_column("goal_type")


def downgrade():
    with op.batch_alter_table("approved_task_graphs") as batch:
        batch.add_column(sa.Column("goal_type", sa.String(200), nullable=True))
        batch.create_index("ix_approved_task_graphs_goal_type", ["goal_type"])
