"""Opt-in, loopback-only facade for the real-device memory-reuse experiment.

Arms (fixed per runtime task by a harness-only registration):
  A  no memory: planner only (learning off on device, no server recall)
  B  current production memory: device route cache + AI2 recall, exact goal key
  C  B + server-side goal normalization (frozen catalogue mapping, upper bound)

Production code is called unchanged: ``AppTaskService.step`` (Gemini planner and
its server-side recall branch), ``analyze_app_task`` (AI2) and
``AppTaskExperienceService._ground`` (recall grounding). Only DB persistence is
replaced by per-arm in-memory stores. Shadow variants (no failure memory,
one-task support, normalization) are computed at every recall and logged; they
never influence the served response.

No raw goal, label, screen or model output is written to disk. Logs carry only
hashes, counts, tools and arm labels.
"""
import argparse
from collections import defaultdict
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import sys
import threading
import time
import uuid

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))
from app.schemas.app_task import AppTaskRequest
from app.schemas.app_task_learning import (TaskExperienceBatch, TaskExperienceReceipt, TaskEnvironment,
    TaskMemoryResponse, TaskUserResult, TaskUserResultReceipt)
from app.services.app_task import AppTaskService
from app.services.app_task_experience import AppTaskExperienceService
from app.services.app_task_learning_policy import POLICY_HASH, digest, target_key, screen_key
from ai_agent.graph_proposal.workflows.app_tasks import analyze_app_task

VERSION = 'local-memory-experiment-facade.v1'
TEST_AUTHORIZATION = 'Bearer local-meta-experiment'
TASK_PATH = '/api/v1/agent/app-guide/task'
MEMORY_PATH, EVENTS_PATH, RESULT_PATH = TASK_PATH + '/memory', TASK_PATH + '/events', TASK_PATH + '/result'
REGISTER_PATH, STATUS_PATH = '/experiment/register', '/experiment/status'
MAX_BODY_BYTES = 128 * 1024
ALLOWED_PACKAGES = frozenset(('com.android.settings', 'com.sec.android.app.clockpackage',
    'com.android.chrome', 'com.google.android.youtube'))
ARMS = ('A', 'B', 'C')
MEMORY_ARMS = ('B', 'C')
VARIANTS = ('exact', 'normalized', 'exact_no_failure_memory', 'exact_support_one')
SERVED = {'B': 'exact', 'C': 'normalized'}


def _ref(value):
    return sha256(str(value).encode()).hexdigest()[:24]


def _bounded_ms(value):
    return round(value, 3) if type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 120000 else None


def load_catalogue(path):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    tasks = data.get('tasks') if isinstance(data, dict) else None
    if not isinstance(tasks, list) or not tasks:
        raise ValueError('Catalogue must contain tasks')
    allowed, canonical, task_of = set(), {}, {}
    for task in tasks:
        if task.get('app_id') not in ALLOWED_PACKAGES:
            raise ValueError('Catalogue app outside allowlist')
        goals = [task.get('goal'), task.get('mirror_goal')]
        if any(not isinstance(g, str) or not 1 <= len(g) <= 200 or g != g.strip() for g in goals) or goals[0] == goals[1]:
            raise ValueError('Each task needs distinct exact goal and mirror_goal strings')
        for g in goals:
            if digest(g) in canonical:
                raise ValueError('Duplicate goal string')
            allowed.add((task['app_id'], g))
            canonical[digest(g)] = digest(goals[0])
            task_of[digest(g)] = task['task_id']
    return frozenset(allowed), canonical, task_of


class ExperimentState:
    """Per-arm, owner-scoped stores mirroring runtime_events + AI2 receipts."""

    def __init__(self, canonical, task_of, log_dir, clock=time.time):
        self.canonical, self.task_of, self.clock = canonical, task_of, clock
        self.log_dir = Path(log_dir)
        self.lock = threading.RLock()
        started = clock()
        self.consent = {arm: dict(id=str(uuid.uuid4()), recorded_at_ms=int(started * 1000) - 1000) for arm in MEMORY_ARMS}
        self.records = {arm: defaultdict(dict) for arm in MEMORY_ARMS}   # experience task -> seq -> record
        self.registered = {}   # runtime task id -> dict(arm, task_id, goal_variant, episode)
        self.experience_of = {}
        self.confirmed = {arm: set() for arm in MEMORY_ARMS}

    def log(self, name, record):
        self.log_dir.mkdir(parents=True, exist_ok=True)
        with self.lock, (self.log_dir / name).open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + '\n')

    # ---- harness registration -------------------------------------------------
    def register(self, body):
        arm = body.get('arm')
        runtime = body.get('runtime_task_id')
        if arm not in ARMS or not isinstance(runtime, str) or not re.fullmatch(r'[0-9a-f-]{36}', runtime):
            raise ValueError('invalid registration')
        with self.lock:
            entry = self.registered.setdefault(runtime, dict(arm=arm, task_id=body.get('task_id'),
                goal_variant=body.get('goal_variant'), episode=body.get('trial_id')))
            if entry['arm'] != arm:
                raise ValueError('arm conflict')
            experience = body.get('experience_task_id')
            if isinstance(experience, str) and re.fullmatch(r'[0-9a-f-]{36}', experience):
                self.experience_of[runtime] = experience
        self.log('registrations.jsonl', dict(at_ms=int(self.clock() * 1000), runtime_ref=_ref(runtime),
            experience_ref=_ref(experience) if isinstance(experience, str) else None, arm=arm,
            task_id=body.get('task_id'), goal_variant=body.get('goal_variant'), trial_id=body.get('trial_id')))
        return dict(status='registered', arm=arm)

    def arm_of(self, runtime_task_id):
        with self.lock:
            entry = self.registered.get(runtime_task_id)
        return entry['arm'] if entry else None

    # ---- events / AI2 ---------------------------------------------------------
    def session_arm(self, session_id):
        match = re.fullmatch(r'memexp-([BC])-[0-9a-zA-Z_.:-]{1,120}', session_id)
        return match.group(1) if match else None

    def ingest(self, batch):
        arm = self.session_arm(batch.session_id)
        if arm is None or batch.consent_id != self.consent[arm]['id']:
            return TaskExperienceReceipt(status='ineligible', acknowledged=[e.event_id for e in batch.events]), arm
        now = self.clock()
        accepted = 0
        with self.lock:
            for event in batch.events:
                body = event.model_dump(mode='json')
                if not max(self.consent[arm]['recorded_at_ms'], (now - 86400) * 1000) <= event.observed_at_ms <= (now + 60) * 1000:
                    continue
                task = self.records[arm][event.task_id]
                existing = task.get(event.sequence)
                if existing:
                    if existing['event_id'] != event.event_id:
                        raise ValueError('EXPERIENCE_SEQUENCE_CONFLICT')
                    continue
                if len(task) >= 66 or any(r['evidence']['goal_key'] != event.goal_key for r in task.values()):
                    raise ValueError('EXPERIENCE_SEQUENCE_CONFLICT')
                task[event.sequence] = dict(event_id=event.event_id, evidence=body, received_at_ms=int(now * 1000))
                accepted += 1
                if event.kind == 'result' and event.outcome == 'user_confirmed':
                    self.confirmed[arm].add(event.task_id)
                    records = [task[q] for q in sorted(task)]
                    try:
                        analysis = analyze_app_task(records, POLICY_HASH)
                        summary = dict(status=analysis.get('status'), route_steps=len(analysis.get('route', [])),
                            step_outcomes=[x['outcome'] for x in analysis.get('steps', [])],
                            effect_evidence=[x.get('effect_evidence') for x in analysis.get('steps', [])])
                    except ValueError as error:
                        summary = dict(status='INVALID', error=str(error)[:60])
                    self.log('analyses.jsonl', dict(arm=arm, experience_ref=_ref(event.task_id),
                        task_id=self.task_of.get(event.goal_key), goal_ref=_ref(event.goal_key), **summary))
                self.log('experience-events.jsonl', dict(arm=arm, experience_ref=_ref(event.task_id),
                    sequence=event.sequence, kind=event.kind, outcome=event.outcome, starts_task=event.starts_task,
                    goal_ref=_ref(event.goal_key), canonical_goal_ref=_ref(self.canonical.get(event.goal_key, event.goal_key)),
                    task_id=self.task_of.get(event.goal_key), subgoal_ref=_ref(event.subgoal_key),
                    screen_ref=_ref(event.screen_key), after_ref=_ref(event.after_key),
                    tool=event.action.tool if event.action else None,
                    target_ref=_ref(event.action.target_key) if event.action else None,
                    expectation=event.action.expectation if event.action else None,
                    observed_at_ms=event.observed_at_ms, received_at_ms=int(now * 1000)))
        return TaskExperienceReceipt(status='accepted', acknowledged=[e.event_id for e in batch.events]), arm

    def analyses(self, arm, normalized=False, failure_memory=True):
        """AI2 analysis per experience task, the shape production recall reads."""
        out = []
        with self.lock:
            tasks = {k: dict(v) for k, v in self.records[arm].items()}
        for task_id, by_seq in tasks.items():
            records = [by_seq[s] for s in sorted(by_seq)]
            if normalized:
                records = [dict(r, evidence=dict(r['evidence'], goal_key=self.canonical.get(
                    r['evidence']['goal_key'], r['evidence']['goal_key']))) for r in records]
            try:
                analysis = analyze_app_task(records, POLICY_HASH)
            except ValueError:
                continue
            if analysis.get('status') != 'ANALYZED':
                continue
            if not failure_memory:
                analysis = dict(analysis, steps=[s for s in analysis['steps'] if s['outcome'] == 'verified'])
            out.append((task_id, analysis))
        return out

    # ---- recall ---------------------------------------------------------------
    def _variant(self, arm, request, env, variant):
        normalized = variant == 'normalized'
        memories = self.analyses(arm, normalized=normalized, failure_memory=variant != 'exact_no_failure_memory')
        if variant == 'exact_support_one':
            memories = memories + [(t + '#support-one', a) for t, a in memories]
        req = request
        if normalized:
            goal = digest(request.goal)
            canonical = self.canonical.get(goal)
            if canonical and canonical != goal:
                req = request.model_copy(update={'goal': _GOALS[canonical]})
        result = TaskMemoryResponse(request_id=request.request_id, observation_id=request.screen.observation_id,
            fingerprint=request.screen.fingerprint, collection_enabled=True, consent_id=self.consent[arm]['id'],
            consent_recorded_at_ms=self.consent[arm]['recorded_at_ms'])
        result = AppTaskExperienceService._ground(result, req, env, memories)
        if variant == 'exact_support_one':
            for e in result.evidence:
                e['supporting_tasks_after_adverse'] = e['supporting_tasks_after_adverse'] // 2
        return result, memories

    @staticmethod
    def _choice(result, request):
        if not result.action:
            return None
        node = next((n for n in request.screen.nodes if n.id == result.action.target_id), None)
        return dict(tool=result.action.tool, target_ref=_ref(target_key(node)) if node else None)

    def recall(self, request, arm, source):
        """Production recall checks, then served + shadow variants; only the served one is returned."""
        screen = request.screen
        base = TaskMemoryResponse(request_id=request.request_id, observation_id=screen.observation_id,
                                  fingerprint=screen.fingerprint)
        if arm not in MEMORY_ARMS:
            base.status = 'disabled'
            return base
        base.collection_enabled = True
        base.consent_id = self.consent[arm]['id']
        base.consent_recorded_at_ms = self.consent[arm]['recorded_at_ms']
        skew = self.clock() * 1000 - screen.captured_at_ms
        reason = None
        env = None
        if not -2000 <= skew <= 15000:
            reason = 'stale_capture'
        elif screen.status != 'public':
            reason = 'not_public'
        else:
            try:
                env = TaskEnvironment.model_validate({'package': screen.package, **request.environment})
                if env.app_version == 'unknown':
                    reason = 'unknown_version'
            except ValueError:
                reason = 'invalid_environment'
        record = dict(at_ms=int(self.clock() * 1000), source=source, arm=arm,
            runtime_ref=_ref(request.state.task_id), screen_ref=_ref(screen_key(screen)),
            goal_ref=_ref(digest(request.goal)), task_id=self.task_of.get(digest(request.goal)),
            completion_known=request.state.completion is not None,
            pending_subgoals=sum(s.status == 'pending' for s in request.state.subgoals),
            path_prefix_len=len(request.path_prefix), served_variant=SERVED[arm], gate=reason, variants={})
        if reason:
            self.log('recalls.jsonl', record)
            return base
        served = base
        for variant in VARIANTS:
            result, memories = self._variant(arm, request, env, variant)
            goal_key = digest(request.goal) if variant != 'normalized' else self.canonical.get(digest(request.goal), digest(request.goal))
            same_goal = [a for _, a in memories if a.get('goal_key') == goal_key]
            record['variants'][variant] = dict(status=result.status, choice=self._choice(result, request),
                memories=len(memories), same_goal_memories=len(same_goal),
                confirmed_routes=sum(bool(a.get('route') and a.get('user_confirmed')) for a in same_goal),
                same_goal_screen_steps=sum(s.get('screen_key') == screen_key(screen) for a in same_goal for s in a['steps']),
                evidence=[{k: e.get(k) for k in ('tool', 'successes', 'rejections', 'unconfirmed', 'supporting_tasks_after_adverse')}
                          for e in result.evidence])
            if variant == SERVED[arm]:
                served = result
        self.log('recalls.jsonl', record)
        return served

    def status(self):
        with self.lock:
            return dict(status='ready', schema_version=VERSION,
                arms={arm: dict(experience_tasks=len(self.records[arm]),
                    events=sum(len(v) for v in self.records[arm].values()),
                    confirmed_tasks=len(self.confirmed[arm])) for arm in MEMORY_ARMS},
                registered=len(self.registered))


_GOALS = {}


class ArmMemory:
    """AppTaskService's memory dependency: user_id is the experiment arm."""

    def __init__(self, state):
        self.state = state

    def recall(self, request, user_id):
        return self.state.recall(request, user_id, 'server_step')


def diagnostic_record(request, response, elapsed_ms, arm, registration):
    diagnostic = response.diagnostic if isinstance(response.diagnostic, dict) else {}
    called = diagnostic.get('called') is True
    model = diagnostic.get('model')
    if not isinstance(model, str) or not re.fullmatch(r'(gemini-[a-zA-Z0-9.-]{1,100}|agent2-experience)', model):
        model = None
    return dict(schema_version=VERSION, at_ms=int(time.time() * 1000), request_ref=_ref(request.request_id),
        runtime_ref=_ref(request.state.task_id), arm=arm,
        task_id=(registration or {}).get('task_id'), goal_variant=(registration or {}).get('goal_variant'),
        trial_id=(registration or {}).get('episode'), model=model, model_calls=int(called),
        model_wait_ms=_bounded_ms(diagnostic.get('latency_ms')) if called else 0,
        request_total_handler_ms=round(elapsed_ms, 3), status=response.status,
        reason=diagnostic.get('reason') if isinstance(diagnostic.get('reason'), str) else None,
        provider_error=diagnostic.get('failure_kind') == 'provider',
        error_type=diagnostic.get('error_type') if isinstance(diagnostic.get('error_type'), str) else None,
        action_tool=response.action.tool if response.action else None,
        completion_bound=response.completion is not None and request.state.completion is None,
        completion_rejected=diagnostic.get('completion_rejected'))


def make_handler(service, state, catalogue):
    step_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        server_version = VERSION

        def setup(self):
            super().setup()
            self.connection.settimeout(20)

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
            if self.path == '/health':
                # Serial provider work: health waits for an in-flight planner request (drain fence).
                with step_lock:
                    self.reply(200, dict(status='ready', schema_version=VERSION, binding='loopback_only'))
            elif self.path == STATUS_PATH:
                self.reply(200, state.status())
            else:
                self.reply(404, {'error': 'not_found'})

        def body(self):
            if self.headers.get('Authorization') != TEST_AUTHORIZATION:
                self.reply(401, {'error': 'test_authorization_required'})
                return None
            size = self.headers.get('Content-Length', '')
            if not re.fullmatch(r'[0-9]{1,10}', size) or not 0 < int(size) <= MAX_BODY_BYTES:
                self.reply(413, {'error': 'invalid_length'})
                return None
            data = self.rfile.read(int(size))
            if len(data) != int(size):
                self.reply(400, {'error': 'truncated'})
                return None
            return data

        def do_POST(self):
            data = self.body()
            if data is None:
                return
            try:
                if self.path == REGISTER_PATH:
                    self.reply(200, state.register(json.loads(data)))
                elif self.path == EVENTS_PATH:
                    receipt, arm = state.ingest(TaskExperienceBatch.model_validate_json(data))
                    self.reply(200, receipt.model_dump(mode='json'))
                elif self.path == RESULT_PATH:
                    result = TaskUserResult.model_validate_json(data)
                    state.log('user-results.jsonl', dict(at_ms=int(time.time() * 1000), arm=state.arm_of(result.task_id),
                        runtime_ref=_ref(result.task_id), outcome=result.outcome))
                    self.reply(200, TaskUserResultReceipt(request_id=result.request_id, session_id=result.session_id,
                        task_id=result.task_id).model_dump(mode='json'))
                elif self.path in (TASK_PATH, MEMORY_PATH):
                    request = AppTaskRequest.model_validate_json(data)
                    if (request.screen.package, request.goal) not in catalogue:
                        self.reply(403, {'error': 'outside_frozen_catalogue'})
                        return
                    arm = state.arm_of(request.state.task_id)
                    if arm is None:
                        self.reply(403, {'error': 'unregistered_task'})
                        return
                    if self.path == MEMORY_PATH:
                        self.reply(200, state.recall(request, arm, 'device_recall').model_dump(mode='json'))
                        return
                    with step_lock:
                        started = time.monotonic()
                        response = service.step(request, user_id=arm if arm in MEMORY_ARMS else None)
                        elapsed = (time.monotonic() - started) * 1000
                    with state.lock:
                        registration = state.registered.get(request.state.task_id)
                    state.log('provider-calls.jsonl', diagnostic_record(request, response, elapsed, arm, registration))
                    self.reply(200, response.model_dump(mode='json'))
                else:
                    self.reply(404, {'error': 'not_found'})
            except ValueError as error:
                self.reply(409 if 'CONFLICT' in str(error) else 422, {'error': type(error).__name__})
            except Exception as error:
                state.log('errors.jsonl', dict(at_ms=int(time.time() * 1000), path=self.path, error=type(error).__name__))
                self.reply(500, {'error': 'local_experiment_service_error'})

    return Handler


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-live', action='store_true', help='Explicitly permit paid real-model calls')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--catalogue', type=Path, required=True)
    parser.add_argument('--log-dir', type=Path, required=True)
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env')
    args = parser.parse_args(argv)
    if not args.allow_live:
        parser.error('Explicit --allow-live is required')
    from dotenv import load_dotenv
    load_dotenv(args.env_file, override=True)
    if not os.getenv('GEMINI_API_KEY', '').strip():
        parser.error('Gemini key is not configured')
    catalogue, canonical, task_of = load_catalogue(args.catalogue)
    for task in json.loads(args.catalogue.read_text(encoding='utf-8'))['tasks']:
        _GOALS[digest(task['goal'])] = task['goal']
    if args.log_dir.exists() and any(args.log_dir.iterdir()):
        parser.error('Log directory must be empty: one facade process per run')
    state = ExperimentState(canonical, task_of, args.log_dir)
    service = AppTaskService(memory=ArmMemory(state))
    server = ThreadingHTTPServer(('127.0.0.1', args.port), make_handler(service, state, catalogue))
    print(json.dumps(dict(status='ready', binding=f'127.0.0.1:{args.port}', schema_version=VERSION)), flush=True)
    try:
        server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
