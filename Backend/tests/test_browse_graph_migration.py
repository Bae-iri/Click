from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


def test_upgrade_preserves_legacy_proposals_and_evaluations_and_downgrade_round_trips(tmp_path, monkeypatch):
    url = 'sqlite+pysqlite:///' + (tmp_path / 'graphs.db').as_posix()
    monkeypatch.setenv('DATABASE_URL', url)
    # Configure the isolated migration without replacing pytest/application loggers.
    config = Config()
    config.set_main_option('script_location', str(Path(__file__).parents[1] / 'migrations'))
    config.set_main_option('sqlalchemy.url', url)
    command.upgrade(config, '20261111_19')
    engine = create_engine(url)
    with engine.begin() as db:
        db.execute(text("INSERT INTO graph_proposals "
            "(id,task_id,base_graph_version,proposal_type,change,evidence,proposal_confidence,requires_human_review,created_at) "
            "VALUES ('legacy','MVP-01','1.0.0','transition_priority',:body,:evidence,0.5,1,1)"),
            {'body': 'existing-encrypted-body', 'evidence': 'existing-encrypted-evidence'})
        db.execute(text("INSERT INTO evaluation_reports "
            "(id,proposal_id,decision,has_regression,failed_cases,created_at) "
            "VALUES ('evaluation','legacy','NEEDS_DATA',0,'existing-encrypted-cases',1)"))
    command.upgrade(config, 'head')
    with engine.connect() as db:
        assert 'runtime_receipt_id' in {c['name'] for c in inspect(db).get_columns('graph_proposals')}
        proposal = db.execute(text("SELECT change,evidence,runtime_receipt_id FROM graph_proposals WHERE id='legacy'")).one()
        assert tuple(proposal) == ('existing-encrypted-body', 'existing-encrypted-evidence', None)
        fks = inspect(db).get_foreign_keys('graph_proposals')
        assert any(fk['referred_table'] == 'runtime_event_consumptions' and fk['options'].get('ondelete') == 'CASCADE' for fk in fks)
    command.downgrade(config, '20261111_19')
    with engine.connect() as db:
        assert 'runtime_receipt_id' not in {c['name'] for c in inspect(db).get_columns('graph_proposals')}
        assert db.execute(text("SELECT COUNT(*) FROM graph_proposals WHERE id='legacy'")).scalar_one() == 1
        assert db.execute(text("SELECT COUNT(*) FROM evaluation_reports WHERE proposal_id='legacy'")).scalar_one() == 1
    command.upgrade(config, 'head')
    engine.dispose()
