"""Replay six public menu requests with both Gemini models. This uses 12 real API calls."""
import argparse
import json
import os
import sys
import time
from pathlib import Path

from dotenv import load_dotenv

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]
from app.schemas.app_task import AppTaskRequest
from app.services.app_task import AppTaskService

BASE = 'gemini-3.8-flash'
LITE = 'gemini-3.5-flash-lite'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    load_dotenv(ROOT / 'Backend/.env')
    if not os.getenv('GEMINI_API_KEY', '').strip():
        parser.error('GEMINI_API_KEY is required')
    os.environ['GEMINI_SETTINGS_MODEL'] = BASE
    os.environ['APP_TASK_COMPLETION_PROVIDER'] = 'gemini'
    fixtures = json.loads((HERE / 'menu-fixtures.json').read_text())
    with args.output.open('x') as output:
        for index, case in enumerate(fixtures['cases']):
            frame = str(case['frame'])
            for model in ((BASE, LITE) if index % 2 == 0 else (LITE, BASE)):
                os.environ['APP_TASK_MENU_MODEL'] = model
                screen = dict(fixtures['screens'][frame], captured_at_ms=round(time.time() * 1000))
                request = AppTaskRequest(request_id=f'replay-{index}', goal=case['goal'], screen=screen,
                                         state={'task_id': f'menu-{index}'}, planning_mode='goal_first',
                                         apps=fixtures['apps'][frame])
                result = AppTaskService().step(request)
                label = next((n.label for n in request.screen.nodes if result.action and n.id == result.action.target_id), None)
                diagnostic = result.diagnostic
                if diagnostic.get('called') is not True or diagnostic.get('model') != model:
                    raise RuntimeError(f'Expected one {model} call for case {index}: {diagnostic.get("reason_code")}')
                correct = result.status == 'proposed' and result.action.tool == 'tap' and label == case['expected']
                print(json.dumps(dict(index=index, model=model, goal=case['goal'], expected=case['expected'],
                                      target=label, ms=diagnostic['latency_ms'], correct=correct), ensure_ascii=False),
                      file=output, flush=True)


if __name__ == '__main__':
    main()
