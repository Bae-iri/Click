"""Free probe: how much of one planner call is fixed overhead (process spawn + new TLS), not the model?

countTokens does no generation and is not billed. Prints medians; writes nothing secret.
"""
import argparse, json, multiprocessing, os, statistics, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / 'Backend'), str(ROOT / 'AI-Agent/src')]


def _child(conn):
    import app.services.app_task_provider  # noqa: F401  same import cost as the real worker
    import httpx  # noqa: F401
    conn.send(time.monotonic()); conn.close()


def spawn_once():
    ctx = multiprocessing.get_context('spawn'); r, s = ctx.Pipe(duplex=False)
    t0 = time.monotonic(); p = ctx.Process(target=_child, args=(s,), daemon=True); p.start(); s.close()
    ready = r.recv(); p.join(); return (ready - t0) * 1000


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--env-file', type=Path, required=True); ap.add_argument('-n', type=int, default=10)
    a = ap.parse_args()
    for line in a.env_file.read_text().splitlines():
        k, _, v = line.partition('=')
        if k.strip() == 'GEMINI_API_KEY': token = v.strip().strip('"\'')
    import httpx
    url = 'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:countTokens'
    body = json.dumps({'contents': [{'parts': [{'text': '디스플레이 화면을 열어줘'}]}]}).encode()
    hdr = {'x-goog-api-key': token, 'Content-Type': 'application/json'}
    spawn = [spawn_once() for _ in range(a.n)]
    fresh, reused, h2 = [], [], []
    for _ in range(a.n):
        t = time.monotonic()
        with httpx.Client(transport=httpx.HTTPTransport(retries=0), trust_env=False) as c:
            assert c.post(url, headers=hdr, content=body).status_code == 200
        fresh.append((time.monotonic() - t) * 1000)
    with httpx.Client(transport=httpx.HTTPTransport(retries=0), trust_env=False) as c:
        c.post(url, headers=hdr, content=body)
        for _ in range(a.n):
            t = time.monotonic(); assert c.post(url, headers=hdr, content=body).status_code == 200; reused.append((time.monotonic() - t) * 1000)
    med = lambda v: round(statistics.median(v), 1)
    print(json.dumps(dict(n=a.n, spawn_ready_ms=med(spawn), spawn_range=[round(min(spawn)), round(max(spawn))],
        new_connection_count_tokens_ms=med(fresh), reused_connection_count_tokens_ms=med(reused),
        connection_setup_ms=round(med(fresh) - med(reused), 1))))


if __name__ == '__main__':
    main()
