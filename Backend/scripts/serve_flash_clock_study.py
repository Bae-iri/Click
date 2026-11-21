"""Serve the public-clock instrumentation study on the workstation's USB loopback port."""
import os
from pathlib import Path
from unittest.mock import patch

from dotenv import load_dotenv
from fastapi import FastAPI
import uvicorn

from app.api.v1.endpoints.app_guide import build_router
from app.services import app_task, openai_responses


def main():
    load_dotenv(Path(__file__).resolve().parents[1] / '.env')
    if not os.getenv('OPENROUTER_API_KEY', '').strip():
        raise SystemExit('Set OPENROUTER_API_KEY for the Flash study.')
    os.environ.update(APP_TASK_PROVIDER='openrouter', APP_TASK_OPENROUTER_COMPACT='1',
                      APP_TASK_OPENROUTER_SHORT_MENU='0', APP_TASK_COMPLETION_TRIAGE='0')
    generate = app_task.generate

    def verified_flash(model, token, payload, **options):
        if options['provider'] != 'openrouter' or model != openai_responses.QWEN_FLASH_MODEL:
            raise ValueError('Unexpected study planner')
        reply = generate(model, token, payload, **options)
        if reply.status_code == 200:
            served = reply.json().get('model')
            if served != model:
                raise ValueError('Unexpected model in provider response')
            print(f'HTTP 200 model={served}', flush=True)
        return reply

    api = FastAPI()
    api.include_router(build_router(None, lambda: object(), app_task.AppTaskService()), prefix='/api/v1')
    with patch.object(app_task, 'generate', verified_flash):
        uvicorn.run(api, host='127.0.0.1', port=18778, access_log=False)


if __name__ == '__main__':
    main()
