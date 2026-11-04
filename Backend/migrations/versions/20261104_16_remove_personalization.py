"""Remove user modeling; retain accounts, runtime evidence and graph releases.

Profile/event contents and signup demographics are intentionally discarded.
Downgrade restores empty tables, neutral profiles and null demographic columns,
not deleted personalization data.
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = "20261104_16"
down_revision = "20261103_15"
branch_labels = depends_on = None


def upgrade():
    op.drop_table("ui_events")
    op.drop_table("user_ui_profiles")
    op.execute(sa.text("DELETE FROM consents WHERE purpose = 'personalization'"))
    op.drop_column("users", "age_group")
    op.drop_column("users", "occupation_category")


def downgrade():
    op.add_column("users", sa.Column("age_group", sa.String(20), nullable=True))
    op.add_column("users", sa.Column("occupation_category", sa.String(50), nullable=True))
    op.create_table("user_ui_profiles",
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("comprehension_level", sa.String(20), nullable=False),
        sa.Column("explanation_depth", sa.String(20), nullable=False),
        sa.Column("prefers_step_by_step", sa.Boolean(), nullable=False),
        sa.Column("confidence_score", sa.Float(), nullable=False),
        sa.Column("evidence_count", sa.Integer(), nullable=False),
        sa.Column("state_difficulties", sa.JSON().with_variant(JSONB(), "postgresql"), nullable=False, server_default="{}"),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()))
    profiles = sa.table("user_ui_profiles", sa.column("user_id"), sa.column("comprehension_level"),
        sa.column("explanation_depth"), sa.column("prefers_step_by_step"),
        sa.column("confidence_score"), sa.column("evidence_count"))
    op.get_bind().execute(profiles.insert().from_select(list(profiles.c.keys()),
        sa.select(sa.column("id"), sa.literal("unknown"), sa.literal("normal"),
                  sa.literal(True), sa.literal(0.0), sa.literal(0)).select_from(sa.table("users"))))
    op.create_table("ui_events",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("session_id", sa.String(200)),
        sa.Column("event_type", sa.String(50), nullable=False),
        sa.Column("screen_name", sa.String(200)),
        sa.Column("metadata", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()))
    for column in ("user_id", "session_id", "event_type"):
        op.create_index("ix_ui_events_" + column, "ui_events", [column])
