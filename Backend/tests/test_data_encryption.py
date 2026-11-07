"""Verify ciphertext with raw SQL, not just ORM roundtrips."""
import json
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from sqlalchemy import text, select
from alembic import command
from alembic.config import Config

from app.core.data_encryption import DataCipher, DataEncryptionError, MARKER, PREFIX
from app.core.encryption_migration import protect_data
from app.database import build_engine
from app.main import create_app
from app.models.auth import Account
from app.models.runtime_event import RuntimeEvent
from app.core.security import hash_password
from tests.helpers import authenticated_client
from tests.test_typed_agent_contract import start_payload


def test_every_encrypted_model_column_is_covered_by_migration_and_rotation():
    from app.database import Base
    from app.core.data_encryption import EncryptedJSON, EncryptedText
    from app.core.encryption_migration import TEXT_COLUMNS, JSON_COLUMNS
    model_fields = {(table.name, column.name) for table in Base.metadata.tables.values()
                    for column in table.columns if isinstance(column.type, (EncryptedJSON, EncryptedText))}
    migration_fields = {(table, column) for definitions in (TEXT_COLUMNS, JSON_COLUMNS)
                        for table, (_, columns) in definitions.items() for column in columns}
    assert model_fields == migration_fields


def test_cipher_is_randomized_authenticated_and_bound_to_field():
    cipher = DataCipher([Fernet.generate_key().decode()], Fernet.generate_key().decode())
    first = cipher.encrypt("private", "users.name")
    assert first != cipher.encrypt("private", "users.name")
    assert cipher.decrypt(first, "users.name") == "private"
    for token, scope in ((first, "accounts.email"), (first[:-5] + "xxxxx", "users.name")):
        with pytest.raises(DataEncryptionError):
            cipher.decrypt(token, scope)
    assert cipher.lookup("01012345678", "phone") == cipher.lookup("01012345678", "phone")
    assert cipher.lookup("01012345678", "phone") != cipher.lookup("01012345678", "email")


def test_no_builtin_key_for_persistent_storage_and_wrong_key_blocks_startup(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/private.db"
    app = create_app(url)
    app.state.engine.dispose()
    with monkeypatch.context() as change:
        change.setenv("DATA_ENCRYPTION_KEYS", Fernet.generate_key().decode())
        with pytest.raises(DataEncryptionError):
            create_app(url)
    with monkeypatch.context() as change:
        change.setenv("DATA_LOOKUP_KEY", Fernet.generate_key().decode())
        with pytest.raises(DataEncryptionError):
            create_app(url)
    monkeypatch.delenv("DATA_ENCRYPTION_KEYS")
    monkeypatch.delenv("DATA_LOOKUP_KEY")
    with pytest.raises(DataEncryptionError, match="required"):
        build_engine(url)
    # A disposable in-memory application still encrypts its data with temporary keys.
    temporary = create_app()
    temporary.state.engine.dispose()


def test_profile_phone_checkpoint_responses_and_ai2_events_are_ciphertext():
    app = create_app()
    client = authenticated_client(app)
    response = client.post("/api/v1/agent/commands", json=start_payload())
    assert response.status_code == 200
    with app.state.engine.connect() as db:
        for table, column in (("users", "name"), ("accounts", "phone_number")):
            stored = db.execute(text(f"SELECT {column} FROM {table}")).scalar_one()
            assert stored.startswith(PREFIX)
            assert "Test User" not in stored and "010" not in stored[:len(PREFIX)]
        for table, column in (("agent_checkpoints", "state"), ("agent_turns", "response"), ("runtime_events", "payload")):
            stored = db.execute(text(f"SELECT {column} FROM {table}")).scalar_one()
            payload = json.loads(stored) if isinstance(stored, str) else stored
            assert set(payload) == {"encryption", "token"}
            assert payload["encryption"] == MARKER
            assert "디스플레이" not in str(payload)
    with app.state.session_factory() as db:
        assert db.scalar(select(Account)).phone_number.startswith("010")
        assert db.scalar(select(RuntimeEvent)).payload["runtime"]


def test_plaintext_is_never_silently_read_and_key_rotation_preserves_login(tmp_path, monkeypatch):
    from app.core.data_encryption import cipher_for
    from app.models.user import User
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/rotate.db"
    old_key = Fernet.generate_key().decode()
    monkeypatch.setenv("DATA_ENCRYPTION_KEYS", old_key)
    app = create_app(url)
    client = authenticated_client(app)
    with app.state.session_factory() as db:
        account = db.scalar(select(Account))
        phone = account.phone_number
    app.state.engine.dispose()
    new_key = Fernet.generate_key().decode()
    monkeypatch.setenv("DATA_ENCRYPTION_KEYS", new_key + "," + old_key)
    monkeypatch.setenv("DATABASE_URL", url)
    from scripts.encrypt_user_data import main
    monkeypatch.setattr("sys.argv", ["encrypt_user_data.py", "--rotate"])
    main()
    monkeypatch.setenv("DATA_ENCRYPTION_KEYS", new_key)
    current = create_app(url)
    from fastapi.testclient import TestClient
    assert TestClient(current).post("/api/v1/auth/login", json={"phone_number":phone,"password":"1234"}).status_code == 200
    with current.state.engine.begin() as db:
        db.execute(text("UPDATE users SET name='plaintext'"))
    with current.state.session_factory() as db, pytest.raises(DataEncryptionError, match="migration"):
        db.scalar(select(User))


def test_migration_encrypts_existing_data_preserves_credentials_and_refuses_plaintext_downgrade(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path.as_posix()}/migration.db"
    monkeypatch.setenv("DATABASE_URL", url)
    config = Config()
    config.set_main_option("script_location", str(Path(__file__).parents[1] / "migrations"))
    command.upgrade(config, "20261104_17")
    engine = build_engine(url)
    password = hash_password("1234")
    with engine.begin() as db:
        db.execute(text("INSERT INTO users(id,name) VALUES ('legacy','Legacy Name')"))
        db.execute(text("INSERT INTO accounts(user_id,email,phone_number,password_hash) VALUES ('legacy','old@example.com','01012345678',:password)"), {"password":password})
        db.execute(text("INSERT INTO agent_sessions(id,user_id,status,version,goal,outcome_history,created_at,updated_at,expires_at) "
                        "VALUES ('session','legacy','IN_PROGRESS',1,:goal,'[]',100,100,1000)"),
                   {"goal":json.dumps({"private_goal":"legacy request"})})
        db.execute(text("INSERT INTO runtime_events(id,session_id,user_id,episode_id,sequence,request_id,kind,payload,created_at,expires_at) "
                        "VALUES ('event','session','legacy','episode',1,'r','turn',:payload,100,1000)"),
                   {"payload":json.dumps({"private_payload":"legacy event"})})
        db.execute(text("INSERT INTO runtime_event_consumptions(id,event_id,consumer,result,consumed_at) "
                        "VALUES ('receipt','event','test',:result,100)"),
                   {"result":json.dumps({"expires_at":500,"private_result":"legacy analysis"})})
    with pytest.raises(DataEncryptionError, match="migration"):
        create_app(url)
    with pytest.raises(RuntimeError, match="Stop legacy"):
        command.upgrade(config, "head")
    monkeypatch.setenv("DATA_ENCRYPTION_MAINTENANCE", "1")
    command.upgrade(config, "head")
    with engine.connect() as db:
        assert db.execute(text("SELECT name FROM users")).scalar_one().startswith(PREFIX)
        assert db.execute(text("SELECT phone_number FROM accounts")).scalar_one().startswith(PREFIX)
        assert db.execute(text("SELECT password_hash FROM accounts")).scalar_one() == password
        assert "legacy request" not in db.execute(text("SELECT goal FROM agent_sessions")).scalar_one()
        assert "legacy event" not in db.execute(text("SELECT payload FROM runtime_events")).scalar_one()
        assert db.execute(text("SELECT expires_at FROM runtime_event_consumptions")).scalar_one() == 500
        assert db.execute(text("PRAGMA foreign_key_check")).all() == []
    app = create_app(url)
    from fastapi.testclient import TestClient
    response = TestClient(app).post("/api/v1/auth/login", json={"phone_number":"01012345678","password":"1234"})
    assert response.status_code == 200
    assert response.json()["user_id"] == "legacy"
    with app.state.session_factory() as db:
        from app.models.agent_session import AgentSession
        from app.models.runtime_event import RuntimeEventConsumption
        assert db.get(AgentSession,"session").goal == {"private_goal":"legacy request"}
        assert db.get(RuntimeEvent,"event").payload == {"private_payload":"legacy event"}
        assert db.get(RuntimeEventConsumption,"receipt").result["private_result"] == "legacy analysis"
    command.check(config)
    with pytest.raises(RuntimeError, match="cannot be downgraded"):
        command.downgrade(config, "20261104_17")
