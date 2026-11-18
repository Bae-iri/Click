"""Private S3 backups with a disposable PostgreSQL restore drill.

No application imports, migrations, production writes, or credentials in logs.
Run in the dedicated PostgreSQL 18 backup image, not the API container.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
from uuid import uuid4

from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL, make_url

try:
    from .database_backup import postgres_backup
except ImportError:
    from database_backup import postgres_backup

UTC = timezone.utc
# A one-day margin for the 365-day maximum; hourly explicit deletion supplements
# S3 lifecycle, whose asynchronous expiration has no completion-time guarantee.
RETENTION_DAYS = 364
PUBLIC_BLOCK = ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets")


def settings():
    bucket = os.environ.get("BACKUP_S3_BUCKET", "")
    owner = os.environ.get("BACKUP_S3_EXPECTED_OWNER", "")
    prefix = os.environ.get("BACKUP_S3_PREFIX", "click/postgres/production/")
    if not bucket or len(owner) != 12 or not owner.isdigit():
        raise ValueError("BACKUP_S3_BUCKET and 12-digit BACKUP_S3_EXPECTED_OWNER are required")
    if not prefix.endswith("/") or prefix.startswith("/") or len(prefix.strip("/")) < 8:
        raise ValueError("Use a dedicated backup prefix ending in / (at least 8 characters)")
    return bucket, owner, prefix


def check_bucket(s3, bucket, owner, prefix):
    target = {"Bucket": bucket, "ExpectedBucketOwner": owner}
    block = s3.get_public_access_block(**target)["PublicAccessBlockConfiguration"]
    if not all(block.get(key) is True for key in PUBLIC_BLOCK):
        raise ValueError("All four S3 public access blocks must be enabled")
    if s3.get_bucket_policy_status(**target)["PolicyStatus"]["IsPublic"]:
        raise ValueError("Public bucket policy is forbidden")
    ownership = s3.get_bucket_ownership_controls(**target)["OwnershipControls"]["Rules"]
    if not any(rule.get("ObjectOwnership") == "BucketOwnerEnforced" for rule in ownership):
        raise ValueError("S3 ACLs must be disabled with BucketOwnerEnforced ownership")
    if s3.get_bucket_versioning(**target).get("Status") in {"Enabled", "Suspended"}:
        raise ValueError("Use a new unversioned bucket so expired data cannot survive as old versions")
    encryption = s3.get_bucket_encryption(**target)["ServerSideEncryptionConfiguration"]["Rules"]
    if not any(rule.get("ApplyServerSideEncryptionByDefault", {}).get("SSEAlgorithm") == "AES256" for rule in encryption):
        raise ValueError("Dedicated bucket must use default AES256 encryption")
    rules = s3.get_bucket_lifecycle_configuration(**target)["Rules"]
    if not any(rule.get("Status") == "Enabled"
               and rule.get("Filter") == {"Prefix": prefix}
               and 1 <= rule.get("Expiration", {}).get("Days", 0) <= RETENTION_DAYS
               for rule in rules):
        raise ValueError("An enabled prefix expiration rule of at most 364 days is required")


def prune(s3, bucket, owner, prefix, *, now=None):
    """Delete only objects in the dedicated prefix, including failed-drill files."""
    cutoff = (now or datetime.now(UTC)) - timedelta(days=RETENTION_DAYS)
    deleted = 0
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix, ExpectedBucketOwner=owner):
        expired = [{"Key": obj["Key"]} for obj in page.get("Contents", [])
                   if obj["Key"].startswith(prefix) and obj["LastModified"] <= cutoff]
        for start in range(0, len(expired), 1000):
            batch = expired[start:start + 1000]
            response = s3.delete_objects(Bucket=bucket, ExpectedBucketOwner=owner,
                                         Delete={"Objects": batch, "Quiet": True})
            if response.get("Errors"):
                raise RuntimeError("Expired backup deletion failed; investigate IAM/Object Lock")
            deleted += len(batch)
    return deleted


def inventory(connection):
    """Counts from the very same exported MVCC snapshot used by pg_dump."""
    rows = connection.execute(text("""
        SELECT n.nspname, c.relname FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('r', 'p') AND n.nspname NOT LIKE 'pg_%'
        AND n.nspname <> 'information_schema' ORDER BY n.nspname, c.relname
    """))
    tables = list(rows)
    quote = connection.dialect.identifier_preparer.quote_identifier
    return [{"schema": schema, "table": table,
             "rows": connection.scalar(text(f"SELECT count(*) FROM {quote(schema)}.{quote(table)}"))}
            for schema, table in tables]


def dump_snapshot(source_url, path):
    url = make_url(source_url)
    if url.get_backend_name() != "postgresql":
        raise ValueError("Production backups require PostgreSQL")
    engine = create_engine(url.set(drivername="postgresql+psycopg"), hide_parameters=True)
    try:
        with engine.connect().execution_options(isolation_level="REPEATABLE READ") as conn:
            with conn.begin():
                conn.execute(text("SET TRANSACTION READ ONLY"))
                snapshot = conn.scalar(text("SELECT pg_export_snapshot()"))
                expected = inventory(conn)
                postgres_backup(source_url, path, snapshot=snapshot)
                return expected
    finally:
        engine.dispose()


def command(args, *, env=None, timeout=1800):
    # A server spawned by pg_ctl can inherit pipe handles on Windows and keep
    # communicate() waiting after pg_ctl exits. No tool output should be logged.
    result = subprocess.run(args, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, timeout=timeout)
    if result.returncode:
        # PostgreSQL errors can contain row contents or credentials.
        raise RuntimeError(f"{Path(args[0]).stem} failed; sensitive tool output suppressed")


@contextmanager
def temporary_postgres(root):
    """Start a new server belonging to this run; never connect to a production target."""
    root = Path(root)
    data, log = root / "restore-data", root / "restore.log"
    command(["initdb", "-D", str(data), "-U", "backup_restore", "--encoding=UTF8",
             "--no-locale", "--auth-local=trust", "--auth-host=trust"], timeout=120)
    if os.name == "nt":
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        options = f"-h 127.0.0.1 -p {port}"
        url = URL.create("postgresql+psycopg", username="backup_restore", host="127.0.0.1", port=port, database="postgres")
    else:
        # Only a private Unix socket, no listening TCP port.
        root.chmod(0o700)
        options = f"-c listen_addresses='' -k {root}"
        url = URL.create("postgresql+psycopg", username="backup_restore", database="postgres", query={"host": str(root)})
    try:
        command(["pg_ctl", "-D", str(data), "-l", str(log), "-o", options, "-w", "start"], timeout=120)
        yield url
    finally:
        command(["pg_ctl", "-D", str(data), "-m", "immediate", "-w", "stop"], timeout=120)


def restore_drill(path, expected, workdir):
    with temporary_postgres(workdir) as url:
        # Discard all inherited production libpq settings (including PGOPTIONS,
        # PGSERVICE and TLS settings) before connecting to the local test server.
        env = {k: v for k, v in os.environ.items() if not k.startswith("PG")}
        env.update(PGHOST=url.query.get("host", url.host or "localhost"),
                   PGPORT=str(url.port or 5432), PGUSER=url.username, PGPASSWORD="")
        command(["pg_restore", "--no-password", "--dbname", "postgres",
                 "--no-owner", "--no-privileges", "--exit-on-error", "--single-transaction", str(path)],
                env=env)
        engine = create_engine(url, hide_parameters=True)
        try:
            with engine.connect() as conn:
                actual = inventory(conn)
            if actual != expected:
                raise RuntimeError("Restored table inventory/row counts do not match the backup snapshot")
            return {"status": "passed", "tables": len(actual), "rows": sum(t["rows"] for t in actual),
                    "target": "disposable-local-postgresql-18"}
        finally:
            engine.dispose()


def sha256(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def run_backup(s3, bucket, owner, prefix, source_url):
    started = datetime.now(UTC)
    key = prefix + started.strftime("%Y/%m/%d/%Y%m%dT%H%M%SZ-") + uuid4().hex + ".dump"
    with tempfile.TemporaryDirectory(prefix="click-backup-") as directory:
        root = Path(directory)
        dump, downloaded = root / "source.dump", root / "downloaded.dump"
        expected = dump_snapshot(source_url, dump)
        digest = sha256(dump)
        s3.upload_file(str(dump), bucket, key, ExtraArgs={"ServerSideEncryption": "AES256",
            "ExpectedBucketOwner": owner, "Metadata": {"sha256": digest, "created-at": started.isoformat()}})
        s3.download_file(bucket, key, str(downloaded), ExtraArgs={"ExpectedBucketOwner": owner})
        if sha256(downloaded) != digest:
            raise RuntimeError("Downloaded backup SHA-256 verification failed")
        drill_dir = root / "drill"
        drill_dir.mkdir(mode=0o700)
        report = {"created_at": started.isoformat(), "object_key": key,
                  "sha256": digest, "bytes": dump.stat().st_size, "retention_days": RETENTION_DAYS,
                  "restore": {"status": "failed"}}
        try:
            report["restore"] = restore_drill(downloaded, expected, drill_dir)
        finally:
            s3.put_object(Bucket=bucket, ExpectedBucketOwner=owner, Key=key + ".json",
                          Body=json.dumps(report).encode(), ContentType="application/json", ServerSideEncryption="AES256")
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["backup", "prune", "check"])
    args = parser.parse_args()
    try:
        import boto3
        bucket, owner, prefix = settings()
        s3 = boto3.client("s3")
        check_bucket(s3, bucket, owner, prefix)
        result = {"bucket": bucket, "prefix": prefix, "operation": args.operation, "status": "passed"}
        if args.operation in {"backup", "prune"}:
            result["deleted_expired"] = prune(s3, bucket, owner, prefix)
        if args.operation == "backup":
            result.update(run_backup(s3, bucket, owner, prefix, os.environ["DATABASE_URL"]))
        print(json.dumps(result))
    except Exception as error:
        # No exception repr: DB URLs, SDK responses, and dumped rows are secret.
        print(json.dumps({"operation": args.operation, "status": "failed", "error_type": type(error).__name__}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
