"""Paid probe: does latency hold when k planner calls are in flight at once?

1) Provider side: k simultaneous raw calls (threads, no local cap) with the real planner payload.
2) Our server side: k simultaneous calls through production generate(), which caps a process at 2.
Writes one JSON line per call; no keys, prompts or bodies.
"""
import argparse, json, os, sys, threading, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import replay_models as rm  # noqa: E402
from app.schemas.app_task import AppTaskRequest  # noqa: E402
from app.services.app_task import AppTaskService  # noqa: E402
from app.services.app_task_provider import ProviderError, generate  # noqa: E402


def payloads():
    """Capture the exact production payload for each fixture case without calling a model."""
    out = []
    for i, case in enumerate(rm.cases()):
        box = {}
        def capture(payload, timeout):
            box['p'] = payload
            raise ProviderError('provider_deadline_exceeded', 'not_started')
        screen = dict(case['screen'], captured_at_ms=round(time.time() * 1000))
        AppTaskService(capture).step(AppTaskRequest(request_id=f'c{i}', goal=case['goal'], screen=screen,
            state={'task_id': f'c{i}'}, planning_mode='goal_first', apps=case['apps']))
        out.append(box['p'])
    return out


def raw_call(arm, payload, keys):
    meter = {}
    provider, level = rm.ARMS[arm]
    send = (rm.gemini_sender(keys['GEMINI_API_KEY'], level, meter) if provider == 'gemini'
            else rm.qwen_sender(keys['QWEN_API_KEY'], meter) if provider == 'qwen'
            else rm.gpt_sender(keys['OPENAI_API_KEY'], *level, meter))
    if provider == 'gemini':  # bypass the production 2-slot cap to see the provider itself
        import app.services.app_task_provider as p
        p._CAPACITY = threading.BoundedSemaphore(64)
    t = time.monotonic()
    try:
        r = send(payload, 9.0); ok = r.status_code == 200
    except ProviderError as e:
        ok = False; meter['error'] = e.reason_code
    return dict(ms=round((time.monotonic() - t) * 1000), ok=ok, error=meter.get('error') or meter.get('gpt_error'))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--allow-live', action='store_true'); ap.add_argument('--env-file', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True); ap.add_argument('--arms', default='gpt_low,qwen_flash')
    ap.add_argument('--levels', default='1,4,8'); ap.add_argument('--server-cap-check', action='store_true'); ap.add_argument('--cap-levels', default='4')
    a = ap.parse_args()
    if not a.allow_live: ap.error('Real provider calls; pass --allow-live')
    keys = {}
    for line in a.env_file.read_text().splitlines():
        k, _, v = line.partition('=')
        if k.strip() in {'GEMINI_API_KEY', 'OPENAI_API_KEY', 'QWEN_API_KEY'}: keys[k.strip()] = v.strip().strip('"\'')
    os.environ['APP_TASK_COMPLETION_TRIAGE'] = '0'
    pls = payloads()
    with a.output.open('x') as out:
        for arm in [x for x in a.arms.split(',') if x]:
            for k in map(int, a.levels.split(',')):
                batch = [pls[i % len(pls)] for i in range(k)]
                with ThreadPoolExecutor(max_workers=k) as pool:
                    started = time.monotonic()
                    results = list(pool.map(lambda p: raw_call(arm, p, keys), batch))
                for r in results:
                    out.write(json.dumps(dict(kind='provider', arm=arm, concurrency=k, **r)) + '\n')
                print(json.dumps(dict(arm=arm, concurrency=k, ok=sum(r['ok'] for r in results),
                    median_ms=sorted(r['ms'] for r in results)[k // 2], max_ms=max(r['ms'] for r in results),
                    batch_ms=round((time.monotonic() - started) * 1000))), flush=True)
        if a.server_cap_check:  # production cap: generate() with its real 2-slot semaphore, Gemini path
            import importlib, app.services.app_task_provider as p
            importlib.reload(p)
            def capped(payload):
                t = time.monotonic()
                try:
                    generate(rm.GEMINI, keys['GEMINI_API_KEY'], payload, timeout=9.0); return dict(ms=round((time.monotonic()-t)*1000), ok=True, error=None)
                except Exception as e:  # reload() makes a fresh ProviderError class
                    return dict(ms=round((time.monotonic()-t)*1000), ok=False, error=getattr(e, 'reason_code', type(e).__name__))
            for k in map(int, a.cap_levels.split(',')):
                with ThreadPoolExecutor(max_workers=k) as pool:
                    results = list(pool.map(capped, [pls[i % len(pls)] for i in range(k)]))
                cap = p.configured_capacity()
                for r in results:
                    out.write(json.dumps(dict(kind='server_cap', arm='gemini_low_production_generate', capacity=cap, concurrency=k, **r)) + '\n')
                print(json.dumps(dict(capacity=cap, concurrency=k, ok=sum(r['ok'] for r in results),
                    median_ms=sorted(r['ms'] for r in results)[k // 2], errors=[r['error'] for r in results if r['error']])), flush=True)


if __name__ == '__main__':
    main()
