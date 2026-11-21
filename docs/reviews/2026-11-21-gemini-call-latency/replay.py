"""Replay six public menus with Gemini (12 calls), GPT, OpenRouter or loopback Qwen (6 each)."""
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
from app.services.app_task_provider import LOCAL_MODEL
from app.services.openai_responses import QWEN_FLASH_MODEL, app_task_model

BASE = 'gemini-3.8-flash'
LITE = 'gemini-3.5-flash-lite'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--provider', choices=('gemini', 'openai', 'openrouter', 'local'), default='gemini')
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--short-menu', action='store_true', help='Use a button-ID response with OpenRouter.')
    modes.add_argument('--compact', action='store_true', help='Use the primary Qwen compact planner.')
    args = parser.parse_args()
    if (args.short_menu or args.compact) and args.provider != 'openrouter':
        parser.error('--short-menu and --compact require --provider openrouter')
    load_dotenv(ROOT / 'Backend/.env')
    key = {'gemini': 'GEMINI_API_KEY', 'openai': 'OPENAI_API_KEY',
           'openrouter': 'OPENROUTER_API_KEY'}.get(args.provider)
    if key and not os.getenv(key, '').strip():
        parser.error(f'{key} is required')
    os.environ['APP_TASK_PROVIDER'] = args.provider
    os.environ['APP_TASK_OPENROUTER_SHORT_MENU'] = '1' if args.short_menu else '0'
    os.environ['APP_TASK_OPENROUTER_COMPACT'] = '1' if args.compact else '0'
    os.environ['GEMINI_SETTINGS_MODEL'] = BASE
    os.environ['APP_TASK_COMPLETION_PROVIDER'] = 'gemini'
    fixtures = json.loads((HERE / 'menu-fixtures.json').read_text())
    with args.output.open('x') as output:
        for index, case in enumerate(fixtures['cases']):
            frame = str(case['frame'])
            if args.provider == 'local':
                models = (LOCAL_MODEL,)
            elif args.provider == 'openai':
                models = (app_task_model(),)
            elif args.provider == 'openrouter':
                models = (QWEN_FLASH_MODEL,)
            else:
                models = (BASE, LITE) if index % 2 == 0 else (LITE, BASE)
            for model in models:
                if args.provider == 'gemini':
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
                                      target=label, ms=diagnostic['latency_ms'], correct=correct,
                                      http_round_trip_ms=diagnostic.get('http_round_trip_ms')), ensure_ascii=False),
                      file=output, flush=True)


if __name__ == '__main__':
    main()
