"""Add phone credentials while preserving legacy email accounts."""
from alembic import op
import sqlalchemy as sa
revision = "20261104_17"
down_revision = "20261104_16"
branch_labels = depends_on = None

def upgrade():
    with op.batch_alter_table("accounts") as batch:
        batch.alter_column("email", existing_type=sa.String(254), nullable=True)
        batch.add_column(sa.Column("phone_number", sa.String(11), nullable=True))
        batch.create_unique_constraint("uq_accounts_phone_number", ["phone_number"])

def downgrade():
    if op.get_bind().execute(sa.text("SELECT COUNT(*) FROM accounts WHERE phone_number IS NOT NULL")).scalar_one():
        raise RuntimeError("Migrate phone accounts before downgrading authentication.")
    with op.batch_alter_table("accounts") as batch:
        batch.drop_constraint("uq_accounts_phone_number", type_="unique")
        batch.drop_column("phone_number")
        batch.alter_column("email", existing_type=sa.String(254), nullable=False)

