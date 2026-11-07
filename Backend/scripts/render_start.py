"""Adapt Render's managed DB URL to psycopg3; no secrets printed."""
import os
from pathlib import Path
import sys

from sqlalchemy.engine import make_url


def database_url(raw):
    url = make_url(raw)
    if url.drivername not in {"postgres", "postgresql", "postgresql+psycopg"}:
        raise ValueError("Render deployment requires a PostgreSQL database")
    return url.set(drivername="postgresql+psycopg").render_as_string(hide_password=False)


def main():
    raw = os.environ.get("DATABASE_URL")
    if not raw:
        raise RuntimeError("DATABASE_URL is required for deployment")
    os.environ["DATABASE_URL"] = database_url(raw)
    from app.core.data_encryption import DataCipher
    DataCipher.from_environment()
    os.chdir(Path(__file__).resolve().parents[1])
    if "--migrate" in sys.argv:
        args = [sys.executable, "-m", "alembic", "upgrade", "head"]
    else:
        port = int(os.environ.get("PORT", "8000"))
        if not 1 <= port <= 65535:
            raise ValueError("Invalid PORT")
        args = [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0",
                "--port", str(port), "--workers", "1", "--no-access-log", "--no-proxy-headers",
                "--log-config", "logging.json"]
    os.execv(sys.executable, args)


if __name__ == "__main__":
    main()
