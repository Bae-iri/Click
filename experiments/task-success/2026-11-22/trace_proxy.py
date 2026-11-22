"""Record stage receipts for an isolated localhost device-audit server on port 8765."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from threading import Lock
import time
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'Backend'))
from app.clients.runtime_timing import PHASES

import httpx

PUBLIC_LABELS = {'디스플레이', '연결', 'Wi-Fi', '와이파이', '타이머', '세계시각', '스톱워치', '알람',
                 'Shorts', '구독', '검색', 'YouTube 검색', 'Chrome 맞춤설정 및 제어', '설정', '새 탭', '검색 설정', '디스플레이 설정'}
TRACE = {'/api/v1/agent/commands', '/api/v1/agent/turn', '/api/v1/agent/app-guide/task'}
DIAGNOSTICS = ('model', 'called', 'reason', 'reason_code', 'failure_stage', 'failure_kind',
               'latency_ms', 'outcome_provider', 'completion_provider')


def receipt(phase, path, request, response, http, elapsed, response_headers=None):
    action = response.get('action') or {}
    nodes = (request.get('screen') or {}).get('nodes', [])
    label = next((n.get('label') for n in nodes if n.get('id') == action.get('target_id')), '')
    diag = response.get('diagnostic') or {}
    row = dict(phase=phase, path=path.rsplit('/', 1)[1], status=response.get('status'), http=http,
               elapsed_ms=elapsed, command=request.get('command_type'), mode=request.get('planning_mode'),
               goal=request.get('goal') if isinstance(request.get('goal'), str) else None,
               subgoals=[x['title'] for x in response.get('subgoals', [])], action=action.get('tool'),
               label=label if label in PUBLIC_LABELS else ('other' if label else None),
               diagnostic={key:diag[key] for key in DIAGNOSTICS if key in diag})
    runtime_phase = (response_headers or {}).get('X-Click-Runtime-Phase')
    if isinstance(runtime_phase, str) and runtime_phase in PHASES:
        row['runtime_phase'] = runtime_phase
    if row['command'] == 'resolve_goal':
        row.update(resolution=response.get('resolution_status'), typed_goal=(response.get('goal') or {}).get('goal_type'),
                   utterance=request.get('source_text'))
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    out = parser.parse_args().output
    out.mkdir(parents=True, exist_ok=True)
    lock = Lock()
    with httpx.Client(base_url='http://127.0.0.1:8765', timeout=38, trust_env=False) as client:
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def forward(self):
                size = int(self.headers.get('Content-Length', 0))
                if not (0 <= size <= 2000000) or not (self.path.startswith('/api/v1/') or
                                                      self.path in {'/version','/openapi.json','/health'}):
                    self.send_error(404)
                    return
                stage = out / 'phase'
                phase = stage.read_text().strip() if stage.exists() else 'setup'
                body = self.rfile.read(size)
                headers = {k:v for k,v in self.headers.items() if k.lower() in
                           {'content-type','authorization','x-click-turn-budget-ms','x-click-execution-state'}}
                start = time.monotonic()
                try:
                    result = client.request(self.command, self.path, headers=headers, content=body)
                except httpx.HTTPError:
                    self.send_error(502)
                    return
                if self.path in TRACE:
                    row = receipt(phase, self.path, json.loads(body), result.json(), result.status_code,
                                  round(1000*(time.monotonic()-start)), result.headers)
                    with lock, (out/'calls.jsonl').open('a') as f:
                        f.write(json.dumps(row, ensure_ascii=False)+'\n')
                self.send_response(result.status_code)
                self.send_header('Content-Type','application/json')
                self.send_header('Content-Length',str(len(result.content)))
                runtime_phase = result.headers.get('X-Click-Runtime-Phase')
                if runtime_phase in PHASES:
                    self.send_header('X-Click-Runtime-Phase', runtime_phase)
                self.end_headers()
                try:
                    self.wfile.write(result.content)
                except BrokenPipeError:
                    pass

            do_GET = do_POST = do_DELETE = forward

        with ThreadingHTTPServer(('127.0.0.1', 8774), Handler) as server:
            server.serve_forever()


if __name__ == '__main__':
    main()
