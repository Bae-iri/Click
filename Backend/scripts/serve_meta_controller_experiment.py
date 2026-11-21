"""Opt-in, loopback-only facade for native MetaController experiment APKs.

Uses the unchanged AppTaskRequest/AppTaskService protocol and privacy filters.
This is not a production authentication bypass: it runs on a separate loopback
port, accepts one static test authorization and exact frozen catalogue goals,
and exposes neither memory, learning, generic proxying nor other API routes.
"""
import argparse
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import math
import os
from pathlib import Path
import re
import sys
from time import monotonic

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))
from app.schemas.app_task import AppTaskRequest
from app.services.app_task import AppTaskService
from app.services.settings_planner import model_name


VERSION = 'local-meta-controller-facade.v1'
TEST_AUTHORIZATION = 'Bearer local-meta-experiment'
TASK_PATH = '/api/v1/agent/app-guide/task'
MAX_BODY_BYTES = 128 * 1024
ALLOWED_PACKAGES = frozenset(('com.android.settings', 'com.sec.android.app.clockpackage',
    'com.android.deskclock', 'com.android.chrome', 'com.google.android.youtube'))
REASONS = frozenset(('invalid_model_configuration', 'missing_api_key', 'grounded_tool_proposal',
    'provider_or_plan_error', 'ai2_remembered'))
PROVIDER_CAUSES = frozenset(('model_high_demand', 'model_overloaded', 'quota_reported',
    'api_key_error', 'permission_denied', 'model_not_found', 'daily_quota_exceeded', 'unspecified'))
ERROR_TYPES = frozenset(('ValueError', 'TimeoutError', 'ReadTimeout', 'ConnectTimeout',
    'WriteTimeout', 'PoolTimeout', 'ConnectError', 'ReadError', 'WriteError',
    'RemoteProtocolError', 'LocalProtocolError', 'JSONDecodeError', 'KeyError',
    'TypeError', 'StopIteration', 'ValidationError'))
NETWORK_ERRORS = ERROR_TYPES - {'ValueError', 'JSONDecodeError', 'KeyError',
    'TypeError', 'StopIteration', 'ValidationError'}


def load_catalogue(path):
    catalogue = json.loads(Path(path).read_text(encoding='utf-8'))
    tasks = catalogue.get('tasks') if isinstance(catalogue, dict) else None
    if not isinstance(tasks, list) or not tasks:
        raise ValueError('Catalogue must contain a nonempty tasks array')
    allowed = set()
    for task in tasks:
        if not isinstance(task, dict) or task.get('app_id') not in ALLOWED_PACKAGES:
            raise ValueError('Catalogue has an app outside the fixed allowlist')
        goal = task.get('goal')
        if not isinstance(goal, str) or not 1 <= len(goal) <= 200 or goal != goal.strip():
            raise ValueError('Catalogue goals must be exact nonempty strings, at most 200 characters')
        allowed.add((task['app_id'], goal))
    return frozenset(allowed)


def _ref(value):
    return sha256(str(value).encode()).hexdigest()[:24]


def _bounded_ms(value):
    return round(value, 3) if type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 120000 else None


def diagnostic_record(request, response, elapsed_ms):
    """No raw goal, controls, labels, model response, identifiers or API secrets."""
    diagnostic = response.diagnostic if isinstance(response.diagnostic, dict) else {}
    called = diagnostic.get('called') is True
    model = diagnostic.get('model')
    if not isinstance(model, str) or not re.fullmatch(r'(?:gemini-[a-zA-Z0-9.-]{1,100}|gpt-[a-zA-Z0-9._-]{1,80}|qwen3-1\.7b)', model):
        model = None
    reason = diagnostic.get('reason')
    record = dict(schema_version=VERSION, request_ref=_ref(request.request_id),
        episode_ref=_ref(request.state.task_id), model=model,
        model_calls=int(called), model_wait_ms=_bounded_ms(diagnostic.get('latency_ms')) if called else 0,
        request_total_handler_ms=round(elapsed_ms, 3), status=response.status,
        reason=reason if reason in REASONS else None,
        memory_enabled=False, evidence_mode='live_native_app_task_facade')
    http_status = diagnostic.get('http_status')
    if type(http_status) is int and 100 <= http_status <= 599:
        record['provider_http_status'] = http_status
    cause = diagnostic.get('provider_cause')
    if cause in PROVIDER_CAUSES:
        record['provider_cause'] = cause
    error_type = diagnostic.get('error_type')
    record['error_type'] = error_type if error_type in ERROR_TYPES else None
    record['provider_error'] = diagnostic.get('failure_kind') == 'provider' or error_type in NETWORK_ERRORS
    record['plan_or_parse_error'] = called and response.status == 'unavailable' and not record['provider_error']
    return record


def make_handler(service, catalogue, output_path):
    """Build the single test endpoint; service injection is for no-network tests."""
    if not catalogue or any(package not in ALLOWED_PACKAGES for package, _ in catalogue):
        raise ValueError('An exact frozen allowlisted catalogue is required')
    output_path = Path(output_path)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        server_version = VERSION

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def log_message(self, *_args):
            pass  # Standard access logs echo URL/headers; only explicit bounded diagnostics are persisted.

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
            self.reply(200, dict(status='ready', schema_version=VERSION, binding='loopback_only',
                provider_connectivity='NOT_CHECKED_BY_HEALTH', memory_enabled=False))

        def do_POST(self):
            if self.path != TASK_PATH:
                self.reply(404, {'error': 'not_found'})
                return
            if self.headers.get('Authorization') != TEST_AUTHORIZATION:
                self.reply(401, {'error': 'test_authorization_required'})
                return
            if self.headers.get('Transfer-Encoding') is not None:
                self.reply(400, {'error': 'transfer_encoding_not_supported'})
                return
            size = self.headers.get('Content-Length', '')
            if not re.fullmatch(r'[0-9]{1,10}', size):
                self.reply(411, {'error': 'content_length_required'})
                return
            length = int(size)
            if length > MAX_BODY_BYTES:
                self.reply(413, {'error': 'body_too_large'})
                return
            if not length or self.headers.get_content_type() != 'application/json':
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
            if (request.screen.package, request.goal) not in catalogue:
                self.reply(403, {'error': 'outside_frozen_catalogue'})
                return
            if any(app.package != request.screen.package for app in request.apps):
                self.reply(403, {'error': 'cross_app_selection_not_in_pilot'})
                return
            if request.state.completion and any(check.package != request.screen.package
                    for check in request.state.completion.checks):
                self.reply(403, {'error': 'cross_app_completion_not_in_pilot'})
                return
            started = monotonic()
            try:
                # No memory dependency or user identity: AI2 recall cannot be reached.
                response = service.step(request)
                record = diagnostic_record(request, response, (monotonic() - started) * 1000)
                output_path.parent.mkdir(parents=True, exist_ok=True)
                with output_path.open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps(record, ensure_ascii=False) + '\n')
                self.reply(200, response.model_dump(mode='json'))
            except Exception:
                self.reply(500, {'error': 'local_experiment_service_error'})

    return Handler


def create_server(*, catalogue, output_path, port=8766, host='127.0.0.1', allow_live=False, service=None):
    if host != '127.0.0.1':
        raise ValueError('Only 127.0.0.1 may be bound')
    if allow_live is not True:
        raise ValueError('Explicit --allow-live is required')
    if type(port) is not int or not 0 <= port <= 65535:
        raise ValueError('Invalid loopback port')
    return HTTPServer((host, port), make_handler(service or AppTaskService(memory=None), catalogue, output_path))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-live', action='store_true', help='Explicitly permit paid real-model calls')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--catalogue', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='Bounded diagnostic JSONL')
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env')
    args = parser.parse_args(argv)
    if not args.allow_live:
        parser.error('Explicit --allow-live is required; no server was started')
    if not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    if not args.env_file.is_file():
        parser.error('Experiment environment file is missing')
    from dotenv import load_dotenv
    load_dotenv(args.env_file, override=True)
    if not os.getenv('GEMINI_API_KEY', '').strip():
        parser.error('Gemini key is not configured')
    try:
        model_name()  # Validate configuration without exposing the key or making a request.
        catalogue = load_catalogue(args.catalogue)
        server = create_server(catalogue=catalogue, output_path=args.output,
                               port=args.port, allow_live=True)
    except (OSError, ValueError):
        parser.error('Invalid catalogue, model configuration or loopback binding')
    print(json.dumps(dict(status='ready', binding=f'127.0.0.1:{args.port}',
                          schema_version=VERSION, memory_enabled=False)), flush=True)
    try:
        server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
