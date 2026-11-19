"""Prepare a separate local pilot database/environment; never opens the source DB."""
import argparse
import os
from pathlib import Path
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent' / 'src'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-env', type=Path, default=BACKEND / '.env')
    args = parser.parse_args()
    from dotenv import dotenv_values, set_key
    from cryptography.fernet import Fernet
    if not args.source_env.is_file():
        parser.error('Source environment does not exist')
    source = dotenv_values(args.source_env)
    folder = BACKEND / 'instance' / 'gov24-planner'
    folder.mkdir(parents=True, exist_ok=True)
    target, database = folder / '.env', folder / 'pilot.db'
    previous = dotenv_values(target) if target.exists() else {}
    if database.exists() and not all(previous.get(k) for k in ('DATA_ENCRYPTION_KEYS', 'DATA_LOOKUP_KEY')):
        parser.error('Existing pilot DB requires its original encryption keys')
    values = {k: v for k, v in source.items() if k in {'GEMINI_API_KEY', 'TYPESAFE_API_KEY', 'JEV_MODEL',
              'JEV_MIN_CONFIDENCE'} or k.startswith('KAKAO_')}
    values.update(DATABASE_URL='sqlite+pysqlite:///' + database.as_posix(),
        CLICK_GOV24_GUIDE_ENABLED='1', CLICK_GOV24_PLANNER_ENABLED='1', CLICK_AI2_BROWSE_GRAPH_ENABLED='0',
        DATA_ENCRYPTION_KEYS=previous.get('DATA_ENCRYPTION_KEYS') or Fernet.generate_key().decode(),
        DATA_LOOKUP_KEY=previous.get('DATA_LOOKUP_KEY') or Fernet.generate_key().decode())
    target.touch(exist_ok=True)
    for key, value in values.items():
        if value is not None:
            set_key(target, key, value)
            os.environ[key] = value
    from app.main import app
    try:
        from fastapi.testclient import TestClient
        with TestClient(app) as client:
            if client.get('/health').status_code != 200:
                raise RuntimeError('Pilot backend health check failed')
        print('PREPARED: instance/gov24-planner/.env and isolated pilot.db. Backend health: OK. Device flow: NOT_RUN.')
    finally:
        app.state.engine.dispose()


if __name__ == '__main__':
    main()
