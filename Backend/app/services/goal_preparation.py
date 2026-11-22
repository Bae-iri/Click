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
from app.services.goal_inputs import GoalInputRequirements, normalize, negated, needs_contextual_interpretation
from app.services.goal_classifier import configured_goal_classifier
from app.services.goal_jev import JevGoalClassifier
from app.services import browse_policy, public_guide_policy
from app.schemas.browse import BrowseGoal


UNSUPPORTED_MESSAGES = {
    'unsupported_app': '아직 클릭이 지원하지 않는 앱이에요. 휴대폰 설정과 정부24 화면 안내를 도와드릴 수 있어요.',
    'unsupported': '아직 클릭이 지원하지 않는 작업이에요. 휴대폰 설정이나 정부24에서 필요한 도움을 말씀해 주세요.',
    'unrelated': '클릭은 휴대폰 설정과 정부24 화면 찾기를 도와드리고 있어요. 필요한 도움을 말씀해 주세요.',
}


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
        exact = [g for g in self.catalog['goals'] if (
            g['target_app'] == 'coupang' and text == normalize(g['display_name'])
            or any(text == normalize(u) for u in g.get('utterances', [])))
            and not any(normalize(w) and normalize(w) in text for w in g.get('excluded_keywords', []))]
        if exact:
            return exact
        scored = []
        for goal in self.catalog['goals']:
            if any(normalize(word) and normalize(word) in text for word in goal.get('excluded_keywords', [])):
                continue
            lengths = [max((len(normalize(w)) for w in group if normalize(w) and normalize(w) in text), default=0)
                for group in goal['keyword_groups']]
            if lengths and all(lengths):
                score = 100 * len(lengths) + sum(lengths)
                scored.append((score, goal))
        top = max((score for score, _ in scored), default=0)
        winners = [goal for score, goal in scored if score == top]
        # "연결 설정 열기" must not tie with opening Settings itself. Likewise,
        # opening a toggle's page is not a request to enable that setting.
        text = normalize(source)
        explicit_change = any(word in text for word in (
            '켜', '꺼', '키워', '키우', '줄여', '줄이', '늘려', '낮춰', '높여',
            '활성', '비활성', '바꿔', '변경', '설정해', '연결해', '해제'))
        if not explicit_change:
            specific = [g for g in winners if g['goal_type'].startswith('settings.navigation.')
                and g['goal_type'] != 'settings.navigation.settings'
                and (g['goal_type'] != 'settings.navigation.display'
                     or any(normalize(word) in text for word in ('디스플레이', 'display')))]
            if len(specific) == 1:
                return specific
        return winners

    def resolve(self, request):
        identity = request.model_dump(mode='json')
        capabilities = identity['device_context']['capabilities']
        if not capabilities.get('coupang_browse'):
            capabilities.pop('coupang_browse', None)
        digest = sha256(json.dumps(identity, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        # Snapshot under the same user-first serialization order as queue/cancel operations.
        with self.factory() as db:
            now = self._lock(db, request)
            # Use the authenticated profile only for the final authored UI message;
            # the name is never part of goal classification/model inputs.
            user_name = db.get(User, request.user_id).name
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
            previous, restored_response = None, None
            if request.goal_resolution_id:
                parent = self._owned(db, request, request.goal_resolution_id)
                if parent.id != parent_id or parent.expires_at <= now:
                    self.conflict('STALE_GOAL_RESOLUTION')
                restored_response = parent.response
            elif current and current.expires_at > now and not request.preparation_reset:
                stored = current.response.get('preparation')
                if stored and stored['source_text'] == request.source_text:
                    restored_response = current.response
            rejection_reason = None
            if restored_response and not request.preparation_reset:
                previous = deepcopy(restored_response['preparation'])  # No approval is restored.
                rejection_reason = restored_response.get('metadata', {}).get('goal_interpretation', {}).get('reason')
                if rejection_reason not in UNSUPPORTED_MESSAGES:
                    rejection_reason = None
            db.commit()

        source = request.source_text
        public_guide_type = None
        planner_goal = False
        gov24_allowed = public_guide_policy.enabled() and request.device_context.capabilities.gov24_login
        browse_allowed = browse_policy.enabled() and request.device_context.capabilities.coupang_browse
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
                rejection_reason = None
            elif field_id == 'browse_query' and previous.get('execution_mode') == 'browse':
                if not browse_allowed:
                    self.conflict('BROWSE_DISABLED')
                values['browse_query'] = browse_policy.safe_query(request.clarification_answer.value)
            else:
                field = next(f for f in self.requirements.required(family, values) if f['id'] == field_id)
                values[field_id] = self.requirements.answer(field, request.clarification_answer.value)

        model_context = isinstance(self.llm, JevGoalClassifier) and needs_contextual_interpretation(source)
        lexical_blocked = negated(source) or model_context
        public_guide_types = set()
        if gov24_allowed:
            public_guide_types.add('gov24.login')
        if public_guide_policy.enabled() and request.device_context.capabilities.gov24_civil:
            public_guide_types.add('gov24.resident_copy.open')
            if request.device_context.capabilities.gov24_services:
                public_guide_types.update(public_guide_policy.CIVIL_GRAPHS)
        if previous and previous.get('execution_mode') == 'public_guide':
            restored_type = (previous.get('public_guide_goal') or {}).get('goal_type')
            if restored_type in public_guide_types:
                public_guide_type = restored_type
                planner_goal = bool((previous.get('public_guide_goal') or {}).get('planner'))
        matches = [] if lexical_blocked else self._keywords(source)
        gov24_matches = [g for g in matches if g['target_app'] == 'gov24']
        civil_type = public_guide_policy.civil_goal(source) if len(gov24_matches) <= 1 else None
        from app.services import gov24_planner
        planned_goal = gov24_planner.requested_goal(source) if (gov24_planner.enabled()
            and request.device_context.capabilities.gov24_planner and len(gov24_matches) <= 1) else None
        if (public_guide_policy.enabled() and request.device_context.capabilities.gov24_civil
                and not lexical_blocked and planned_goal):
            public_guide_type, planner_goal = planned_goal, True
            family, values, rejection_reason = None, {}, None
        elif (public_guide_policy.enabled() and request.device_context.capabilities.gov24_civil
                and not lexical_blocked and civil_type and (civil_type == 'gov24.resident_copy.open'
                    or request.device_context.capabilities.gov24_services)):
            public_guide_type = civil_type
            family, values, rejection_reason = None, {}, None
        elif (gov24_allowed and not lexical_blocked and public_guide_policy.requested(source)
              and not any(g['goal_type'] != 'gov24.login' for g in gov24_matches)):
            public_guide_type = 'gov24.login'
            family, values, rejection_reason = None, {}, None
        match = matches[0] if len(matches) == 1 else None
        browse_context = '쿠팡' in normalize(source) or request.device_context.foreground_package == browse_policy.POLICY['package']
        browse_type = (previous.get('browse_goal_type') if previous and previous.get('execution_mode') == 'browse'
                       else match['goal_type'] if match and match['goal_type'] in browse_policy.GOALS and browse_context else None) if browse_allowed else None
        if match and not browse_type and match.get('execution_status') != 'approved':
            supported_apps = {g['target_app'] for g in self.catalog['goals'] if g.get('execution_status') == 'approved'}
            supported_apps.add('gov24')
            rejection_reason = 'unsupported_app' if match['target_app'] not in supported_apps else 'unsupported'
            family, values = None, {}
        named_app = self.requirements.explicit_values(source).get('app_name')
        if (not model_context and match is None and not public_guide_type and not any(g['target_app']=='coupang' and g.get('execution_status')=='approved' for g in self.catalog['goals'])
                and (named_app=='쿠팡' or request.device_context.foreground_package=='com.coupang.mobile')):
            rejection_reason='unsupported_app'
            family, values=None, {}
        if browse_allowed and match and match['goal_type'] in browse_policy.GOALS and not browse_context and named_app:
            rejection_reason = 'unsupported_app'
            family, values = None, {}
        if previous is None and not browse_type and not public_guide_type and not rejection_reason:
            family = None if lexical_blocked else self.requirements.family(match['goal_type'] if match else None, source)
            ambiguous_direction = len(self.requirements.directions(source, family)) > 1
            values = {} if lexical_blocked else self.requirements.explicit_values(source, family)
            if match and family:
                for field in family['fields']:
                    if field['id'] == 'direction' and ambiguous_direction:
                        continue
                    if field.get('goal_slot') in match['slots']:
                        values.setdefault(field['id'], match['slots'][field['goal_slot']])
            if model_context or (not negated(source) and len(matches) < 2 and (match is None or family is not None) and (
                    family is None or self.requirements.missing(family, values))):
                candidates = [{k: g[k] for k in ('goal_type', 'display_name', 'target_app', 'slots')}
                    for g in self.catalog['goals'] if (g.get('execution_status') == 'approved'
                        or browse_allowed and browse_context and g['goal_type'] in browse_policy.GOALS
                        or model_context and g['goal_type'] in public_guide_types)
                    and (not match or not family or g['goal_type'].startswith(family['goal_prefix']))]
                # Unknown intent needs one goal choice, not a union of unrelated
                # families' conflicting field definitions. Its fields are derived
                # from the chosen goal and the original request below.
                fields = family['fields'] if family else []
                inferred = self.llm.interpret_inputs(source, candidates, fields)
                if inferred and inferred.get('reason') in UNSUPPORTED_MESSAGES:
                    rejection_reason = inferred['reason']
                    family, values = None, {}
                elif inferred:
                    suggested = next((g for g in candidates if g['goal_type'] == inferred['goal_type']), None)
                    if suggested and suggested['goal_type'] in public_guide_types:
                        public_guide_type = suggested['goal_type']
                    if suggested and suggested['goal_type'] in browse_policy.GOALS and browse_allowed:
                        browse_type = suggested['goal_type']
                    selected_family = self.requirements.family(suggested['goal_type']) if suggested else None
                    directions = self.requirements.directions(source, selected_family) if selected_family else set()
                    contradictory = not model_context and suggested and 'direction' in suggested['slots'] and directions and (
                        suggested['slots']['direction'] not in directions or len(directions) > 1)
                    if selected_family and not contradictory:
                        family = selected_family
                        # Negated clauses must not override the selected goal or
                        # scope. Jev's validated choices share the same model call.
                        values = {} if model_context else self.requirements.explicit_values(source, family)
                        if model_context and inferred['inputs'].get('scope') in {'system', 'app'}:
                            values['scope'] = inferred['inputs']['scope']
                        for field in family['fields']:
                            key, slot = field['id'], field.get('goal_slot')
                            if key in values or not model_context and key in {'scope', 'audio_stream'}:
                                continue
                            candidate = suggested['slots'].get(slot) if slot else inferred['inputs'].get(key)
                            if candidate is None or key == 'app_name' and normalize(candidate) not in normalize(source):
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
        browse_goal = None
        if public_guide_type:
            family, fields, missing = None, [], []
            status, resolution_status = 'ready', 'confirmation_required'
            display_name = '정부24 로그인 방법 선택'
            message = '정부24 로그인 방법을 함께 고를까요? 실제 화면에 보이는 항목에서 말·글·버튼으로 선택해요. 개인정보 입력과 실제 인증은 직접 진행해 주세요.'
            if public_guide_type in public_guide_policy.CIVIL_GRAPHS:
                label = public_guide_policy.CIVIL_GRAPHS[public_guide_type]['label']
                display_name = f'정부24 {label} 안내 화면 찾기'
                message = f'정부24에서 {label} 안내 화면까지 함께 이동할까요? 화면에 보이는 공개 메뉴를 안내하고, 신청서 입력과 최종 신청은 직접 진행해 주세요.'
            if planner_goal:
                title = gov24_planner.SERVICES[public_guide_type][0]
                display_name = f'정부24 {title} 안내 화면 찾기'
                message = f'정부24 {title} 안내 화면까지 함께 이동할까요? 실제 발급·신청과 인증은 직접 진행해 주세요.'
            rejection_reason = None
        elif browse_type:
            family, fields, missing = None, [], []
            rejection_reason = None
            display_name = '쿠팡 상품 검색' if browse_type.endswith('search') else '쿠팡 상품 상세 보기'
            if browse_type.endswith('search') and not values.get('browse_query'):
                literal_query = browse_policy.explicit_query(source)
                if literal_query:
                    values['browse_query'] = literal_query
            if not values.get('browse_query'):
                missing = [{'id': 'browse_query'}]
                question = InputQuestion(id=identifier + ':browse_query', field='browse_query', label='상품 이름', kind='text',
                    message='어떤 상품을 찾을까요? 상품 이름을 말씀해 주세요.', placeholder='예: 무선 이어폰', max_length=100)
                message = question.message
            else:
                browse_goal = BrowseGoal(goal_type=browse_type, query=browse_policy.safe_query(values['browse_query']))
                status, resolution_status = 'ready', 'confirmation_required'
                action_label = '검색을' if browse_type.endswith('search') else '상세 보기를'
                message = f'쿠팡에서 “{browse_goal.query}” 상품 {action_label} 안내할까요? 상품 화면의 공개 항목을 AI로 확인해요. 검색어 입력과 화면 조작은 직접 진행해 주세요.'
        elif rejection_reason:
            status, resolution_status = 'unsupported', 'unsupported'
            message = UNSUPPORTED_MESSAGES[rejection_reason]
        elif family is None:
            if match:
                status, resolution_status = 'unsupported', 'unsupported'
                supported_apps = {g['target_app'] for g in self.catalog['goals'] if g.get('execution_status') == 'approved'}
                if browse_allowed:
                    supported_apps.add('coupang')
                rejection_reason = 'unsupported_app' if match['target_app'] not in supported_apps else 'unsupported'
                message = UNSUPPORTED_MESSAGES[rejection_reason]
            else:
                question = InputQuestion(id=identifier + ':request', field='request', label='요청', kind='text',
                    message='원하시는 작업을 조금 더 자세히 말해주세요.', placeholder='예: 글자 크기를 키워줘', max_length=200)
                message = question.message
        elif missing:
            field = missing[0]
            question = InputQuestion(id=identifier + ':' + field['id'], field=field['id'], label=field['label'],
                kind=field['kind'], message=field['question'], choices=field.get('choices', []),
                placeholder=field.get('placeholder'), max_length=field.get('max_length', 100))
            message = question.message
        else:
            candidates = [g for g in self.catalog['goals'] if (g['goal_type'] == family['goal_prefix']
                or family['goal_prefix'].endswith('.') and g['goal_type'].startswith(family['goal_prefix'])) and g.get('execution_status') == 'approved'
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
                if selected['target_app'] == 'coupang':
                    message = f"{display_name} 화면을 찾고 진행 방법을 안내할게요. 상품·주문 선택과 입력은 쿠팡에서 직접 해주세요."
                if selected['task_type'] == 'settings_assisted':
                    display_name += ' · 직접 진행 안내'
                    from ai_agent.task_graph.assisted_guidance import start_message
                    message = start_message(selected['goal_type'], user_name=user_name) or '설정 화면까지 안내할게요. 항목 선택·입력·최종 변경은 직접 진행하고 결과를 확인해 주세요.'
                    if selected.get('risk_level') == 'high':
                        message += ' 데이터 삭제나 보안 변경이 포함될 수 있으니 기기의 경고를 확인해 주세요.'
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
        if browse_allowed and rejection_reason:
            message = {
                'unsupported_app': '아직 클릭이 지원하지 않는 앱이에요. 휴대폰 설정과 정부24 화면 안내를 도와드릴 수 있어요.',
                'unsupported': '아직 클릭이 지원하지 않는 작업이에요. 휴대폰 설정과 정부24 화면 안내를 도와드릴 수 있어요.',
                'unrelated': '클릭은 휴대폰 설정과 정부24 화면 찾기를 도와드리고 있어요. 필요한 도움을 말씀해 주세요.',
            }[rejection_reason]
        if match and match['target_app'] == 'gov24' and not public_guide_type:
            status, resolution_status = 'unsupported', 'unsupported'
            rejection_reason = 'unsupported'
            if match['execution_status'] == 'public_guide':
                message = '정부24 화면 안내를 사용하려면 최신 앱과 정부24 안내가 켜진 서버가 필요해요.'
            else:
                message = (f"{match['display_name']} 안내는 아직 준비 중이에요. "
                           '현재는 정부24 로그인 방법 선택과 주민등록등본 발급 안내 화면 찾기를 지원해요.')
        prepared = GoalPreparation(id=identifier, source_text=source, family=family['id'] if family else None,
            status=status, values=values, required_fields=fields, missing_fields=[f['id'] for f in missing] or (
                ['request'] if question and question.field == 'request' else []), question=question,
            graph_reference=reference, expires_at=now + self.TTL, execution_mode='public_guide' if public_guide_type else 'browse' if browse_type else 'graph',
            browse_goal_type=browse_type, browse_goal=browse_goal,
            public_guide_goal={'goal_type': public_guide_type, 'planner': planner_goal,
                'request_scope': gov24_planner.request_scope(source) if planner_goal else 'screen_only'} if public_guide_type else None)
        response = AgentGoalResolutionResponse(request_id=request.request_id, session_id=request.session_id,
            session_version=0, resolution_status=resolution_status, goal=goal, display_name=display_name,
            message=message, preparation=prepared)
        response.metadata['goal_selection'] = {'id': identifier, 'message': message,
            'candidates': [{'goal': goal.model_dump(mode='json'), 'label': display_name}] if goal else []}
        from app.services.settings_planner import enabled as settings_experiment_enabled, VERSION as settings_experiment_version
        if settings_experiment_enabled() and request.device_context.capabilities.settings_experiment_arm:
            response.metadata['settings_experiment_preparation'] = {
                'version': settings_experiment_version, 'arm':request.device_context.capabilities.settings_experiment_arm,
                'stage':'preparation','resolution_status':resolution_status,
                'goal_type':goal.goal_type if goal else None,'missing_fields':prepared.missing_fields}
        if rejection_reason:
            response.metadata['goal_interpretation'] = {'reason': rejection_reason}
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
