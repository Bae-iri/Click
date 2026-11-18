"""Prepare/check an isolated backend paired with the validation APK; never use the production DB."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))
INSTANCE = BACKEND / 'instance/validation'


def prepare(env_file, port, *, coupang_browse=False, gov24=False):
    from dotenv import dotenv_values, set_key
    from cryptography.fernet import Fernet
    from app.core.data_encryption import DataCipher
    if not 1 <= port <= 65535:
        raise ValueError('Invalid validation port')
    INSTANCE.mkdir(parents=True, exist_ok=True)
    values = dict(dotenv_values(env_file))
    if coupang_browse:
        values['CLICK_COUPANG_BROWSE_ENABLED'] = '1'
    if gov24:
        values['CLICK_GOV24_GUIDE_ENABLED'] = '1'
    target = INSTANCE / '.env'
    previous = dotenv_values(target) if target.exists() else {}
    values.update(DATABASE_URL='sqlite+pysqlite:///' + (INSTANCE / 'validation.db').as_posix(),
        CLICK_VALIDATION_ENVIRONMENT_ID=previous.get('CLICK_VALIDATION_ENVIRONMENT_ID') or secrets.token_hex(16),
        CLICK_VALIDATION_DIAGNOSTICS='1',
        OPS_TOKEN=previous.get('OPS_TOKEN') or secrets.token_urlsafe(48),
        DATA_ENCRYPTION_KEYS=previous.get('DATA_ENCRYPTION_KEYS') or Fernet.generate_key().decode(),
        DATA_LOOKUP_KEY=previous.get('DATA_LOOKUP_KEY') or Fernet.generate_key().decode())
    if not target.exists():
        target.touch()
    for key, value in values.items():
        if value is not None:
            set_key(target, key, value)
    child_env = {**os.environ, **{k: v for k, v in values.items() if v is not None}}
    subprocess.run([sys.executable, '-m', 'alembic', 'upgrade', 'head'],
                   cwd=BACKEND, env=child_env, check=True)
    from app.database import build_engine, build_session_factory
    from app import models  # noqa: F401
    from app.repositories.task_graphs import TaskGraphRepository, canonical_graph
    from app.task_graphs import approved_graphs
    from app.core.validation import environment_manifest
    from scripts.agent2 import audit_catalog
    engine = build_engine(values['DATABASE_URL'], data_cipher=DataCipher(values['DATA_ENCRYPTION_KEYS'].split(','), values['DATA_LOOKUP_KEY']))
    try:
        factory = build_session_factory(engine)
        TaskGraphRepository(factory).seed_mvp()
        manifest = environment_manifest(factory, values['CLICK_VALIDATION_ENVIRONMENT_ID'], runtime_settings=values)
        expected = {graph.definition.task_id: canonical_graph(graph)[1] for graph in approved_graphs().values()}
        actual = {row['task_id']: row['sha256'] for row in manifest['approved_graphs']}
        if not expected or actual != expected:
            raise ValueError('Validation must use exactly the current approved seed graphs')
        manifest['api_base_url'] = f'http://127.0.0.1:{port}'
        (INSTANCE / 'environment.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        audit = audit_catalog(factory)
        (INSTANCE / 'graph-audit.json').write_text(json.dumps(audit, indent=2) + '\n', encoding='utf-8')
    finally:
        engine.dispose()
    print(json.dumps({'status': 'PREPARED', 'api_base_url': manifest['api_base_url'],
        'environment_id': manifest['environment_id'], 'fingerprint': manifest['fingerprint'],
        'migrations': manifest['database_migrations'], 'approved_graphs': len(actual),
        'device_validation': 'NOT_RUN'}))


def check():
    import httpx
    expected = json.loads((INSTANCE / 'environment.json').read_text(encoding='utf-8'))
    with httpx.Client(trust_env=False, follow_redirects=False, timeout=5) as client:
        base = expected['api_base_url']
        for path in ('/health', '/ready'):
            if client.get(base + path).status_code != 200:
                raise ValueError('Validation backend is not ready')
        response = client.get(base + '/validation/environment')
        if response.status_code != 200 or response.json().get('fingerprint') != expected['fingerprint']:
            raise ValueError('Backend differs from the paired manifest; prepare/restart/rebuild together')
    print('MATCHED: backend code, migrations and approved graph hashes match the APK build manifest. Device flow NOT_RUN.')


def trace(session):
    from app.core.validation import digest
    wanted = digest(session)
    path = INSTANCE / 'api.stderr.log'
    count = 0
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        if 'click.validation ' not in line or len(line) > 65536:
            continue
        try:
            record = json.loads(line[line.index('{'):])
        except (ValueError, TypeError):
            continue
        if record.get('schema_version') == 'click-validation-turn.v1' and record.get('session_id_hash') == wanted:
            print(json.dumps(record, ensure_ascii=True))
            count += 1
    print(json.dumps({'matched_turns': count, 'evidence': 'diagnostics_only_not_independent_ground_truth'}))


def main():
    global INSTANCE
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare_cmd = commands.add_parser('prepare')
    prepare_cmd.add_argument('--env-file', type=Path, default=BACKEND / '.env')
    prepare_cmd.add_argument('--port', type=int, default=8001)
    prepare_cmd.add_argument('--coupang-browse', action='store_true')
    prepare_cmd.add_argument('--gov24', action='store_true')
    parser.add_argument('--instance', type=Path, default=INSTANCE)
    commands.add_parser('check')
    commands.add_parser('serve', help='Serve the isolated paired instance on localhost')
    trace_cmd = commands.add_parser('trace')
    trace_cmd.add_argument('--session', required=True)
    args = parser.parse_args()
    INSTANCE = args.instance.resolve()
    if not INSTANCE.is_relative_to((BACKEND / 'instance').resolve()):
        parser.error('Instance must be inside Backend/instance')
    try:
        if args.command == 'prepare':
            if not args.env_file.is_file():
                raise ValueError('Source environment file does not exist')
            prepare(args.env_file, args.port, coupang_browse=args.coupang_browse, gov24=args.gov24)
        elif args.command == 'check':
            check()
        elif args.command == 'serve':
            from dotenv import dotenv_values
            from urllib.parse import urlparse
            import uvicorn
            settings = dotenv_values(INSTANCE / '.env')
            expected = json.loads((INSTANCE / 'environment.json').read_text(encoding='utf-8'))
            address = urlparse(expected['api_base_url'])
            if address.hostname != '127.0.0.1' or not address.port:
                raise ValueError('Only a local validation server may be started')
            os.environ.update({k: v for k, v in settings.items() if v is not None})
            # Windows venv launchers may have a different PID from the actual
            # serving interpreter. Let the helper manage the real server only.
            (INSTANCE / 'server-runtime.json').write_text(json.dumps({
                'pid': os.getpid(), 'fingerprint': expected['fingerprint']}), encoding='utf-8')
            uvicorn.run('app.main:create_app', factory=True, host='127.0.0.1', port=address.port)
        else:
            trace(args.session)
        return 0
    except Exception as error:
        print('VALIDATION_ERROR: ' + type(error).__name__ + '. Inspect local configuration and service logs.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
