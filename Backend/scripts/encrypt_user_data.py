"""Check encrypted storage or rotate it in place; never print secrets or user content."""
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.database import build_engine
from app.core.data_encryption import MARKER, cipher_for, validate_database_keys
from app.core.encryption_migration import protect_data
from scripts.render_start import database_url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rotate", action="store_true", help="Rewrite ciphertext using the first configured key; keep old keys during rollout")
    args = parser.parse_args()
    raw = os.environ.get("DATABASE_URL")
    if not raw:
        parser.error("DATABASE_URL is required")
    engine = build_engine(database_url(raw) if raw.startswith(("postgres://", "postgresql://")) else raw)
    try:
        validate_database_keys(engine)
        cipher = cipher_for(engine.dialect)
        with engine.begin() as connection:
            result = protect_data(connection, cipher, check_only=not args.rotate, rotate=args.rotate)
            if result.get("plaintext_values"):
                raise RuntimeError("Unencrypted data remains; complete the encryption migration")
            if args.rotate:
                import sqlalchemy as sa
                state = sa.table("data_protection_state", sa.column("id", sa.Integer()), sa.column("payload", sa.JSON()))
                connection.execute(state.update().where(state.c.id == 1).values(payload={
                    "encryption": MARKER, "token": cipher.encrypt("configured", "data_protection_state.payload")}))
        print(json.dumps({"status": "encrypted", "rotated": args.rotate, **result}))
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
