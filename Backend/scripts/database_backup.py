"""Backup/restore without overwriting an existing SQLite destination."""
import argparse
import os
from pathlib import Path
import sqlite3
import subprocess
from contextlib import closing
from sqlalchemy.engine import make_url


def sqlite_copy(source, destination):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if not source.is_file() or destination.exists():
        raise ValueError("Source must exist and destination must be new")
    # Exclusive creation prevents accidental replacement, including races.
    with destination.open("xb"):
        pass
    try:
        with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as src:
            with closing(sqlite3.connect(destination)) as dst:
                src.backup(dst)
                if dst.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise ValueError("Backup integrity check failed")
                if dst.execute("PRAGMA foreign_key_check").fetchone():
                    raise ValueError("Backup foreign key check failed")
    except Exception:
        destination.unlink(missing_ok=True)
        raise


def postgres_backup(url, destination):
    parsed = make_url(url)
    env = dict(os.environ, PGHOST=parsed.host or "localhost", PGPORT=str(parsed.port or 5432),
        PGDATABASE=parsed.database or "", PGUSER=parsed.username or "", PGPASSWORD=parsed.password or "")
    path = Path(destination).resolve()
    with path.open("xb") as output:
        try:
            result = subprocess.run(["pg_dump", "--format=custom", "--no-owner"], env=env,
                stdout=output, stderr=subprocess.PIPE, timeout=300)
            if result.returncode:
                raise RuntimeError("Backup failed")
        except Exception as error:
            output.close()
            path.unlink(missing_ok=True)
            raise RuntimeError("PostgreSQL backup failed; check connectivity and tool compatibility") from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=["backup", "restore-sqlite"])
    parser.add_argument("path")
    parser.add_argument("--new-database")
    args = parser.parse_args()
    if args.operation == "restore-sqlite":
        if not args.new_database:
            parser.error("--new-database is required; existing databases are never overwritten")
        sqlite_copy(args.path, args.new_database)
    else:
        if not os.getenv("DATABASE_URL"):
            parser.error("DATABASE_URL is required")
        url = make_url(os.environ["DATABASE_URL"])
        if url.drivername.startswith("sqlite"):
            sqlite_copy(url.database, args.path)
        elif url.drivername.startswith("postgresql"):
            postgres_backup(os.environ["DATABASE_URL"], args.path)
        else:
            parser.error("Unsupported database")
    print("PASS: database copy completed")


if __name__ == "__main__":
    main()
