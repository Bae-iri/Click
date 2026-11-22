"""Isolated local API with real providers for a USB physical-device audit."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]


def main():
    from cryptography.fernet import Fernet
    from dotenv import dotenv_values
    import uvicorn
    values = dotenv_values(ROOT / 'Backend/.env')
    for key in ('TYPESAFE_API_KEY', 'OPENROUTER_API_KEY', 'JEV_MODEL', 'JEV_MIN_CONFIDENCE',
                'JEV_SCREEN_JUDGMENT_MODE', 'JEV_CHOICE_TEMPERATURE'):
        if values.get(key):
            os.environ[key] = values[key]
    for key in ('TYPESAFE_API_KEY',):
        if not os.environ.get(key):
            raise RuntimeError('Required audit provider is not configured: '+key)
    with tempfile.TemporaryDirectory(prefix='click-device-audit-', dir='/private/tmp') as directory:
        os.environ.update(
            DATABASE_URL='sqlite+pysqlite:///'+str(Path(directory)/'audit.db'),
            DATA_ENCRYPTION_KEYS=Fernet.generate_key().decode(), DATA_LOOKUP_KEY=Fernet.generate_key().decode(),
            AI1_UI_SELECTOR_PROVIDER='jev', GOAL_CLASSIFIER_PROVIDER='jev',
            APP_TASK_PROVIDER='openrouter', APP_TASK_OPENROUTER_COMPACT='1',
            CLICK_SETTINGS_PLANNER_PROVIDER='openrouter', APP_TASK_PROVIDER_CAPACITY='6',
            CLICK_REQUIRE_EXECUTION_CONTEXT='1', CLICK_GOV24_GUIDE_ENABLED='1', CLICK_COUPANG_BROWSE_ENABLED='1',
            CLICK_AI2_BROWSE_GRAPH_ENABLED='0', CLICK_VALIDATION_DIAGNOSTICS='0',
            RENDER_GIT_COMMIT=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip())
        print('LOCAL_DEVICE_AUDIT_API http://127.0.0.1:8765; isolated temporary database; real Jev; app planner configured=' + str(bool(os.environ.get('OPENROUTER_API_KEY'))),flush=True)
        uvicorn.run('app.main:app',host='127.0.0.1',port=8765,access_log=False,log_level='warning')


if __name__ == '__main__':
    main()
