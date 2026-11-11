from pathlib import Path
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect, text
from app.database import build_engine


def test_migration_drops_only_user_modeling_and_downgrade_creates_neutral_profiles(tmp_path, monkeypatch):
    url = 'sqlite+pysqlite:///' + (tmp_path / 'remove-personalization.db').as_posix()
    monkeypatch.setenv('DATABASE_URL', url)
    config = Config()
    config.set_main_option('script_location', str(Path(__file__).parents[1] / 'migrations'))
    command.upgrade(config, '20261103_15')
    engine = build_engine(url)
    with engine.begin() as db:
        db.execute(text("""INSERT INTO users (id, name, age_group, occupation_category)
            VALUES ('existing-user', 'kept', '60_plus', 'retired')"""))
        db.execute(text("""INSERT INTO user_ui_profiles
            (user_id,comprehension_level,explanation_depth,prefers_step_by_step,confidence_score,evidence_count)
            VALUES ('existing-user','beginner','detailed',true,-5,4)"""))
        for purpose in ('personalization', 'model_training', 'conversation_storage'):
            db.execute(text("""INSERT INTO consents (id,user_id,purpose,consented,policy_version)
                VALUES (:id,'existing-user',:purpose,true,'1')"""), {'id':purpose,'purpose':purpose})
    command.upgrade(config, '20261104_16')
    with engine.connect() as db:
        tables = inspect(db).get_table_names()
        assert 'user_ui_profiles' not in tables and 'ui_events' not in tables
        assert {'runtime_events','approved_task_graphs','graph_release_audits','accounts'} <= set(tables)
        assert db.execute(text('SELECT name FROM users')).scalar_one() == 'kept'
        assert {c['name'] for c in inspect(db).get_columns('users')} == {'id', 'name', 'created_at'}
        assert set(db.execute(text('SELECT purpose FROM consents')).scalars()) == {'model_training','conversation_storage'}
        assert db.execute(text('PRAGMA foreign_key_check')).all() == []
    command.downgrade(config, '20261103_15')
    with engine.connect() as db:
        assert db.execute(text('SELECT comprehension_level, evidence_count FROM user_ui_profiles')).one() == ('unknown', 0)
        assert db.execute(text('SELECT COUNT(*) FROM ui_events')).scalar_one() == 0
    command.upgrade(config, '20261104_16')
    engine.dispose()
