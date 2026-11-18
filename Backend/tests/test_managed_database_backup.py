"""Backup safety tests; real-server drill is opt-in and never uses DATABASE_URL."""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import sys
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from sqlalchemy import create_engine, text

from scripts import database_backup as raw
from scripts import managed_database_backup as backup


OWNER = "123456789012"
PREFIX = "click/postgres/production/"


def safe_client():
    client = Mock()
    client.get_public_access_block.return_value = {"PublicAccessBlockConfiguration": {k: True for k in backup.PUBLIC_BLOCK}}
    client.get_bucket_policy_status.return_value = {"PolicyStatus": {"IsPublic": False}}
    client.get_bucket_ownership_controls.return_value = {"OwnershipControls": {"Rules": [{"ObjectOwnership": "BucketOwnerEnforced"}]}}
    client.get_bucket_versioning.return_value = {}
    client.get_bucket_encryption.return_value = {"ServerSideEncryptionConfiguration": {"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]}}
    client.get_bucket_lifecycle_configuration.return_value = {"Rules": [{"Status": "Enabled", "Filter": {"Prefix": PREFIX}, "Expiration": {"Days": 364}}]}
    return client


def test_fail_closed_when_public_or_recoverable_versions_exist():
    for field in backup.PUBLIC_BLOCK:
        s3 = safe_client()
        s3.get_public_access_block.return_value["PublicAccessBlockConfiguration"][field] = False
        with pytest.raises(ValueError):
            backup.check_bucket(s3, "bucket", OWNER, PREFIX)
    for status in ("Enabled", "Suspended"):
        s3 = safe_client()
        s3.get_bucket_versioning.return_value = {"Status": status}
        with pytest.raises(ValueError):
            backup.check_bucket(s3, "bucket", OWNER, PREFIX)
    s3 = safe_client()
    s3.get_bucket_policy_status.return_value = {"PolicyStatus": {"IsPublic": True}}
    with pytest.raises(ValueError):
        backup.check_bucket(s3, "bucket", OWNER, PREFIX)


@pytest.mark.parametrize("rule", [
    {"Status": "Disabled", "Filter": {"Prefix": PREFIX}, "Expiration": {"Days": 364}},
    {"Status": "Enabled", "Filter": {"Prefix": "other/"}, "Expiration": {"Days": 364}},
    {"Status": "Enabled", "Filter": {"Prefix": PREFIX}, "Expiration": {"Days": 365}},
])
def test_retention_must_cover_exact_prefix_with_safety_margin(rule):
    s3 = safe_client()
    s3.get_bucket_lifecycle_configuration.return_value = {"Rules": [rule]}
    with pytest.raises(ValueError):
        backup.check_bucket(s3, "bucket", OWNER, PREFIX)


def test_prune_boundary_pagination_and_scope():
    now = datetime(2026, 11, 18, tzinfo=timezone.utc)
    cutoff = now - timedelta(days=364)
    s3 = safe_client()
    s3.get_paginator.return_value.paginate.return_value = [
        {"Contents": [
            {"Key": PREFIX + "old.dump", "LastModified": cutoff},
            {"Key": PREFIX + "fresh.dump", "LastModified": cutoff + timedelta(seconds=1)},
            {"Key": "other/old.dump", "LastModified": cutoff},
        ]},
        {"Contents": [{"Key": PREFIX + "old.dump.json", "LastModified": cutoff - timedelta(days=1)}]},
    ]
    s3.delete_objects.return_value = {}
    assert backup.prune(s3, "bucket", OWNER, PREFIX, now=now) == 2
    assert [c.kwargs["Delete"]["Objects"] for c in s3.delete_objects.call_args_list] == [
        [{"Key": PREFIX + "old.dump"}], [{"Key": PREFIX + "old.dump.json"}]]
    s3.delete_objects.return_value = {"Errors": [{"Code": "AccessDenied"}]}
    with pytest.raises(RuntimeError, match="deletion failed"):
        backup.prune(s3, "bucket", OWNER, PREFIX, now=now)


def test_pg_dump_keeps_tls_and_hides_credentials_in_process_args(monkeypatch, tmp_path):
    runner = Mock(return_value=SimpleNamespace(returncode=0))
    monkeypatch.setattr(raw.subprocess, "run", runner)
    url = "postgresql+psycopg://reader:p%40ss@db.example/prod?sslmode=verify-full"
    raw.postgres_backup(url, tmp_path / "db.dump", snapshot="snapshot-id")
    args, kwargs = runner.call_args
    assert kwargs["env"]["PGSSLMODE"] == "verify-full"
    assert kwargs["env"]["PGDATABASE"] == "prod"
    assert kwargs["env"]["PGPASSWORD"] == "p@ss"
    assert "p%40ss" not in str(args)
    assert args[0][-2:] == ["--snapshot", "snapshot-id"]


def test_failed_drill_records_failure_and_never_reports_success(monkeypatch, tmp_path):
    s3 = safe_client()
    def dump(_, path):
        path.write_bytes(b"backup")
        return []
    monkeypatch.setattr(backup, "dump_snapshot", dump)
    s3.download_file.side_effect = lambda bucket, key, path, **kw: Path(path).write_bytes(b"backup")
    monkeypatch.setattr(backup, "restore_drill", Mock(side_effect=RuntimeError("failure")))
    with pytest.raises(RuntimeError):
        backup.run_backup(s3, "bucket", OWNER, PREFIX, "not-used")
    report = json.loads(s3.put_object.call_args.kwargs["Body"])
    assert report["restore"]["status"] == "failed"
    assert report["retention_days"] == 364


def test_corrupt_download_never_reaches_restore(monkeypatch):
    s3 = safe_client()
    monkeypatch.setattr(backup, "dump_snapshot", lambda url, path: (path.write_bytes(b"original"), [])[1])
    s3.download_file.side_effect = lambda bucket, key, path, **kw: Path(path).write_bytes(b"corrupt")
    drill = Mock()
    monkeypatch.setattr(backup, "restore_drill", drill)
    with pytest.raises(RuntimeError, match="SHA-256"):
        backup.run_backup(s3, "bucket", OWNER, PREFIX, "not-used")
    drill.assert_not_called()


@pytest.mark.skipif(os.environ.get("CLICK_BACKUP_POSTGRES_TEST") != "1", reason="Explicit opt-in; requires local PostgreSQL 18 binaries")
def test_real_postgres_dump_s3_roundtrip_restore(tmp_path):
    """Two fresh PG18 servers + simulated S3. No production credentials read."""
    boto3 = pytest.importorskip("boto3")
    moto = pytest.importorskip("moto")
    assert all(shutil.which(tool) for tool in ("pg_dump", "pg_restore", "initdb", "pg_ctl"))
    source_dir = tmp_path / "source"
    source_dir.mkdir()
    with backup.temporary_postgres(source_dir) as source:
        # Restore the actual Click schema as well as representative typed data.
        env = {**os.environ, "DATABASE_URL": source.render_as_string(hide_password=False)}
        backup.command([sys.executable, "-m", "alembic", "upgrade", "head"], env=env)
        engine = create_engine(source)
        with engine.begin() as conn:
            conn.execute(text('CREATE TABLE parent (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, label text NOT NULL)'))
            conn.execute(text("INSERT INTO parent(label) VALUES ('정부24 테스트'), ('encrypted-placeholder')"))
            conn.execute(text('CREATE TABLE child (id bigint PRIMARY KEY, parent_id bigint REFERENCES parent(id), payload jsonb)'))
            conn.execute(text('INSERT INTO child VALUES (1, 1, \'{"status":"ok"}\')'))
            conn.execute(text('CREATE SCHEMA "odd schema"'))
            conn.execute(text('CREATE TABLE "odd schema"."quoted""name" (value bytea)'))
            conn.execute(text('INSERT INTO "odd schema"."quoted""name" VALUES (decode(\'000102ff\', \'hex\'))'))
        try:
            with moto.mock_aws():
                s3 = boto3.client("s3", region_name="ap-southeast-1")
                bucket = "click-backup-test-only"
                s3.create_bucket(Bucket=bucket, CreateBucketConfiguration={"LocationConstraint": "ap-southeast-1"})
                s3.put_public_access_block(Bucket=bucket, PublicAccessBlockConfiguration={k: True for k in backup.PUBLIC_BLOCK})
                s3.put_bucket_ownership_controls(Bucket=bucket, OwnershipControls={"Rules": [{"ObjectOwnership": "BucketOwnerEnforced"}]})
                s3.put_bucket_encryption(Bucket=bucket, ServerSideEncryptionConfiguration={"Rules": [{"ApplyServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]})
                s3.put_bucket_lifecycle_configuration(Bucket=bucket, LifecycleConfiguration={"Rules": [{"ID": "expire", "Status": "Enabled", "Filter": {"Prefix": PREFIX}, "Expiration": {"Days": 364}}]})
                # Moto cannot evaluate aws:SecureTransport correctly. Keep the
                # emulator policy private; the real TLS deny is in Terraform.
                s3.put_bucket_policy(Bucket=bucket, Policy=json.dumps({"Version": "2012-10-17", "Statement": []}))
                backup.check_bucket(s3, bucket, OWNER, PREFIX)
                report = backup.run_backup(s3, bucket, OWNER, PREFIX, source.render_as_string(hide_password=False))
                with engine.connect() as conn:
                    expected = backup.inventory(conn)
                assert report["restore"] == {"status": "passed", "tables": len(expected),
                    "rows": sum(t["rows"] for t in expected), "target": "disposable-local-postgresql-18"}
                stored = json.loads(s3.get_object(Bucket=bucket, Key=report["object_key"] + ".json")["Body"].read())
                assert stored == report
                assert s3.head_object(Bucket=bucket, Key=report["object_key"])["ServerSideEncryption"] == "AES256"
                print("RESTORE_EVIDENCE", json.dumps(report))
        finally:
            engine.dispose()
