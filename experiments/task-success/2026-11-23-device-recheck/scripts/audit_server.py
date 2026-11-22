"""Windows launcher for serve_device_audit.py with per-turn timing (no repo edits).

Top-level patches are re-applied in spawned runtime children because spawn re-imports
this module as __mp_main__.
"""
import json
import os
from pathlib import Path
import sys
import time

WT = Path(os.environ['CLICK_WT'])
sys.path[:0] = [str(WT / 'Backend'), str(WT / 'AI-Agent/src')]
LOG = Path(os.environ['CLICK_TIMING_LOG'])


def log(**row):
    row['t'] = round(time.time(), 3)
    row['pid'] = os.getpid()
    with LOG.open('a', encoding='utf8') as f:
        f.write(json.dumps(row, ensure_ascii=False) + '\n')


def patch():
    import httpx
    send = httpx.Client.send

    def timed_send(self, request, *a, **k):
        s = time.monotonic()
        status = None
        try:
            r = send(self, request, *a, **k)
            status = r.status_code
            return r
        finally:
            if '127.0.0.1' not in str(request.url):
                log(kind='http', host=request.url.host, path=request.url.path,
                    status=status, ms=round(1000 * (time.monotonic() - s)))
    httpx.Client.send = timed_send

    from app.clients import runtime_work
    call = runtime_work.RuntimeWork.__call__

    def timed_call(self):
        s = time.monotonic()
        try:
            return call(self)
        finally:
            log(kind='runtime_work', command=getattr(self.request, 'command_type', None),
                ms=round(1000 * (time.monotonic() - s)))
    runtime_work.RuntimeWork.__call__ = timed_call


patch()


def main():
    from cryptography.fernet import Fernet
    from dotenv import dotenv_values
    import tempfile
    import subprocess
    import uvicorn
    from app.clients import runtime_process
    run = runtime_process.run_runtime

    def timed_run(op, timeout):
        s = time.monotonic()
        status = 'ok'
        try:
            return run(op, timeout)
        except Exception as e:
            status = getattr(e, 'status_code', type(e).__name__)
            raise
        finally:
            log(kind='run_runtime', command=getattr(op.request, 'command_type', None), status=status,
                timeout=round(timeout, 1), ms=round(1000 * (time.monotonic() - s)))
    runtime_process.run_runtime = timed_run
    import app.services.agent_turn as at
    if hasattr(at, 'run_runtime'):
        at.run_runtime = timed_run

    values = dotenv_values(Path(os.environ['CLICK_ENV_FILE']))
    for key in ('TYPESAFE_API_KEY', 'OPENROUTER_API_KEY', 'JEV_MODEL', 'JEV_MIN_CONFIDENCE',
                'JEV_SCREEN_JUDGMENT_MODE', 'JEV_CHOICE_TEMPERATURE'):
        if values.get(key):
            os.environ[key] = values[key]
    if not os.environ.get('TYPESAFE_API_KEY'):
        raise RuntimeError('TYPESAFE_API_KEY missing')
    with tempfile.TemporaryDirectory(prefix='click-device-audit-') as directory:
        os.environ.update(
            DATABASE_URL='sqlite+pysqlite:///' + str(Path(directory) / 'audit.db').replace('\\', '/'),
            DATA_ENCRYPTION_KEYS=Fernet.generate_key().decode(), DATA_LOOKUP_KEY=Fernet.generate_key().decode(),
            AI1_UI_SELECTOR_PROVIDER='jev', GOAL_CLASSIFIER_PROVIDER='jev',
            APP_TASK_PROVIDER='openrouter', APP_TASK_OPENROUTER_COMPACT='1',
            CLICK_SETTINGS_PLANNER_PROVIDER='openrouter', APP_TASK_PROVIDER_CAPACITY='6',
            CLICK_REQUIRE_EXECUTION_CONTEXT='1', CLICK_GOV24_GUIDE_ENABLED='1', CLICK_COUPANG_BROWSE_ENABLED='1',
            CLICK_AI2_BROWSE_GRAPH_ENABLED='0', CLICK_VALIDATION_DIAGNOSTICS='0',
            RENDER_GIT_COMMIT=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=WT, text=True).strip())
        print('LOCAL_DEVICE_AUDIT_API ready on 8765', flush=True)
        uvicorn.run('app.main:app', host='127.0.0.1', port=8765, access_log=False, log_level='warning')


if __name__ == '__main__':
    main()
