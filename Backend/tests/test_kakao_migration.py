from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, text

from app.database import build_engine


def test_upgrade_preserves_email_sessions_and_downgrade_protects_kakao_users(tmp_path, monkeypatch):
    url = "sqlite+pysqlite:///" + (tmp_path / "migration.db").as_posix()
    monkeypatch.setenv("DATABASE_URL", url)
    backend = Path(__file__).parents[1]
    # Do not let Alembic's fileConfig disable loggers used by later API tests.
    config = Config()
    config.set_main_option("script_location", str(backend / "migrations"))
    command.upgrade(config, "20261027_14")
    engine = build_engine(url)
    with engine.begin() as db:
        db.execute(text("INSERT INTO users (id, age_group) VALUES ('email-user', 'unknown')"))
        db.execute(text("INSERT INTO accounts (user_id, email, password_hash) VALUES ('email-user', 'old@example.com', 'hash')"))
        db.execute(text("""INSERT INTO auth_sessions
            (id,user_id,access_hash,refresh_hash,access_expires_at,refresh_expires_at)
            VALUES ('session','email-user','access','refresh',9999999999,9999999999)"""))
    command.upgrade(config, "head")
    with engine.begin() as db:
        assert db.execute(text("SELECT email FROM accounts")).scalar_one() == "old@example.com"
        assert db.execute(text("SELECT id FROM auth_sessions")).scalar_one() == "session"
        assert inspect(db).get_foreign_keys("auth_sessions")[0]["referred_table"] == "users"
        db.execute(text("INSERT INTO users (id, age_group) VALUES ('kakao-user', 'unknown')"))
        db.execute(text("INSERT INTO kakao_identities (subject,user_id) VALUES ('123','kakao-user')"))
    with pytest.raises(RuntimeError, match="Migrate Kakao accounts"):
        command.downgrade(config, "20261027_14")
    with engine.begin() as db:
        assert db.execute(text("SELECT user_id FROM kakao_identities")).scalar_one() == "kakao-user"
        db.execute(text("DELETE FROM users WHERE id='kakao-user'"))
    command.downgrade(config, "20261027_14")
    command.upgrade(config, "head")
    with engine.connect() as db:
        assert db.execute(text("SELECT id FROM auth_sessions")).scalar_one() == "session"
        assert db.execute(text("PRAGMA foreign_key_check")).all() == []
    engine.dispose()
