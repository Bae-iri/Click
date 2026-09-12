"""Restore a backup only into an explicitly selected, empty PostgreSQL database."""
import argparse
import os
from pathlib import Path
import subprocess
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("backup")
    args = parser.parse_args()
    raw = os.getenv("RESTORE_DATABASE_URL")
    if not raw or not Path(args.backup).is_file():
        parser.error("Provide an existing backup and RESTORE_DATABASE_URL")
    url = make_url(raw)
    if url.get_backend_name() != "postgresql":
        parser.error("Restore target must be PostgreSQL")
    if os.getenv("DATABASE_URL"):
        current = make_url(os.environ["DATABASE_URL"])
        if (url.host, url.port or 5432, url.database) == (current.host, current.port or 5432, current.database):
            parser.error("Restore target must differ from the application's database")
    engine = create_engine(url)
    try:
        with engine.connect() as connection:
            count = connection.scalar(text("SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog', 'information_schema')"))
            if count:
                parser.error("Restore target must be empty; create a separate database first")
    finally:
        engine.dispose()
    env = dict(os.environ, PGHOST=url.host or "localhost", PGPORT=str(url.port or 5432),
        PGDATABASE=url.database or "", PGUSER=url.username or "", PGPASSWORD=url.password or "")
    result = subprocess.run(["pg_restore", "--dbname", url.database, "--no-owner", "--no-privileges",
        "--single-transaction", "--exit-on-error", str(Path(args.backup).resolve())],
        env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
    if result.returncode:
        raise SystemExit("Restore failed; check tool compatibility and target permissions")
    print("PASS: restore into separate empty database completed; run readiness and acceptance checks before use")


if __name__ == "__main__":
    main()
