"""Explicit loopback facade for paid Gemini calls against the frozen synthetic UI.

This NEW test-only executable does not alter the native-app experiment allowlist
or production service. Public fixture controls are validated against the frozen
manifest. The actual AppTaskService sees only the current visible observation,
goal and controller history: no world state, fault condition or oracle route.
"""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import re
import sys
from time import monotonic, time

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))
sys.path.insert(0, str(BACKEND / 'scripts'))
from app.schemas.app_task import AppTaskRequest
from app.services.app_task import AppTaskService
from serve_meta_controller_experiment import diagnostic_record, MAX_BODY_BYTES, TASK_PATH

VERSION = 'local-meta-controller-synthetic-facade.v1'
PACKAGE = 'org.click.experiment.fixture'
AUTHORIZATION = 'Bearer local-meta-synthetic-experiment'
DEFAULT_MANIFEST = BACKEND.parent / 'experiments/metacontroller/closed-loop-fixture-v1.json'


def load_contract(path=DEFAULT_MANIFEST):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if data.get('schema_version') != 'click.synthetic-closed-loop.v1' or data.get('app_id') != PACKAGE:
        raise ValueError('Exact synthetic fixture contract required')
    tasks = data.get('tasks')
    if not isinstance(tasks, list) or len(tasks) != 6:
        raise ValueError('Six frozen tasks required')
    goals = frozenset(task['goal'] for task in tasks)
    labels = frozenset({'', '검색어 입력', '다른 경로', '고급', '화면 준비 중', '원하는 화면을 선택하세요', '화면을 확인하세요'} |
        {task[field] for task in tasks for field in ('primary_label', 'destination_label')})
    resources = frozenset(PACKAGE + ':id/' + name for name in
        ('primary', 'alternate', 'advanced', 'goal_title', 'history', 'pending', 'summary', 'search'))
    if len(goals) != 6:
        raise ValueError('Duplicate frozen goals')
    return goals, labels, resources


def allowed(request, contract):
    goals, labels, resources = contract
    if request.goal not in goals or request.screen.package != PACKAGE or request.screen.status != 'public':
        return False
    if any(app.package != PACKAGE for app in request.apps):
        return False
    if request.state.completion and any(check.package != PACKAGE or check.label not in labels
            or check.resource and check.resource not in resources for check in request.state.completion.checks):
        return False
    return all(node.source == 'accessibility' and node.label in labels and node.resource in resources
        for node in request.screen.nodes)


def make_handler(service, contract, output_path):
    output = Path(output_path)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        server_version = VERSION

        def setup(self):
            super().setup()
            self.connection.settimeout(35)

        def log_message(self, *_args):
            pass

        def reply(self, status, body):
            payload = json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            self.wfile.write(payload)

        def do_GET(self):
            if self.path != '/health':
                self.reply(404, {'error': 'not_found'})
                return
            self.reply(200, {'status': 'ready', 'schema_version': VERSION,
                'evidence_mode': 'live_model_synthetic_closed_loop',
                'provider_connectivity': 'NOT_CHECKED_BY_HEALTH', 'memory_enabled': False})

        def do_POST(self):
            if self.path != TASK_PATH:
                self.reply(404, {'error': 'not_found'})
                return
            if self.headers.get('Authorization') != AUTHORIZATION:
                self.reply(401, {'error': 'test_authorization_required'})
                return
            size = self.headers.get('Content-Length', '')
            if self.headers.get('Transfer-Encoding') is not None or not re.fullmatch(r'[0-9]{1,10}', size):
                self.reply(411, {'error': 'bounded_content_length_required'})
                return
            length = int(size)
            if length > MAX_BODY_BYTES:
                self.reply(413, {'error': 'body_too_large'})
                return
            if length <= 0 or self.headers.get_content_type() != 'application/json':
                self.reply(415, {'error': 'json_body_required'})
                return
            try:
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError('truncated_body')
                request = AppTaskRequest.model_validate_json(body)
            except Exception:
                self.reply(422, {'error': 'invalid_app_task_request'})
                return
            if not allowed(request, contract):
                self.reply(403, {'error': 'outside_frozen_public_fixture'})
                return
            # Only transport freshness changes. The controller keeps true virtual
            # observations and timestamps for native pre/postchecks. Fingerprint,
            # observation id, visible controls and all decision history are intact.
            wire_request = request.model_copy(update={'screen': request.screen.model_copy(
                update={'captured_at_ms': int(time() * 1000)})})
            started = monotonic()
            try:
                response = service.step(wire_request)
                record = diagnostic_record(request, response, (monotonic() - started) * 1000)
                record.update(schema_version=VERSION, evidence_mode='live_model_synthetic_closed_loop',
                    transport_clock_translation='virtual_ui_to_real_wall_freshness_only',
                    synthetic_ui=True, real_app=False)
                output.parent.mkdir(parents=True, exist_ok=True)
                with output.open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps(record, ensure_ascii=False) + '\n')
                self.reply(200, response.model_dump(mode='json'))
            except Exception:
                self.reply(500, {'error': 'synthetic_experiment_service_error'})

    return Handler


def create_server(*, output_path, port=8767, host='127.0.0.1', allow_live=False,
                  manifest=DEFAULT_MANIFEST, service=None):
    if host != '127.0.0.1' or allow_live is not True:
        raise ValueError('Explicit allow_live and loopback-only binding required')
    if type(port) is not int or not 0 <= port <= 65535:
        raise ValueError('Invalid loopback port')
    return HTTPServer((host, port), make_handler(service or AppTaskService(memory=None),
        load_contract(manifest), output_path))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-live', action='store_true')
    parser.add_argument('--port', type=int, default=8767)
    parser.add_argument('--manifest', type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument('--output', type=Path, required=True, help='Bounded, label-free provider diagnostics')
    args = parser.parse_args(argv)
    server = create_server(output_path=args.output, port=args.port,
        manifest=args.manifest, allow_live=args.allow_live)
    print(json.dumps({'status': 'listening', 'host': '127.0.0.1', 'port': server.server_port,
        'schema_version': VERSION, 'evidence_mode': 'live_model_synthetic_closed_loop'}, ensure_ascii=False), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
