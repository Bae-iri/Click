"""Replay synchronized bursts through the app task endpoint against the loopback Qwen server."""
import argparse
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier

from fastapi import FastAPI
from fastapi.testclient import TestClient

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]
from app.api.v1.endpoints.app_guide import build_router
from app.services.app_guide import AppGuideService
from app.services.app_task import AppTaskService


def run_request(fixtures, client, barrier, users, wave, lane):
    index = (wave + lane) % len(fixtures['cases'])
    case = fixtures['cases'][index]
    frame = str(case['frame'])
    screen = dict(fixtures['screens'][frame])
    body = dict(request_id=f'load-{wave}-{lane}', state=dict(task_id=f'job-{wave}-{lane}'),
                goal=case['goal'], apps=fixtures['apps'][frame], screen=screen, planning_mode='goal_first')
    barrier.wait(timeout=10)
    start = time.monotonic()
    screen['captured_at_ms'] = round(time.time() * 1000)
    response = client.post('/api/v1/agent/app-guide/task', json=body)
    response.raise_for_status()
    elapsed = round((time.monotonic() - start) * 1000)
    result = response.json()
    diagnostic = result['diagnostic']
    action = result.get('action')
    label = next((n['label'] for n in screen['nodes'] if action and n['id'] == action['target_id']), None)
    correct = result['status'] == 'proposed' and action['tool'] == 'tap' and label == case['expected']
    return dict(users=users, wave=wave, lane=lane, fixture=index, correct=correct, selected=label,
                status=result['status'], called=diagnostic.get('called'), ms=elapsed,
                model_ms=diagnostic.get('latency_ms'), reason=diagnostic.get('reason_code'),
                provider=diagnostic.get('completion_provider'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    os.environ['APP_TASK_PROVIDER'] = 'local'
    fixtures = json.loads((HERE / 'menu-fixtures.json').read_text())
    app = FastAPI()
    app.include_router(build_router(AppGuideService(), lambda: object(), AppTaskService()), prefix='/api/v1')
    with args.output.open('x') as output, TestClient(app) as client, ThreadPoolExecutor(max_workers=4) as pool:
        for wave, users in enumerate((1, 2, 4, 4, 1, 2, 2, 4, 1)):
            barrier = Barrier(users)
            jobs = [pool.submit(run_request, fixtures, client, barrier, users, wave, lane) for lane in range(users)]
            for job in jobs:
                print(json.dumps(job.result(), ensure_ascii=False), file=output, flush=True)


if __name__ == '__main__':
    main()
