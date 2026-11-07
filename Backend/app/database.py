from __future__ import annotations

import os
from collections.abc import Iterator

from sqlalchemy import create_engine, event
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from sqlalchemy.pool import StaticPool


class Base(DeclarativeBase):
    pass


def build_engine(database_url: str | None = None, *, data_cipher=None):
    url = database_url or os.getenv("DATABASE_URL", "sqlite+pysqlite:///:memory:")
    from app.core.data_encryption import DataCipher
    cipher = data_cipher or DataCipher.from_environment(ephemeral=url in {"sqlite+pysqlite:///:memory:", "sqlite:///:memory:"})
    kwargs: dict = {"pool_pre_ping": True, "hide_parameters": True}
    if url == "sqlite+pysqlite:///:memory:":
        kwargs.update(
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
    elif url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False}
    engine = create_engine(url, **kwargs)
    engine.dialect._click_data_cipher = cipher
    if url.startswith("sqlite"):
        @event.listens_for(engine, "connect")
        def enable_sqlite_foreign_keys(dbapi_connection, _connection_record) -> None:
            cursor = dbapi_connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()
    return engine


def build_session_factory(engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False)


def session_scope(factory: sessionmaker[Session]) -> Iterator[Session]:
    session = factory()
    try:
        yield session
    finally:
        session.close()
