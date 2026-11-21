"""Paid replay: same AppTaskService prompt, schema and validators; only the model call differs.

Arms: gemini_low (production), gemini_nothink (thinkingBudget 0), gpt_low, gpt_priority (service_tier).
'minimal' is rejected by both models (HTTP 400), so it is not an arm. Each arm is a `sender`
plugged into the real service, so parsing, plan and action validation are identical. Writes one
JSON line per call (labels are public fixture labels; no keys, prompts or raw bodies).
"""
import argparse, copy, json, os, sys, time
from pathlib import Path

import httpx

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]
from app.schemas.app_task import AppTaskRequest  # noqa: E402
from app.services.app_task import AppTaskService  # noqa: E402
from app.services.app_task_provider import generate  # noqa: E402

GEMINI, GPT = 'gemini-3.8-flash', os.getenv('PROBE_GPT_MODEL', 'gpt-6.2-sol')
SAMSUNG_MENU = ['연결', '연결된 기기', '모드 및 루틴', '소리 및 진동', '알림', '디스플레이', '배경화면 및 스타일', '테마',
                '홈 화면', '잠금화면', '보안 및 개인정보 보호', '위치', '안전 및 긴급', '계정 및 백업', 'Google',
                '배터리 및 디바이스 케어', '애플리케이션', '일반', '접근성', '소프트웨어 업데이트']
SAMSUNG_CASES = [('글자 크기와 스타일 화면을 열어줘', '디스플레이'), ('앱 알림 설정 화면을 열어줘', '알림'),
                 ('배터리 화면을 열어줘', '배터리 및 디바이스 케어'), ('블루투스 화면을 열어줘', '연결'),
                 ('다크 모드 설정 화면을 열어줘', '디스플레이'), ('디스플레이 화면을 열어줘', '디스플레이')]


def samsung_screen():
    nodes = [dict(id=f'n{i}', label=l, resource='com.android.settings:id/title', role='button', clickable=True,
                  bounds=[0, 200 + i * 110, 1080, 300 + i * 110]) for i, l in enumerate(SAMSUNG_MENU)]
    nodes.insert(0, dict(id='h', label='설정', role='text', bounds=[40, 80, 600, 180]))
    return dict(status='public', package='com.android.settings', observation_id='samsung-like', fingerprint='b' * 64,
                width=1080, height=2600, nodes=nodes)


def cases():
    fx = json.loads((ROOT / 'docs/reviews/2026-11-21-gemini-call-latency/menu-fixtures.json').read_text())
    out = [dict(source='emulator_fixture', goal=c['goal'], expected=c['expected'], screen=fx['screens'][str(c['frame'])],
                apps=fx['apps'][str(c['frame'])]) for c in fx['cases']]
    out += [dict(source='samsung_like_synthetic', goal=g, expected=e, screen=samsung_screen(), apps=[]) for g, e in SAMSUNG_CASES]
    return out


def gemini_sender(token, thinking, meter):
    def send(payload, timeout):
        p = copy.deepcopy(payload); p['generationConfig']['thinkingConfig'] = thinking
        t = time.monotonic()
        try:
            return generate(GEMINI, token, p, timeout=timeout)  # production killable boundary, new connection
        finally:
            meter['wall_ms'] = round((time.monotonic() - t) * 1000)
    return send


def _strict(schema):
    s = copy.deepcopy(schema)
    if 'planned_steps' in s['properties'] and 'planned_steps' not in s['required']:
        s['required'].append('planned_steps')  # OpenAI strict mode: every property required
    return s


def gpt_sender(token, effort, tier, meter):
    def send(payload, timeout):
        cfg = payload['generationConfig']
        body = {'model': GPT, 'store': False, 'max_output_tokens': cfg['maxOutputTokens'],
                'input': payload['contents'][0]['parts'][0]['text'], 'reasoning': {'effort': effort},
                'text': {'format': {'type': 'json_schema', 'name': 'app_task_step', 'strict': True,
                                    'schema': _strict(cfg['responseJsonSchema'])}}}
        if tier: body['service_tier'] = tier
        t = time.monotonic()
        try:
            with httpx.Client(timeout=httpx.Timeout(timeout, connect=min(2, timeout)), trust_env=False) as c:
                r = c.post('https://api.openai.com/v1/responses', json=body,
                           headers={'Authorization': 'Bearer ' + token})
        finally:
            meter['wall_ms'] = round((time.monotonic() - t) * 1000)
        if r.status_code != 200:
            meter['gpt_error'] = r.status_code
            return httpx.Response(r.status_code, content=b'{}')
        data = r.json()
        text = next(c['text'] for item in data['output'] if item.get('type') == 'message'
                    for c in item['content'] if c.get('type') == 'output_text')
        u = data.get('usage') or {}
        usage = {'promptTokenCount': u.get('input_tokens'), 'candidatesTokenCount': u.get('output_tokens'),
                 'thoughtsTokenCount': (u.get('output_tokens_details') or {}).get('reasoning_tokens'),
                 'totalTokenCount': u.get('total_tokens')}
        return httpx.Response(200, json={'candidates': [{'finishReason': 'STOP', 'content': {'parts': [{'text': text}]}}],
                                         'usageMetadata': {k: v for k, v in usage.items() if type(v) is int}})
    return send


ARMS = {'gemini_low': ('gemini', {'thinkingLevel': 'low'}), 'gemini_nothink': ('gemini', {'thinkingBudget': 0}),
        'gpt_low': ('gpt', ('low', None)), 'gpt_priority': ('gpt', ('low', 'priority'))}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--allow-live', action='store_true'); ap.add_argument('--env-file', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True); ap.add_argument('--repeats', type=int, default=2)
    ap.add_argument('--arms', default=','.join(ARMS)); ap.add_argument('--limit', type=int, default=0)
    a = ap.parse_args()
    if not a.allow_live: ap.error('Real Gemini/OpenAI calls; pass --allow-live')
    keys = {}
    for line in a.env_file.read_text().splitlines():
        k, _, v = line.partition('=')
        if k.strip() in {'GEMINI_API_KEY', 'OPENAI_API_KEY'}: keys[k.strip()] = v.strip().strip('"\'')
    os.environ['APP_TASK_COMPLETION_PROVIDER'] = 'gemini'; os.environ['APP_TASK_COMPLETION_TRIAGE'] = '0'
    os.environ['GEMINI_SETTINGS_MODEL'] = GEMINI; os.environ.pop('APP_TASK_MENU_MODEL', None)
    arms = a.arms.split(','); todo = cases()[:a.limit or None]
    with a.output.open('x') as out:
        for rep in range(a.repeats):
            for i, case in enumerate(todo):
                k = (i + rep) % len(arms); order = arms[k:] + arms[:k]  # rotate arm order per call slot
                for arm in order:
                    provider, level = ARMS[arm]; meter = {}
                    sender = (gemini_sender(keys['GEMINI_API_KEY'], level, meter) if provider == 'gemini'
                              else gpt_sender(keys['OPENAI_API_KEY'], *level, meter))
                    screen = dict(case['screen'], captured_at_ms=round(time.time() * 1000))
                    req = AppTaskRequest(request_id=f'{arm}-{rep}-{i}', goal=case['goal'], screen=screen,
                                         state={'task_id': f'probe-{rep}-{i}'}, planning_mode='goal_first', apps=case['apps'])
                    res = AppTaskService(sender).step(req)
                    d = res.diagnostic
                    label = next((n.label for n in req.screen.nodes if res.action and n.id == res.action.target_id), None)
                    row = dict(rep=rep, case=i, source=case['source'], arm=arm, goal=case['goal'], expected=case['expected'],
                               target=label, status=res.status, correct=res.status == 'proposed' and res.action.tool == 'tap' and label == case['expected'],
                               service_ms=d.get('latency_ms'), wall_ms=meter.get('wall_ms'), gpt_error=meter.get('gpt_error'),
                               failure=d.get('reason_code') if res.status == 'unavailable' else None, error_type=d.get('error_type'),
                               input_tokens=d.get('input_tokens'), output_tokens=d.get('output_tokens'),
                               thinking_tokens=d.get('thinking_tokens'), total_tokens=d.get('total_tokens'),
                               completion=bool(res.completion), deferred=bool(res.deferred_completion),
                               planned=[s.label for s in res.planned_steps])
                    out.write(json.dumps(row, ensure_ascii=False) + '\n'); out.flush()
                    print(json.dumps({k: row[k] for k in ('rep', 'case', 'arm', 'correct', 'service_ms', 'status', 'failure', 'gpt_error')}), flush=True)


if __name__ == '__main__':
    main()
