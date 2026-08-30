"""Add users, UI profiles, consents, and UI events."""

from alembic import op
import sqlalchemy as sa


revision = "20260830_01"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("age_group", sa.String(20)),
        sa.Column("occupation_category", sa.String(50)),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "user_ui_profiles",
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("comprehension_level", sa.String(20), nullable=False),
        sa.Column("explanation_depth", sa.String(20), nullable=False),
        sa.Column("prefers_step_by_step", sa.Boolean(), nullable=False),
        sa.Column("confidence_score", sa.Float(), nullable=False),
        sa.Column("evidence_count", sa.Integer(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_table(
        "consents",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("purpose", sa.String(50), nullable=False),
        sa.Column("consented", sa.Boolean(), nullable=False),
        sa.Column("policy_version", sa.String(30), nullable=False),
        sa.Column("recorded_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_consents_user_id", "consents", ["user_id"])
    op.create_table(
        "ui_events",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("user_id", sa.String(36), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("session_id", sa.String(200)),
        sa.Column("event_type", sa.String(50), nullable=False),
        sa.Column("screen_name", sa.String(200)),
        sa.Column("metadata", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_ui_events_user_id", "ui_events", ["user_id"])
    op.create_index("ix_ui_events_session_id", "ui_events", ["session_id"])
    op.create_index("ix_ui_events_event_type", "ui_events", ["event_type"])


def downgrade() -> None:
    op.drop_table("ui_events")
    op.drop_table("consents")
    op.drop_table("user_ui_profiles")
    op.drop_table("users")
