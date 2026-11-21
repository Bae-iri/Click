"""Opt-in loopback facade for a frozen, real multi-app public-navigation study.

This is a separate test endpoint, not an authentication bypass for production.
It receives the native observation unchanged: no virtual-clock translation,
oracle, preparation Intent, fault condition or task-world state enters the planner.
"""
import argparse
from dataclasses import dataclass
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import sys
from threading import Condition, Lock
from time import monotonic, time
from uuid import uuid4

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))
sys.path.insert(0, str(BACKEND / 'scripts'))
from fastapi import HTTPException
from app.api.v1.endpoints.app_guide import task_request_budget
from app.schemas.app_task import AppTaskRequest
from app.services.app_task import AppTaskService, VALIDATION_FIELDS, VALIDATION_TYPES
from app.services.app_task_capabilities import ActionCapabilities
from app.services.app_guide import public_label, PRIVATE, BOUNDARY
from app.services.goal_llm import _unique_object
from serve_meta_controller_experiment import diagnostic_record, MAX_BODY_BYTES, TASK_PATH
from serve_meta_controller_synthetic_experiment import (
    reference, _header_reference, SERVICE_CODES, SERVICE_KINDS, SERVICE_STAGES)

VERSION = 'local-meta-controller-public-apps-facade.v1'
EVIDENCE_MODE = 'live_native_public_apps'
AUTHORIZATION = 'Bearer local-meta-public-apps-experiment'
MANIFEST_VERSION = 'click.native-public-apps.v1'
DRAIN_TIMEOUT_MS = 12000
ERROR_CODES = frozenset(('not_found', 'test_authorization_required', 'bounded_content_length_required',
    'body_too_large', 'json_body_required', 'request_read_timeout', 'invalid_app_task_request',
    'outside_frozen_public_apps_catalogue', 'reference_mismatch', 'invalid_app_task_budget',
    'public_apps_experiment_service_error', 'public_apps_experiment_internal_error',
    'service_identity_mismatch', 'unsafe_navigation_proposal'))
_RESOURCE = re.compile(r'(?:[A-Za-z_][A-Za-z0-9_]*)(?:\.[A-Za-z_][A-Za-z0-9_]*)*:id/[A-Za-z0-9_./-]{1,250}')
_PACKAGE = re.compile(r'[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+')
_VALUE_CONTROL = re.compile(r'switch|toggle|seekbar|seek_bar|slider|checkbox|check_box|radio_button|edit_text', re.I)


@dataclass(frozen=True)
class PublicAppContract:
    package: str
    goals: frozenset[str]
    public_labels: frozenset[str]
    public_resources: frozenset[str]
    navigation_controls: frozenset[tuple[str, str]]

    @property
    def navigation_labels(self):return frozenset(label for label, _ in self.navigation_controls)


@dataclass(frozen=True)
class PublicAppsContract:
    apps: tuple[PublicAppContract, ...]
    manifest_sha256: str

    def for_request(self, request):
        return next((app for app in self.apps if request.goal in app.goals and request.screen.package == app.package), None)


def _resource_shape(value):
    return isinstance(value, str) and len(value) <= 300 and (not value or _RESOURCE.fullmatch(value))


def _public_resource(value):return _resource_shape(value) and not PRIVATE.search(value)


def _public_text(value, *, empty=False, strict=False):
    return isinstance(value, str) and (empty and value == '' or public_label(value) == value and bool(value)) and (
        not strict or not PRIVATE.search(value) and not BOUNDARY.search(value))


def load_contract(path):
    """Freeze exactly three apps and two distinct public navigation goals per app.

    Only app-level public observation and executable selectors enter the service.
    Preparation routes, independent oracles and assigned faults never do.
    """
    raw = Path(path).read_bytes();data = json.loads(raw, object_pairs_hook=_unique_object)
    if data.get('schema_version') != MANIFEST_VERSION:raise ValueError('Public apps v1 catalogue required')
    apps, tasks = data.get('apps'), data.get('tasks')
    if not isinstance(apps, list) or len(apps) != 3 or not isinstance(tasks, list) or len(tasks) != 6:
        raise ValueError('Exactly three apps and six tasks required')
    by_package={};seen_ids=set();seen_goals=set();goals={}
    for app in apps:
        if not isinstance(app, dict):raise ValueError('Invalid app')
        package=app.get('package')
        if not isinstance(package,str) or len(package)>200 or not _PACKAGE.fullmatch(package) or package in by_package:
            raise ValueError('Unique exact packages required')
        by_package[package]=app;goals[package]=set()
    for task in tasks:
        if not isinstance(task,dict):raise ValueError('Invalid task')
        identity,goal,package=task.get('task_id'),task.get('goal'),task.get('package')
        if (not isinstance(identity,str) or not re.fullmatch(r'[A-Za-z0-9_]{1,80}',identity) or identity in seen_ids
                or not isinstance(goal,str) or not 1<=len(goal)<=200 or goal!=goal.strip() or goal in seen_goals
                or package not in by_package):raise ValueError('Unique scoped goals and IDs required')
        seen_ids.add(identity);seen_goals.add(goal);goals[package].add(goal)
    contracts=[]
    for package,app in by_package.items():
        if len(goals[package])!=2:raise ValueError('Exactly two tasks per app required')
        labels,resources,controls=app.get('public_labels'),app.get('public_resources'),app.get('navigation_controls')
        if (not isinstance(labels,list) or not 1<=len(labels)<=400 or not all(_public_text(x,empty=True) for x in labels)
                or not isinstance(resources,list) or not 1<=len(resources)<=400 or not all(_resource_shape(x) for x in resources)
                or not isinstance(controls,list) or not 1<=len(controls)<=200):raise ValueError('Frozen public observations and navigation controls required')
        pairs=set()
        for control in controls:
            if (not isinstance(control,dict) or set(control)!={'label','resource'}
                    or not _public_text(control['label'],strict=True) or control['label'] not in labels
                    or control['resource'] not in resources or not _public_resource(control['resource'])
                    or _VALUE_CONTROL.search(control['resource'])):raise ValueError('Exact public navigation selector required')
            pair=(control['label'],control['resource'])
            if pair in pairs:raise ValueError('Duplicate navigation selector')
            pairs.add(pair)
        contracts.append(PublicAppContract(package,frozenset(goals[package]),frozenset(labels),frozenset(resources),frozenset(pairs)))
    return PublicAppsContract(tuple(contracts),sha256(raw).hexdigest())


def allowed(request, contract):
    app=contract.for_request(request)
    if app is None or request.screen.status!='public' or any(x.package!=app.package for x in request.apps):return False
    if request.state.completion and any(check.package!=app.package or not _public_text(check.label,empty=True,strict=True)
            or not _public_resource(check.resource) for check in request.state.completion.checks):return False
    return all(node.source=='accessibility' and not node.has_value and node.label in app.public_labels
        and _public_text(node.label,empty=True) and _resource_shape(node.resource) and node.resource in app.public_resources
        and (not node.editable or node.role=='text_field')
        and (node.role!='text_field' or node.label=='검색어 입력' and _public_resource(node.resource))
        for node in request.screen.nodes)


def navigation_capabilities(request, contract):
    app=contract.for_request(request)
    if app is None or request.screen.status!='public':return ActionCapabilities(frozenset())
    pairs={('back','')}
    if any(x.package==app.package for x in request.apps):pairs.add(('open_app',app.package))
    for node in request.screen.nodes:
        if node.source!='accessibility' or node.editable or node.has_value:continue
        if (node.scrollable and node.role=='list' and (node.label,node.resource) in app.navigation_controls
                and not _VALUE_CONTROL.search(node.resource)):pairs.update((tool,node.id) for tool in ('scroll_forward','scroll_backward'))
        if (node.clickable and node.role in {'button','tab'} and node.checked is None
                and not _VALUE_CONTROL.search(node.resource) and (node.label,node.resource) in app.navigation_controls):pairs.add(('tap',node.id))
    return ActionCapabilities(frozenset(pairs))


def navigation_action_allowed(request, response, contract):
    app=contract.for_request(request);action=response.action
    if app is None:return False
    if action is None:return True
    if action.package not in ('',app.package) or action.expectation.checked is not None:return False
    if action.tool=='open_app':return action.package==app.package and any(x.package==app.package for x in request.apps)
    if action.tool=='back':return action.expectation.selected is None
    node=next((node for node in request.screen.nodes if node.id==action.target_id),None)
    if node is None or action.bounds!=node.bounds:return False
    if action.tool in {'scroll_forward','scroll_backward'}:
        return ((action.tool,node.id) in navigation_capabilities(request,contract).pairs and action.expectation.selected is None)
    return (action.tool=='tap' and ('tap',node.id) in navigation_capabilities(request,contract).pairs
        and (action.expectation.selected is None or node.role in {'button','tab'} and node.selected is not None))


def _diagnostic_metadata(request, response, record, elapsed):
    diagnostic = response.diagnostic if isinstance(response.diagnostic, dict) else {}
    compatible = dict(diagnostic)
    for key in ('model', 'reason', 'provider_cause', 'error_type'):
        if not isinstance(compatible.get(key), str):compatible[key] = None
    record.update(diagnostic_record(request, response.model_copy(update={'diagnostic': compatible}), elapsed))
    identity_bound=(response.request_id==request.request_id and response.task_id==request.state.task_id
        and response.observation_id==request.screen.observation_id and response.fingerprint==request.screen.fingerprint
        and response.requires_confirmation is True)
    if identity_bound:
        for key,permitted in (('planning_mode',{'next_action','goal_first','result_only'}),
                ('goal_assessment',{'satisfied','not_satisfied','uncertain'})):
            value=diagnostic.get(key)
            if isinstance(value,str) and value in permitted:record[key]=value
    called = diagnostic.get('called')
    if called is True:
        record['model_call_state'] = 'observed'
    elif called is False:
        record.update(model_call_state='not_started', model_calls=0, model_wait_ms=0)
    else:
        record.update(model_call_state='unknown', model_calls=None, model_wait_ms=None)
    for key, permitted in (('reason_code', SERVICE_CODES), ('failure_stage', SERVICE_STAGES), ('failure_kind', SERVICE_KINDS)):
        value = diagnostic.get(key)
        if isinstance(value, str) and value in permitted:
            record['service_' + key] = value
    for key, permitted in (('completion_grounding', {'deferred_actionable_proof', 'deferred_unobserved_result', 'deferred_invalid_contract','deferred_goal_not_satisfied'}),
            ('completion_deferred_reason', {'completion_contract_invalid'})):
        if isinstance(diagnostic.get(key), str) and diagnostic[key] in permitted:
            record[key] = diagnostic[key]
    budget = diagnostic.get('provider_budget_ms')
    if type(budget) in (int, float) and 0 <= budget <= 9000:
        record['provider_budget_ms'] = budget
    if diagnostic.get('provider_attempt_limit') == 1:
        record['provider_attempt_limit'] = 1
    for key in ('recovery_context_used','capabilities_applied','allowed_actions_context_used'):
        if type(diagnostic.get(key)) is bool:record[key] = diagnostic[key]
    for key, high in (('allowed_action_count',322),('recovery_blocked_action_count',16),('recovery_attempt',3)):
        value = diagnostic.get(key)
        if (type(value) is int and (1 if key == 'recovery_attempt' else 0) <= value <= high
                and (key != 'recovery_attempt' or diagnostic.get('recovery_context_used') is True)):
            record[key] = value
    policy, reason = diagnostic.get('recovery_policy'), diagnostic.get('recovery_reason')
    attempt = diagnostic.get('recovery_attempt')
    if (diagnostic.get('recovery_context_used') is True and policy in {'mc_v0','mc_v1','mc_v2'}
            and reason in {'accepted_action_unconfirmed','observed_transition_unconfirmed'}
            and type(attempt) is int and 1 <= attempt <= (2 if reason == 'observed_transition_unconfirmed' else 3)
            and (reason != 'observed_transition_unconfirmed' or policy in {'mc_v1','mc_v2'})):
        record.update(recovery_policy=policy,recovery_reason=reason)
    if diagnostic.get('called') is True:
        for key in ('input_tokens','output_tokens','thinking_tokens','total_tokens'):
            value=diagnostic.get(key)
            if type(value) is int and 0<=value<=1000000:record[key]=value
    errors = diagnostic.get('validation_errors')
    if isinstance(errors, list):
        clean = []
        for error in errors[:4]:
            if not isinstance(error, dict):
                continue
            field = error.get('field')
            parts = field.split('.')[:3] if isinstance(field, str) and len(field) <= 100 else ['other']
            field = '.'.join(part if part in VALIDATION_FIELDS or part in {'index', 'other', 'contract'} else 'other' for part in parts)
            kind = error.get('type')
            clean.append(dict(field=field or 'contract', type=kind if isinstance(kind, str) and kind in VALIDATION_TYPES else 'other'))
        if clean:
            record['validation_errors'] = clean
    record['provider_error'] = record.get('provider_error') or diagnostic.get('failure_kind') == 'provider'
    record['plan_or_parse_error'] = record.get('plan_or_parse_error') or diagnostic.get('failure_kind') in ('parse', 'plan', 'configuration')


def make_handler(service, contract, output_path, *, drain_timeout_ms=DRAIN_TIMEOUT_MS):
    if type(drain_timeout_ms) is not int or not 1 <= drain_timeout_ms <= DRAIN_TIMEOUT_MS:
        raise ValueError('Drain timeout must be bounded to 12000 ms')
    output, write_lock, activity = Path(output_path), Lock(), Condition()
    active_posts, receipt_failed = 0, False

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        server_version = VERSION

        def setup(self):
            super().setup();self.connection.settimeout(15)

        def log_message(self, *_args):
            pass

        def reply(self, status, body, record=None):
            payload = json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(payload)))
            self.send_header('Cache-Control', 'no-store');self.send_header('Connection', 'close')
            if record is not None:
                for key, header in (('server_ref', 'X-Click-Server-Ref'), ('request_ref', 'X-Click-Request-Ref'), ('task_ref', 'X-Click-Task-Ref')):
                    if record.get(key):self.send_header(header, record[key])
            self.end_headers();self.close_connection = True;self.wfile.write(payload)

        def do_GET(self):
            if self.path == '/drain':
                if self.headers.get('Authorization') != AUTHORIZATION:
                    self.reply(401, {'error': 'test_authorization_required'});return
                deadline = monotonic() + drain_timeout_ms / 1000
                with activity:
                    while active_posts:
                        remaining = deadline - monotonic()
                        if remaining <= 0:break
                        activity.wait(remaining)
                    idle = active_posts == 0 and not receipt_failed
                self.reply(200 if idle else 503, dict(status='idle' if idle else 'busy',
                    schema_version=VERSION, evidence_mode=EVIDENCE_MODE,
                    terminal_receipts_complete=idle, provider_connectivity='NOT_CHECKED_BY_DRAIN'))
                return
            if self.path != '/health':
                self.reply(404, {'error': 'not_found'});return
            self.reply(200, dict(status='ready', schema_version=VERSION, evidence_mode=EVIDENCE_MODE,
                catalogue_ref=contract.manifest_sha256, provider_connectivity='NOT_CHECKED_BY_HEALTH',
                memory_enabled=False, native_clock_preserved=True))

        def _invoke(self, request, record):
            started = monotonic()
            record.update(service_state='entered', model_call_state='unknown', model_calls=None, model_wait_ms=None)
            try:
                response = service.step(request, action_capabilities=navigation_capabilities(request, contract))
                record['service_state'] = 'returned'
                if not (response.protocol_version == 'app-task.v2' and response.requires_confirmation is True
                        and response.request_id == request.request_id and response.task_id == request.state.task_id
                        and response.observation_id == request.screen.observation_id
                        and response.fingerprint == request.screen.fingerprint):
                    return 502, {'error': 'service_identity_mismatch'}
                _diagnostic_metadata(request, response, record, (monotonic() - started) * 1000)
                if not navigation_action_allowed(request, response, contract):
                    record.update(navigation_proposal_blocked=True, plan_or_parse_error=True,
                        service_failure_kind='plan', service_failure_stage='action_contract',
                        service_reason_code='unsafe_navigation_proposal')
                    return 422, {'error': 'unsafe_navigation_proposal'}
                return 200, response.model_dump(mode='json')
            except Exception:
                record['service_state'] = 'raised'
                return 500, {'error': 'public_apps_experiment_service_error'}
            finally:
                record['service_elapsed_ms'] = round((monotonic() - started) * 1000, 3)

        def _post(self, record):
            if self.path != TASK_PATH:return 404, {'error': 'not_found'}
            if self.headers.get('Authorization') != AUTHORIZATION:return 401, {'error': 'test_authorization_required'}
            size = self.headers.get('Content-Length', '')
            if self.headers.get('Transfer-Encoding') is not None or not re.fullmatch(r'[0-9]{1,10}', size):
                return 411, {'error': 'bounded_content_length_required'}
            length = int(size)
            if length > MAX_BODY_BYTES:return 413, {'error': 'body_too_large'}
            if length <= 0 or self.headers.get_content_type() != 'application/json':return 415, {'error': 'json_body_required'}
            try:
                body = self.rfile.read(length)
                if len(body) != length:raise ValueError('truncated_body')
                parsed = json.loads(body, object_pairs_hook=_unique_object)
                if isinstance(parsed, dict):
                    state = parsed.get('state')
                    derived = dict(request_ref=reference(parsed.get('request_id')),
                        task_ref=reference(state.get('task_id')) if isinstance(state, dict) else None)
                    if any(record.get(key) and value and record[key] != value for key, value in derived.items()):
                        return 400, {'error': 'reference_mismatch'}
                    record.update({key: value for key, value in derived.items() if value});record['episode_ref'] = record.get('task_ref')
                request = AppTaskRequest.model_validate(parsed)
            except TimeoutError:return 408, {'error': 'request_read_timeout'}
            except Exception:return 422, {'error': 'invalid_app_task_request'}
            if not allowed(request, contract):return 403, {'error': 'outside_frozen_public_apps_catalogue'}
            # Real native clocks and observations are used verbatim. Budget
            # handling is the same production helper, never a freshness rewrite.
            record['observation_age_ms'] = round(max(-120000, min(120000, time() * 1000 - request.screen.captured_at_ms)), 3)
            try:
                with task_request_budget(self):return self._invoke(request, record)
            except HTTPException:return 400, {'error': 'invalid_app_task_budget'}

        def do_POST(self):
            nonlocal active_posts, receipt_failed
            with activity:active_posts += 1
            recorded = False
            try:
                self._do_post_terminal();recorded = True
            finally:
                with activity:
                    if not recorded:receipt_failed = True
                    active_posts -= 1
                    activity.notify_all()

        def _do_post_terminal(self):
            started = monotonic()
            record = dict(server_ref=reference(str(uuid4())), request_ref=_header_reference(self.headers.get('X-Click-Request-Ref')),
                task_ref=_header_reference(self.headers.get('X-Click-Task-Ref')), service_state='not_started',
                model_call_state='not_started', model_calls=0, model_wait_ms=0, response_delivery='not_attempted')
            record['memory_enabled'] = False
            record['episode_ref'] = record['task_ref']
            try:status, body = self._post(record)
            except TimeoutError:status, body = 408, {'error': 'request_read_timeout'}
            except Exception:status, body = 500, {'error': 'public_apps_experiment_internal_error'}
            error = body.get('error') if isinstance(body, dict) else None
            record.update(schema_version=VERSION, event='request_terminal', evidence_mode=EVIDENCE_MODE,
                real_app=True, synthetic_ui=False, clock_translation='none_native_wall_time', http_status=status,
                error_code=error if error in ERROR_CODES else None,
                outcome='service_response' if status == 200 else 'service_exception' if record['service_state'] == 'raised'
                    else 'invalid_service_response' if error == 'service_identity_mismatch'
                    else 'blocked_navigation_action' if error == 'unsafe_navigation_proposal' else 'rejected')
            if status != 200:
                body = dict(body, server_ref=record['server_ref'], request_ref=record.get('request_ref'),
                    task_ref=record.get('task_ref'), model_call_state=record['model_call_state'],
                    model_calls=record['model_calls'], model_wait_ms=record['model_wait_ms'])
            try:self.reply(status, body, record);record['response_delivery'] = 'sent'
            except OSError:record['response_delivery'] = 'client_disconnected'
            finally:
                record['request_total_handler_ms'] = round((monotonic() - started) * 1000, 3)
                with write_lock:
                    with output.open('a', encoding='utf-8') as stream:stream.write(json.dumps(record, ensure_ascii=False) + '\n')

    return Handler


def create_server(*, manifest, output_path, port=8769, host='127.0.0.1', allow_live=False, service=None,
        drain_timeout_ms=DRAIN_TIMEOUT_MS):
    if host != '127.0.0.1' or allow_live is not True:
        raise ValueError('Explicit allow_live and loopback-only binding required')
    if type(port) is not int or not 0 <= port <= 65535:raise ValueError('Invalid loopback port')
    contract = load_contract(manifest)
    output = Path(output_path);output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('a', encoding='utf-8'):pass
    return ThreadingHTTPServer((host, port), make_handler(service or AppTaskService(memory=None), contract, output,
        drain_timeout_ms=drain_timeout_ms))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-live', action='store_true');parser.add_argument('--port', type=int, default=8769)
    parser.add_argument('--manifest', type=Path, required=True);parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    server = create_server(manifest=args.manifest, output_path=args.output, port=args.port, allow_live=args.allow_live)
    print(json.dumps(dict(status='listening', host='127.0.0.1', port=server.server_port, schema_version=VERSION,
        evidence_mode=EVIDENCE_MODE)), flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()


if __name__ == '__main__':main()
