"""Paid, device-free probe: does the live planner return usable planned_steps, and what do they cost?

Sends the SAME public, Settings-like first screens to a given Backend checkout's AppTaskService
(real Gemini). Run it once against a checkout before planned_steps and once after, then compare.
The screens are synthetic public menu lists (no device capture, no private data). Only bounded,
label-free counts are written, plus the planned public labels so they can be checked on a device.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import time
from uuid import uuid4

MENU = ['연결', '연결된 기기', '모드 및 루틴', '소리 및 진동', '알림', '디스플레이', '배경화면 및 스타일', '테마',
        '홈 화면', '잠금화면', '보안 및 개인정보 보호', '위치', '안전 및 긴급', '계정 및 백업', 'Google',
        '배터리 및 디바이스 케어', '애플리케이션', '일반', '접근성', '소프트웨어 업데이트']
# goal, expected first tap, the later labels a One UI 5 device is expected to show (to check on a device)
GOALS = [('글자 크기와 스타일 화면을 열어줘', '디스플레이', ['글자 크기와 스타일']),
         ('앱 알림 설정 화면을 열어줘', '알림', ['앱 알림']),
         ('배터리 화면을 열어줘', '배터리 및 디바이스 케어', ['배터리']),
         ('블루투스 화면을 열어줘', '연결', ['블루투스']),
         ('다크 모드 설정 화면을 열어줘', '디스플레이', ['다크 모드 설정']),
         ('디스플레이 화면을 열어줘', '디스플레이', [])]


def screen(now_ms):
    nodes = [dict(id=f'n{i}', label=label, resource='com.android.settings:id/title', role='button', clickable=True,
                  bounds=[0, 200 + i * 110, 1080, 300 + i * 110]) for i, label in enumerate(MENU)]
    nodes.insert(0, dict(id='h', label='설정', role='text', bounds=[40, 80, 600, 180]))
    return dict(status='public', package='com.android.settings', observation_id=str(uuid4()), captured_at_ms=now_ms,
                fingerprint='b' * 64, width=1080, height=2600, nodes=nodes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-live', action='store_true')
    parser.add_argument('--backend', type=Path, required=True, help='Backend directory of the checkout to probe')
    parser.add_argument('--env-file', type=Path, required=True)
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.allow_live:
        parser.error('This sends real Gemini requests; pass --allow-live')
    if args.output.exists():
        parser.error('Refuse to overwrite a previous run')
    for line in args.env_file.read_text().splitlines():
        key, _, value = line.partition('=')
        if key.strip() in {'GEMINI_API_KEY', 'GEMINI_SETTINGS_MODEL'} and value.strip():
            os.environ[key.strip()] = value.strip().strip('"\'')
    sys.path.insert(0, str(args.backend.resolve()))
    sys.path.insert(0, str(args.backend.resolve().parent / 'AI-Agent/src'))
    from app.schemas.app_task import AppTaskRequest
    from app.services.app_task import AppTaskService
    service = AppTaskService()
    rows = []
    for repeat in range(args.repeats):
        for goal, first, later in GOALS:
            request = AppTaskRequest(request_id=str(uuid4()), goal=goal, state={'task_id': str(uuid4())},
                                     screen=screen(int(time.time() * 1000)), planning_mode='goal_first')
            started = time.monotonic()
            response = service.step(request)
            target = next((n.label for n in request.screen.nodes if response.action and n.id == response.action.target_id), None)
            d = response.diagnostic
            planned = [s.label for s in getattr(response, 'planned_steps', [])]
            rows.append(dict(repeat=repeat, goal=goal, status=response.status, first_tap=target, first_tap_expected=first,
                planned_steps=planned, planned_expected=later, planned_exact=planned[:len(later)] == later if later else planned == [],
                completion_labels=[c.label for c in response.completion.checks] if response.completion else [],
                goal_assessment=d.get('goal_assessment'), provider_error=d.get('failure_kind') == 'provider',
                reason=d.get('reason') or d.get('reason_code'), model=d.get('model'),
                input_tokens=d.get('input_tokens'), output_tokens=d.get('output_tokens'), thinking_tokens=d.get('thinking_tokens'),
                total_tokens=d.get('total_tokens'), latency_ms=d.get('latency_ms'), wall_ms=round((time.monotonic() - started) * 1000)))
            print(json.dumps(rows[-1], ensure_ascii=False))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows))


if __name__ == '__main__':
    main()
