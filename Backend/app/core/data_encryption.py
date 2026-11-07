"""Authenticated encryption at the database boundary; keys never live in the DB."""
import base64
import hashlib
import hmac
import json
import os

from cryptography.fernet import Fernet, InvalidToken, MultiFernet
from sqlalchemy import JSON, Text, table, column, Integer, String
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.types import TypeDecorator

PREFIX = "clickenc:v1:"
MARKER = "click.fernet.v1"


class DataEncryptionError(ValueError):
    pass


def _key(value):
    try:
        encoded = value.encode("ascii")
        decoded = base64.b64decode(encoded, altchars=b"-_", validate=True)
        if len(decoded) != 32:
            raise ValueError()
        return encoded, decoded
    except (ValueError, UnicodeError, AttributeError):
        raise DataEncryptionError("Data encryption keys must be URL-safe base64 encoded 32-byte secrets") from None


class DataCipher:
    def __init__(self, keys, lookup_key):
        if not keys:
            raise DataEncryptionError("DATA_ENCRYPTION_KEYS and DATA_LOOKUP_KEY are required")
        encoded = [_key(key)[0] for key in keys]
        self._lookup = _key(lookup_key)[1]
        if any(hmac.compare_digest(self._lookup, _key(key)[1]) for key in keys):
            raise DataEncryptionError("Use a separate DATA_LOOKUP_KEY")
        self._cipher = MultiFernet([Fernet(key) for key in encoded])

    @classmethod
    def from_environment(cls, *, ephemeral=False):
        keys = os.getenv("DATA_ENCRYPTION_KEYS", "")
        lookup = os.getenv("DATA_LOOKUP_KEY", "")
        if not keys and not lookup and ephemeral:
            return cls([Fernet.generate_key().decode()], Fernet.generate_key().decode())
        if not keys or not lookup:
            raise DataEncryptionError("DATA_ENCRYPTION_KEYS and DATA_LOOKUP_KEY are required for persistent storage")
        return cls([key.strip() for key in keys.split(",")], lookup.strip())

    def encrypt(self, value, scope):
        data = json.dumps({"field": scope, "value": value}, ensure_ascii=False,
                          separators=(",", ":"), allow_nan=False).encode()
        return self._cipher.encrypt(data).decode("ascii")

    def decrypt(self, token, scope):
        try:
            data = json.loads(self._cipher.decrypt(token.encode("ascii")))
            if set(data) != {"field", "value"} or data["field"] != scope:
                raise ValueError()
            return data["value"]
        except (InvalidToken, ValueError, TypeError, UnicodeError, AttributeError):
            raise DataEncryptionError("Stored data cannot be authenticated with the configured keys") from None

    def lookup(self, value, scope):
        if value is None:
            return None
        return hmac.new(self._lookup, (scope + "\0" + value).encode(), hashlib.sha256).hexdigest()


def cipher_for(dialect):
    if not hasattr(dialect, "_click_data_cipher"):
        dialect._click_data_cipher = DataCipher.from_environment()
    return dialect._click_data_cipher


def validate_database_keys(engine, *, initialize=False):
    """Refuse an unrelated encryption/lookup key before login or user reads."""
    state = table("data_protection_state", column("id", Integer), column("payload", JSON), column("lookup_check", String))
    cipher = cipher_for(engine.dialect)
    with engine.begin() as connection:
        row = connection.execute(state.select().where(state.c.id == 1)).mappings().first()
        if row is None:
            if not initialize:
                raise DataEncryptionError("Data encryption migration must complete before serving requests")
            payload = {"encryption": MARKER, "token": cipher.encrypt("configured", "data_protection_state.payload")}
            connection.execute(state.insert().values(id=1, payload=payload,
                lookup_check=cipher.lookup("configured", "data_protection_state.lookup_check")))
            return
        payload = row["payload"]
        if (not isinstance(payload, dict) or payload.get("encryption") != MARKER
                or cipher.decrypt(payload.get("token"), "data_protection_state.payload") != "configured"
                or not hmac.compare_digest(row["lookup_check"], cipher.lookup("configured", "data_protection_state.lookup_check"))):
            raise DataEncryptionError("Configured keys do not match this database")


class EncryptedText(TypeDecorator):
    impl = Text
    cache_ok = True

    def __init__(self, scope):
        self.scope = scope
        super().__init__()

    def process_bind_param(self, value, dialect):
        return PREFIX + cipher_for(dialect).encrypt(value, self.scope) if value is not None else None

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        if not isinstance(value, str) or not value.startswith(PREFIX):
            raise DataEncryptionError("Unencrypted stored data requires the encryption migration")
        return cipher_for(dialect).decrypt(value[len(PREFIX):], self.scope)


class EncryptedJSON(TypeDecorator):
    impl = JSON().with_variant(JSONB(), "postgresql")
    cache_ok = True

    def __init__(self, scope):
        self.scope = scope
        super().__init__()

    def load_dialect_impl(self, dialect):
        legacy_json = {"agent_sessions.workflow", "agent_sessions.recovery", "runtime_events.payload", "runtime_event_consumptions.result"}
        return dialect.type_descriptor(JSONB() if dialect.name == "postgresql" and self.scope not in legacy_json else JSON())

    def process_bind_param(self, value, dialect):
        return {"encryption": MARKER, "token": cipher_for(dialect).encrypt(value, self.scope)} if value is not None else None

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        if not isinstance(value, dict) or set(value) != {"encryption", "token"} or value["encryption"] != MARKER:
            raise DataEncryptionError("Unencrypted stored data requires the encryption migration")
        return cipher_for(dialect).decrypt(value["token"], self.scope)
