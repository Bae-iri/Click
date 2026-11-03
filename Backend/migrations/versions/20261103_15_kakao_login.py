"""Add Kakao identities while preserving existing email accounts and sessions."""
from alembic import op
import sqlalchemy as sa

revision = "20261103_15"
down_revision = "20261027_14"
branch_labels = depends_on = None


def change_session_owner(target, column):
    naming = {"fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s"}
    existing = sa.inspect(op.get_bind()).get_foreign_keys("auth_sessions")
    foreign_key = next(key for key in existing if key["constrained_columns"] == ["user_id"])
    old_name = foreign_key["name"] or "fk_auth_sessions_user_id_" + foreign_key["referred_table"]
    with op.batch_alter_table("auth_sessions", naming_convention=naming) as batch:
        batch.drop_constraint(old_name, type_="foreignkey")
        batch.create_foreign_key("fk_auth_sessions_user_id_" + target, target,
                                 ["user_id"], [column], ondelete="CASCADE")


def upgrade():
    change_session_owner("users", "id")
    op.create_table("kakao_identities",
        sa.Column("subject", sa.String(40), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"),
                  nullable=False, unique=True))


def downgrade():
    # Older schemas cannot represent social-only accounts. Refuse to discard them.
    count = op.get_bind().execute(sa.text("SELECT COUNT(*) FROM kakao_identities")).scalar_one()
    if count:
        raise RuntimeError("Migrate Kakao accounts before downgrading authentication.")
    op.drop_table("kakao_identities")
    change_session_owner("accounts", "user_id")
