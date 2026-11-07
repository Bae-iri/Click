"""Encrypt retained user content and add keyed login indexes."""
from alembic import op
import sqlalchemy as sa

revision = "20261107_18"
down_revision = "20261104_17"
branch_labels = depends_on = None


def upgrade():
    import os
    from app.core.data_encryption import DataCipher, MARKER
    from app.core.encryption_migration import protect_data
    cipher = DataCipher.from_environment()  # Fail before schema/data changes if keys are absent.
    connection = op.get_bind()
    if (connection.execute(sa.text("SELECT COUNT(*) FROM users")).scalar_one()
            and os.getenv("DATA_ENCRYPTION_MAINTENANCE") != "1"):
        raise RuntimeError("Stop legacy database writers, then set DATA_ENCRYPTION_MAINTENANCE=1 for the initial encryption migration")
    with op.batch_alter_table("users") as batch:
        batch.alter_column("name", existing_type=sa.String(50), type_=sa.Text())
    with op.batch_alter_table("accounts") as batch:
        batch.alter_column("email", existing_type=sa.String(254), type_=sa.Text())
        batch.alter_column("phone_number", existing_type=sa.String(11), type_=sa.Text())
        batch.add_column(sa.Column("email_lookup", sa.String(64), nullable=True))
        batch.add_column(sa.Column("phone_lookup", sa.String(64), nullable=True))
        batch.create_unique_constraint("uq_accounts_email_lookup", ["email_lookup"])
        batch.create_unique_constraint("uq_accounts_phone_lookup", ["phone_lookup"])
    with op.batch_alter_table("voice_requests") as batch:
        batch.alter_column("raw_goal", existing_type=sa.String(500), type_=sa.Text())
    with op.batch_alter_table("graph_release_audits") as batch:
        batch.alter_column("reviewer", existing_type=sa.String(200), type_=sa.Text())
    with op.batch_alter_table("kakao_identities") as batch:
        batch.alter_column("subject", existing_type=sa.String(40), type_=sa.String(64))
    with op.batch_alter_table("runtime_event_consumptions") as batch:
        batch.add_column(sa.Column("expires_at", sa.BigInteger(), nullable=True))
        batch.create_index("ix_runtime_event_consumptions_expires_at", ["expires_at"])
    protect_data(connection, cipher)
    # Legacy receipts may lack their own deadline; the referenced event is a
    # conservative fallback. Current summaries use their earliest evidence expiry.
    connection.execute(sa.text("UPDATE runtime_event_consumptions SET expires_at = "
        "(SELECT expires_at FROM runtime_events WHERE runtime_events.id = runtime_event_consumptions.event_id) "
        "WHERE expires_at IS NULL"))
    with op.batch_alter_table("runtime_event_consumptions") as batch:
        batch.alter_column("expires_at", existing_type=sa.BigInteger(), nullable=False)
    identities = sa.table("kakao_identities", sa.column("subject", sa.String()), sa.column("user_id", sa.String()))
    for row in connection.execute(sa.select(identities)).all():
        connection.execute(identities.update().where(identities.c.subject == row.subject).values(
            subject=cipher.lookup(row.subject, "kakao.subject")))
    op.create_table("data_protection_state", sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("payload", sa.JSON(), nullable=False), sa.Column("lookup_check", sa.String(64), nullable=False))
    state = sa.table("data_protection_state", sa.column("id", sa.Integer()), sa.column("payload", sa.JSON()), sa.column("lookup_check", sa.String()))
    connection.execute(state.insert().values(id=1,
        payload={"encryption": MARKER, "token": cipher.encrypt("configured", "data_protection_state.payload")},
        lookup_check=cipher.lookup("configured", "data_protection_state.lookup_check")))


def downgrade():
    raise RuntimeError("Encryption cannot be downgraded to plaintext; deploy a compatible reader or restore an approved backup.")
