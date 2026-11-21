"""Prepare an isolated, shared-DB Settings A/B pilot. Never opens the source database."""
import argparse
import os
from pathlib import Path
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-env', type=Path, default=BACKEND / '.env')
    args = parser.parse_args()
    from dotenv import dotenv_values, set_key
    from cryptography.fernet import Fernet
    if not args.source_env.is_file():
        parser.error('Source environment does not exist')
    source = dotenv_values(args.source_env)
    folder = BACKEND / 'instance/settings-experiment'
    folder.mkdir(parents=True, exist_ok=True)
    target, database = folder / '.env', folder / 'experiment.db'
    previous = dotenv_values(target) if target.exists() else {}
    if database.exists() and not all(previous.get(k) for k in ('DATA_ENCRYPTION_KEYS','DATA_LOOKUP_KEY')):
        parser.error('Existing experiment database requires its original keys')
    provider = source.get('CLICK_SETTINGS_PLANNER_PROVIDER') or previous.get('CLICK_SETTINGS_PLANNER_PROVIDER') or 'openrouter'
    if provider not in {'gemini', 'openrouter'}:
        parser.error('Invalid CLICK_SETTINGS_PLANNER_PROVIDER')
    planner_key = 'OPENROUTER_API_KEY' if provider == 'openrouter' else 'GEMINI_API_KEY'
    allowed = {planner_key,'GEMINI_SETTINGS_MODEL','TYPESAFE_API_KEY','JEV_MODEL','JEV_MIN_CONFIDENCE','JEV_SCREEN_JUDGMENT_MODE'}
    values = {k:v for k,v in source.items() if k in allowed and v is not None}
    values[planner_key] = source.get(planner_key) or ''
    values.setdefault('GEMINI_SETTINGS_MODEL', previous.get('GEMINI_SETTINGS_MODEL') or 'gemini-3.8-flash')
    values.update(CLICK_SETTINGS_PLANNER_PROVIDER=provider, DATABASE_URL='sqlite+pysqlite:///' + database.as_posix(),
        AI1_UI_SELECTOR_PROVIDER='jev', GOAL_CLASSIFIER_PROVIDER='jev',
        AGENT_TURN_TIMEOUT_SECONDS='20', CLICK_SETTINGS_EXPERIMENT_ENABLED='1',
        CLICK_AI2_SETTINGS_GRAPH_ENABLED='1',
        CLICK_SETTINGS_ROUTE_REUSE_ENABLED='0',
        CLICK_AI2_BROWSE_GRAPH_ENABLED='0', GUIDE_NARRATION_ENABLED='false', OPENAI_VERIFIER_ENABLED='0',
        DATA_ENCRYPTION_KEYS=previous.get('DATA_ENCRYPTION_KEYS') or Fernet.generate_key().decode(),
        DATA_LOOKUP_KEY=previous.get('DATA_LOOKUP_KEY') or Fernet.generate_key().decode())
    target.touch(mode=0o600, exist_ok=True)
    for key,value in values.items():
        set_key(target,key,value)
        os.environ[key]=value
    from app.main import app
    from fastapi.testclient import TestClient
    with TestClient(app) as client:
        assert client.get('/health').status_code == 200
    print('PREPARED: instance/settings-experiment/.env; isolated shared DB; backend health OK; device comparison NOT_RUN.')


if __name__ == '__main__':
    main()
