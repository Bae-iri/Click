"""Persist graph inputs and ask only for missing fields before choosing an executable graph."""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError

from app.models.agent_session import AgentGoalResolution, AgentSession
from app.models.user import User
from app.schemas.agent_turn import AgentGoalResolutionResponse, TypedGoalRequest
from app.schemas.goal_preparation import GoalPreparation, InputQuestion
from app.services.goal_inputs import GoalInputRequirements, normalize, negated
from app.services.goal_classifier import configured_goal_classifier


class GoalPreparationService:
    TTL = 600
    ACTIVE = ('PENDING', 'READY', 'UNSUPPORTED')

    def __init__(self, factory, graphs, sessions, llm=None, requirements=None):
        self.factory, self.graphs, self.sessions = factory, graphs, sessions
        self.llm = llm if llm is not None else configured_goal_classifier()
        self.requirements = requirements or GoalInputRequirements()
        self.catalog = json.loads((Path(__file__).parents[1] / 'task_graphs/goal_catalog.json').read_text(encoding='utf-8'))

    @staticmethod
    def conflict(code):
        raise HTTPException(409, headers={'X-Conflict-Code': code}, detail=code)

    def _lock(self, db, request, *, before_start=True):
        if db.execute(update(User).where(User.id == request.user_id).values(id=request.user_id)).rowcount != 1:
            raise HTTPException(401)
        row = db.get(AgentSession, request.session_id)
        if row is None or row.user_id != request.user_id:
            raise HTTPException(404)
        now = int(self.sessions.clock())
        if row.expires_at <= now:
            self.conflict('SESSION_EXPIRED')
        if before_start:
            self.sessions.guard_voice_request(db, request, now)
            if row.version != request.expected_version or row.version != 0 or row.episode_id or row.status != 'IN_PROGRESS':
                self.conflict('GOAL_PREPARATION_NOT_ALLOWED')
        return now

    def _current(self, db, request):
        return db.scalar(select(AgentGoalResolution).where(
            AgentGoalResolution.user_id == request.user_id,
            AgentGoalResolution.session_id == request.session_id,
            AgentGoalResolution.status.in_(self.ACTIVE)))

    def _owned(self, db, request, identifier):
        row = db.get(AgentGoalResolution, identifier)
        if row is None or row.user_id != request.user_id or row.session_id != request.session_id:
            raise HTTPException(404)
        return row

    def requires_preparation(self, request):
        with self.factory() as db:
            return db.scalar(select(AgentGoalResolution.id).where(
                AgentGoalResolution.user_id == request.user_id,
                AgentGoalResolution.session_id == request.session_id).limit(1)) is not None

    def _keywords(self, source):
        text = normalize(source)
        scored = []
        for goal in self.catalog['goals']:
            lengths = [max((len(normalize(w)) for w in group if normalize(w) and normalize(w) in text), default=0)
                for group in goal['keyword_groups']]
            if lengths and all(lengths):
                score = 100 * len(lengths) + sum(lengths)
                if any(text == normalize(w) for w in goal.get('utterances', [])):
                    score += 1000
                scored.append((score, goal))
        top = max((score for score, _ in scored), default=0)
        return [goal for score, goal in scored if score == top]

    def resolve(self, request):
        digest = sha256(json.dumps(request.model_dump(mode='json'), sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        # Snapshot under the same user-first serialization order as queue/cancel operations.
        with self.factory() as db:
            now = self._lock(db, request)
            cached = db.scalar(select(AgentGoalResolution).where(AgentGoalResolution.user_id == request.user_id,
                AgentGoalResolution.request_id == request.request_id))
            if cached:
                if cached.request_hash != digest or cached.session_id != request.session_id:
                    self.conflict('REQUEST_ID_REUSED')
                if cached.status not in self.ACTIVE or cached.expires_at <= now:
                    self.conflict('STALE_GOAL_RESOLUTION')
                return AgentGoalResolutionResponse.model_validate(cached.response)
            current = self._current(db, request)
            parent_id = current.id if current else None
            previous = None
            if request.goal_resolution_id:
                parent = self._owned(db, request, request.goal_resolution_id)
                if parent.id != parent_id or parent.expires_at <= now:
                    self.conflict('STALE_GOAL_RESOLUTION')
                previous = deepcopy(parent.response['preparation'])
            elif current and current.expires_at > now and not request.preparation_reset:
                stored = current.response.get('preparation')
                if stored and stored['source_text'] == request.source_text:
                    previous = deepcopy(stored)  # Reconnect restores answered fields; no approval is restored.
            if request.preparation_reset:
                previous = None
            db.commit()

        source = request.source_text
        values = dict(previous['values']) if previous else {}
        family = next((f for f in self.requirements.document['families'] if previous and f['id'] == previous['family']), None)
        if request.clarification_answer:
            question = previous.get('question') if previous else None
            if not question or question['id'] != request.clarification_answer.question_id:
                self.conflict('STALE_CLARIFICATION_QUESTION')
            if source != previous['source_text']:
                self.conflict('CLARIFICATION_SOURCE_CHANGED')
            field_id = question['field']
            if field_id == 'request':
                source = request.clarification_answer.value.strip()
                values, family, previous = {}, None, None
            else:
                field = next(f for f in self.requirements.required(family, values) if f['id'] == field_id)
                values[field_id] = self.requirements.answer(field, request.clarification_answer.value)

        matches = [] if negated(source) else self._keywords(source)
        match = matches[0] if len(matches) == 1 else None
        if previous is None:
            family = None if negated(source) else self.requirements.family(match['goal_type'] if match else None, source)
            ambiguous_direction = len(self.requirements.directions(source, family)) > 1
            values = {} if negated(source) else self.requirements.explicit_values(source, family)
            if match and family:
                for field in family['fields']:
                    if field['id'] == 'direction' and ambiguous_direction:
                        continue
                    if field.get('goal_slot') in match['slots']:
                        values.setdefault(field['id'], match['slots'][field['goal_slot']])
            if not negated(source) and len(matches) < 2 and (match is None or family is not None) and (
                    family is None or self.requirements.missing(family, values)):
                candidates = [{k: g[k] for k in ('goal_type', 'display_name', 'target_app', 'slots')}
                    for g in self.catalog['goals'] if g.get('execution_status') == 'approved']
                fields = family['fields'] if family else [f for fam in self.requirements.document['families'] for f in fam['fields']]
                inferred = self.llm.interpret_inputs(source, candidates, fields)
                if inferred:
                    suggested = next((g for g in self.catalog['goals'] if g['goal_type'] == inferred['goal_type']), None)
                    if family is None and suggested:
                        family = self.requirements.family(suggested['goal_type'])
                    if family:
                        for field in family['fields']:
                            key = field['id']
                            # Scope is never invented from an LLM guess or the foreground app.
                            # A model's supported direction may be proposed, then confirmed by the user.
                            if key in values or key in {'scope', 'audio_stream'} or key not in inferred['inputs']:
                                continue
                            if key == 'direction' and ambiguous_direction:
                                continue
                            candidate = inferred['inputs'][key]
                            if key == 'app_name' and normalize(candidate) not in normalize(source):
                                continue
                            try:
                                values[key] = self.requirements.answer(field, candidate)
                            except ValueError:
                                pass

        identifier = str(uuid4())
        goal, reference, display_name = None, None, None
        status, resolution_status = 'needs_input', 'needs_clarification'
        fields = self.requirements.required(family, values) if family else []
        missing = self.requirements.missing(family, values) if family else []
        question = None
        if family is None:
            if match:
                status, resolution_status = 'unsupported', 'unsupported'
                message = '이 작업의 입력 조건과 실행 안내는 아직 지원하지 않아요.'
            else:
                question = InputQuestion(id=identifier + ':request', field='request', label='요청', kind='text',
                    message='바꾸려는 대상과 원하는 변경을 다시 말씀해 주세요.', placeholder='예: 글자 크기를 키워줘', max_length=200)
                message = question.message
        elif missing:
            field = missing[0]
            question = InputQuestion(id=identifier + ':' + field['id'], field=field['id'], label=field['label'],
                kind=field['kind'], message=field['question'], choices=field.get('choices', []),
                placeholder=field.get('placeholder'), max_length=field.get('max_length', 100))
            message = question.message
        else:
            candidates = [g for g in self.catalog['goals'] if g['goal_type'].startswith(family['goal_prefix'])
                and all(not f.get('goal_slot') or g['slots'].get(f['goal_slot']) == values[f['id']] for f in fields)]
            try:
                route = next((r for r in family['routes'] if all(values.get(k) == v for k, v in r['when'].items())), None)
                candidates = [g for g in candidates if route and g['target_app'] == route['target_app']]
                if len(candidates) != 1:
                    raise ValueError('NO_GRAPH_FOR_REQUESTED_SCOPE')
                selected = candidates[0]
                goal = TypedGoalRequest(catalog_version=self.catalog['version'], goal_type=selected['goal_type'],
                    target_app=selected['target_app'], slots=selected['slots'])
                _, reference = self.graphs.select_for_inputs(goal, values)
                display_name = selected['display_name']
                status, resolution_status = 'ready', 'confirmation_required'
                message = f'{display_name} 안내를 시작할까요?'
            except ValueError:
                goal = None
                status, resolution_status = 'unsupported', 'unsupported'
                label = values.get('app_name', '선택한 범위')
                if family['id'] == 'font_size':
                    message = f'{label}의 글자 크기를 바꾸는 실행 안내는 아직 지원하지 않아요. 요청을 수정할 수 있어요.'
                elif family['id'] == 'volume' and values.get('audio_stream') == 'other':
                    message = '현재는 동영상·음악의 미디어 음량 조절을 지원해요. 벨소리·알림·통화 소리는 아직 지원하지 않아요.'
                else:
                    message = f'{label}에서 이 설정을 바꾸는 실행 안내는 아직 지원하지 않아요. 요청을 수정할 수 있어요.'
        prepared = GoalPreparation(id=identifier, source_text=source, family=family['id'] if family else None,
            status=status, values=values, required_fields=fields, missing_fields=[f['id'] for f in missing] or (
                ['request'] if question and question.field == 'request' else []), question=question,
            graph_reference=reference, expires_at=now + self.TTL)
        response = AgentGoalResolutionResponse(request_id=request.request_id, session_id=request.session_id,
            session_version=0, resolution_status=resolution_status, goal=goal, display_name=display_name,
            message=message, preparation=prepared)
        response.metadata['goal_selection'] = {'id': identifier, 'message': message,
            'candidates': [{'goal': goal.model_dump(mode='json'), 'label': display_name}] if goal else []}
        with self.factory() as db:
            self._lock(db, request)  # A cancel or start during inference invalidates this result.
            current = self._current(db, request)
            if (current.id if current else None) != parent_id:
                self.conflict('GOAL_PREPARATION_CHANGED')
            if current:
                current.status = 'SUPERSEDED'
            db.add(AgentGoalResolution(id=identifier, user_id=request.user_id, session_id=request.session_id,
                request_id=request.request_id, request_hash=digest, candidates=response.metadata['goal_selection']['candidates'],
                response=response.model_dump(mode='json'), status={'ready': 'READY', 'unsupported': 'UNSUPPORTED'}.get(status, 'PENDING'),
                expires_at=prepared.expires_at))
            try:
                db.commit()
            except IntegrityError as error:
                db.rollback()
                raise HTTPException(409, detail='GOAL_PREPARATION_CHANGED') from error
        return response

    def accept(self, request):
        with self.factory() as db:
            now = self._lock(db, request, before_start=False)
            row = self._owned(db, request, request.goal_resolution_id)
            if row.status == 'ACCEPTED' and row.selection_request_id == request.request_id:
                if row.candidates[0]['goal'] != request.goal.model_dump(mode='json'):
                    self.conflict('PREPARED_GOAL_MISMATCH')
                return deepcopy(row.response['preparation']['graph_reference'])
            self._lock(db, request)
            if row.status != 'READY' or row.expires_at <= now:
                self.conflict('GOAL_INPUTS_NOT_READY')
            prepared = row.response['preparation']
            if row.candidates[0]['goal'] != request.goal.model_dump(mode='json'):
                self.conflict('PREPARED_GOAL_MISMATCH')
            _, reference = self.graphs.select_for_inputs(request.goal, prepared['values'])
            if reference != prepared['graph_reference']:
                self.conflict('PREPARED_GRAPH_CHANGED')
            row.status, row.selected_goal, row.selection_request_id = 'ACCEPTED', request.goal.goal_type, request.request_id
            db.commit()
            return reference

    def cancel(self, request):
        with self.factory() as db:
            self._lock(db, request)
            row = self._owned(db, request, request.goal_resolution_id)
            if row.status not in self.ACTIVE and row.status != 'CANCELLED':
                self.conflict('STALE_GOAL_RESOLUTION')
            row.status = 'CANCELLED'
            result = AgentGoalResolutionResponse.model_validate(row.response)
            result.request_id = request.request_id
            result.goal = None
            result.resolution_status = 'needs_clarification'
            result.preparation.status = 'cancelled'
            result.preparation.question = None
            result.preparation.graph_reference = None
            result.message = '요청 확인을 취소했어요.'
            result.metadata['goal_selection'] = {'id': row.id, 'message': result.message, 'candidates': []}
            db.commit()
            return result
