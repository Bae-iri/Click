"""Opt-in live classifier checks on synthetic requests and disposable in-memory DBs.

Never connects to the production DB, reads screen data or changes device settings.
"""
import argparse
from pathlib import Path
import sys
from uuid import uuid4

BACKEND = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(BACKEND), str(BACKEND.parent / 'AI-Agent/src')]

from app.services.goal_classifier import goal_classifier_configuration

CASES = (
    ('brightness_down', '눈이 부시니 빛이 덜 나오게 해줘', 'settings.brightness.decrease', None),
    ('brightness_up', '빛이 부족해. 좀 환하게 보여줘', 'settings.brightness.increase', None),
    ('media_down', '유튜브 재생이 귀에 거슬려. 한결 잔잔하게 들리게 해줘', 'settings.volume.decrease', None),
    ('media_up', '유튜브 재생이 잘 안 들려. 더 우렁차게 들려줘', 'settings.volume.increase', None),
    ('dark_theme', '밤이라 검은 배경 테마로 전환해줘', 'settings.dark_mode.enable', None),
    ('light_theme', '검은 배경 대신 흰 바탕 테마를 쓰고 싶어', 'settings.dark_mode.disable', None),
    ('missing_scope', '문자가 깨알처럼 보여', None, 'scope'),
    ('cancel', '다크모드 키지 마', None, 'request'),
)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-live', action='store_true', help='Send only these fixed synthetic utterances to the configured classifier')
    args = parser.parse_args(argv)
    state = goal_classifier_configuration()
    print(f"provider={state['provider']} configured={state['configured']}")
    if not state['configured']:
        print('CONFIG_ERROR: goal interpretation is not configured; no model requests were sent.')
        return 2
    if not args.run_live:
        print('Configuration only. Add --run-live to test the full goal-preparation path.')
        return 0
    from fastapi.testclient import TestClient
    from app.main import create_app
    passed = 0
    for label, source, expected_goal, expected_question in CASES:
        # Explicit URL overrides DATABASE_URL. No production account or DB is used.
        app = create_app('sqlite+pysqlite:///:memory:')
        with TestClient(app) as client:
            account = client.post('/api/v1/auth/register', json={
                'name':'Synthetic check', 'phone_number':'010'+str(uuid4().int % 100000000).zfill(8),
                'password':str(uuid4().int % 10000).zfill(4), 'age_group':'60_plus', 'occupation_category':'retired'})
            ok = account.status_code == 201
            if ok:
                client.headers['Authorization'] = 'Bearer ' + account.json()['access_token']
                queued = client.post('/api/v1/voice-requests/enqueue', json={'id':str(uuid4()), 'raw_goal':source})
                claim = client.post('/api/v1/voice-requests/next/claim') if queued.status_code == 200 else None
                running = claim.json().get('running') if claim is not None and claim.status_code == 200 else None
                ok = bool(running)
                if ok:
                    response = client.post('/api/v1/agent/commands', json={
                        'contract_version':'agent-fe-contract.v0.3', 'preparation_version':'goal-inputs.v1',
                        'command_type':'resolve_goal', 'request_id':str(uuid4()),
                        'session_id':running['agent_session_id'], 'expected_version':0, 'source_text':source,
                        'device_context':{'platform':'android','locale':'ko-KR'}})
                    body = response.json() if response.status_code == 200 else {}
                    preparation = body.get('preparation') or {}
                    if expected_goal:
                        ok = (body.get('goal') or {}).get('goal_type') == expected_goal and bool(
                            preparation.get('graph_reference')) and preparation.get('status') == 'ready'
                    else:
                        ok = body.get('goal') is None and (preparation.get('question') or {}).get('field') == expected_question
                    ok = ok and 'guide_command' not in body
            passed += int(ok)
            # Fixed labels and pass/fail only; no tokens, model responses or user content.
            print(f'{label}: {"PASS" if ok else "FAIL"}')
    print(f'{passed}/{len(CASES)} live-model routing checks passed. No physical task was executed.')
    return 0 if passed == len(CASES) else 1


if __name__ == '__main__':
    raise SystemExit(main())
