"""Explicit loopback facade for paid Gemini calls against the frozen synthetic UI.

This NEW test-only executable does not alter the native-app experiment allowlist
or production service. Public fixture controls are validated against the frozen
manifest. The actual AppTaskService sees only the current visible observation,
goal and controller history: no world state, fault condition or oracle route.
"""
import argparse
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path
import re
import sys
from time import monotonic, time
from uuid import uuid4

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))
sys.path.insert(0, str(BACKEND / 'scripts'))
from app.schemas.app_task import AppTaskRequest
from app.services.app_task import AppTaskService, PLAN_ERROR_CODES, VALIDATION_FIELDS, VALIDATION_TYPES
from app.services.app_task_provider import REASON_CODES as PROVIDER_CODES
from app.services.app_guide import public_label, PRIVATE, BOUNDARY
from serve_meta_controller_experiment import diagnostic_record, MAX_BODY_BYTES, TASK_PATH

VERSION = 'local-meta-controller-synthetic-facade.v2'
PACKAGE = 'org.click.experiment.fixture'
AUTHORIZATION = 'Bearer local-meta-synthetic-experiment'
DEFAULT_MANIFEST = BACKEND.parent / 'experiments/metacontroller/closed-loop-fixture-v1.json'
ERROR_CODES = frozenset(('not_found', 'test_authorization_required', 'bounded_content_length_required',
    'body_too_large', 'json_body_required', 'request_read_timeout', 'invalid_app_task_request',
    'outside_frozen_public_fixture', 'reference_mismatch', 'synthetic_experiment_service_error',
    'synthetic_experiment_internal_error', 'service_identity_mismatch'))
SERVICE_STAGES = frozenset(('none', 'observation', 'configuration', 'provider', 'provider_response',
    'model_output', 'plan_validation', 'completion_contract', 'subgoal_contract', 'action_contract'))
SERVICE_KINDS = frozenset(('observation', 'configuration', 'provider', 'parse', 'plan'))
SERVICE_CODES = frozenset(PLAN_ERROR_CODES) | PROVIDER_CODES | frozenset(('protected_observation', 'public_observation_unavailable',
    'stale_observation', 'no_public_controls', 'no_allowed_actions', 'unusable_goal', 'bound_result_available',
    'invalid_model_configuration', 'missing_api_key', 'provider_timeout', 'provider_http_error',
    'provider_transport_error', 'contract_validation_failed', 'invalid_json', 'provider_response_invalid',
    'model_output_invalid', 'plan_validation_failed', 'result_evidence_candidate', 'result_evidence_unavailable'))


def reference(value):
    """Hash bounded identifiers, never the request body or an exception message."""
    return sha256(value.encode()).hexdigest()[:24] if isinstance(value, str) and 0 < len(value) <= 100 else None


def _header_reference(value):
    return value if isinstance(value, str) and re.fullmatch(r'[0-9a-f]{24}', value) else None


def _public_prediction(check):
    # A future alias is a model hypothesis, not a current observation. Keep the
    # strict observed-node whitelist below; predictions instead use bounded
    # same-package public selectors, never sensitive text/resources.
    return (check.package == PACKAGE
        and (not check.label or public_label(check.label) == check.label)
        and not PRIVATE.search(check.label + ' ' + check.resource)
        and not BOUNDARY.search(check.label)
        and (not check.resource or re.fullmatch(re.escape(PACKAGE) + r':id/[A-Za-z0-9_./]{1,200}', check.resource)))


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
    if request.state.completion and any(not _public_prediction(check) for check in request.state.completion.checks):
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

        def reply(self, status, body, record=None):
            payload = json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            if record is not None:
                for key, header in (('server_ref', 'X-Click-Server-Ref'), ('request_ref', 'X-Click-Request-Ref'), ('task_ref', 'X-Click-Task-Ref')):
                    if record.get(key):
                        self.send_header(header, record[key])
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

        def _post(self, record):
            if self.path != TASK_PATH:
                return 404, {'error': 'not_found'}
            if self.headers.get('Authorization') != AUTHORIZATION:
                return 401, {'error': 'test_authorization_required'}
            size = self.headers.get('Content-Length', '')
            if self.headers.get('Transfer-Encoding') is not None or not re.fullmatch(r'[0-9]{1,10}', size):
                return 411, {'error': 'bounded_content_length_required'}
            length = int(size)
            if length > MAX_BODY_BYTES:
                return 413, {'error': 'body_too_large'}
            if length <= 0 or self.headers.get_content_type() != 'application/json':
                return 415, {'error': 'json_body_required'}
            try:
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError('truncated_body')
                parsed = json.loads(body)
                if isinstance(parsed, dict):
                    state = parsed.get('state')
                    derived = {'request_ref': reference(parsed.get('request_id')),
                        'task_ref': reference(state.get('task_id')) if isinstance(state, dict) else None}
                    if any(record.get(key) and value and record[key] != value for key, value in derived.items()):
                        return 400, {'error': 'reference_mismatch'}
                    record.update({key: value for key, value in derived.items() if value})
                    record['episode_ref'] = record.get('task_ref')
                request = AppTaskRequest.model_validate(parsed)
            except TimeoutError:
                return 408, {'error': 'request_read_timeout'}
            except Exception:
                return 422, {'error': 'invalid_app_task_request'}
            if not allowed(request, contract):
                return 403, {'error': 'outside_frozen_public_fixture'}
            # Only transport freshness changes. The controller keeps true virtual
            # observations and timestamps for native pre/postchecks. Fingerprint,
            # observation id, visible controls and all decision history are intact.
            wire_request = request.model_copy(update={'screen': request.screen.model_copy(
                update={'captured_at_ms': int(time() * 1000)})})
            started = monotonic()
            record.update(service_state='entered', model_call_state='unknown', model_calls=None, model_wait_ms=None)
            try:
                response = service.step(wire_request)
                record['service_state'] = 'returned'
                if not (response.protocol_version == 'app-task.v2' and response.requires_confirmation is True
                        and response.request_id == request.request_id and response.task_id == request.state.task_id
                        and response.observation_id == request.screen.observation_id
                        and response.fingerprint == request.screen.fingerprint):
                    return 502, {'error': 'service_identity_mismatch'}
                record.update(diagnostic_record(request, response, (monotonic() - started) * 1000))
                diagnostic = response.diagnostic if isinstance(response.diagnostic, dict) else {}
                if diagnostic.get('called') is True:
                    record['model_call_state'] = 'observed'
                elif diagnostic.get('called') is False:
                    record.update(model_call_state='not_started', model_calls=0, model_wait_ms=0)
                else:
                    record.update(model_call_state='unknown', model_calls=None, model_wait_ms=None)
                # New bounded service diagnostics are enumerated by their own
                # code, not exception text. Unknown diagnostic fields stay out.
                for key, permitted in (('reason_code', SERVICE_CODES), ('failure_stage', SERVICE_STAGES), ('failure_kind', SERVICE_KINDS)):
                    value = diagnostic.get(key)
                    if isinstance(value, str) and value in permitted:
                        record['service_' + key] = value
                grounding=diagnostic.get('completion_grounding')
                if grounding in {'deferred_actionable_proof','deferred_unobserved_result','deferred_invalid_contract'}:
                    record['completion_grounding']=grounding
                if diagnostic.get('completion_deferred_reason')=='completion_contract_invalid':
                    record['completion_deferred_reason']='completion_contract_invalid'
                budget=diagnostic.get('provider_budget_ms')
                if type(budget) in (int,float) and 0<=budget<=9000:record['provider_budget_ms']=budget
                if diagnostic.get('provider_attempt_limit')==1:record['provider_attempt_limit']=1
                errors = diagnostic.get('validation_errors')
                if isinstance(errors, list):
                    clean = []
                    for error in errors[:4]:
                        if not isinstance(error, dict):
                            continue
                        field = error.get('field')
                        parts = field.split('.')[:3] if isinstance(field, str) and len(field) <= 100 else ['other']
                        field = '.'.join(p if p in VALIDATION_FIELDS or p in {'index', 'other', 'contract'} else 'other' for p in parts)
                        kind = error.get('type')
                        clean.append(dict(field=field or 'contract', type=kind if kind in VALIDATION_TYPES else 'other'))
                    if clean:
                        record['validation_errors'] = clean
                record['provider_error'] = record.get('provider_error') or diagnostic.get('failure_kind') == 'provider'
                record['plan_or_parse_error'] = record.get('plan_or_parse_error') or diagnostic.get('failure_kind') in {'parse', 'plan', 'configuration'}
                return 200, response.model_dump(mode='json')
            except Exception:
                record['service_state'] = 'raised'
                return 500, {'error': 'synthetic_experiment_service_error'}

        def do_POST(self):
            started = monotonic()
            record = dict(server_ref=reference(str(uuid4())),
                request_ref=_header_reference(self.headers.get('X-Click-Request-Ref')),
                task_ref=_header_reference(self.headers.get('X-Click-Task-Ref')),
                service_state='not_started', model_call_state='not_started', model_calls=0,
                model_wait_ms=0, response_delivery='not_attempted')
            record['episode_ref'] = record['task_ref']
            try:
                status, body = self._post(record)
            except TimeoutError:
                status, body = 408, {'error': 'request_read_timeout'}
            except Exception:
                status, body = 500, {'error': 'synthetic_experiment_internal_error'}
            error = body.get('error') if isinstance(body, dict) else None
            record.update(schema_version=VERSION, event='request_terminal',
                evidence_mode='live_model_synthetic_closed_loop', synthetic_ui=True, real_app=False,
                transport_clock_translation='virtual_ui_to_real_wall_freshness_only',
                http_status=status, error_code=error if error in ERROR_CODES else None,
                outcome='service_response' if status == 200 else 'service_exception' if record['service_state'] == 'raised'
                    else 'invalid_service_response' if error == 'service_identity_mismatch' else 'rejected')
            if status != 200:
                body = dict(body, server_ref=record['server_ref'], request_ref=record.get('request_ref'),
                    task_ref=record.get('task_ref'), model_call_state=record['model_call_state'])
            try:
                self.reply(status, body, record)
                record['response_delivery'] = 'sent'
            except OSError:
                record['response_delivery'] = 'client_disconnected'
            finally:
                record['request_total_handler_ms'] = round((monotonic() - started) * 1000, 3)
                output.parent.mkdir(parents=True, exist_ok=True)
                with output.open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps(record, ensure_ascii=False) + '\n')

    return Handler


def create_server(*, output_path, port=8767, host='127.0.0.1', allow_live=False,
                  manifest=DEFAULT_MANIFEST, service=None):
    if host != '127.0.0.1' or allow_live is not True:
        raise ValueError('Explicit allow_live and loopback-only binding required')
    if type(port) is not int or not 0 <= port <= 65535:
        raise ValueError('Invalid loopback port')
    # Refuse to start a paid-call facade without a writable telemetry sink.
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('a', encoding='utf-8'):
        pass
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
