"""Versioned graphless manual browsing; no execution or completion authority from Jev."""
from copy import deepcopy
import json
from types import SimpleNamespace
from uuid import uuid4
from pathlib import Path
from sqlalchemy import select, update
from fastapi import HTTPException
from app.models.agent_session import AgentSession, AgentGoalResolution, AgentTurn
from app.repositories.runtime_events import append
from app.schemas.browse import BrowseGoal, BrowseResponse
from app.services import browse_policy as policy
from app.services.browse_jev import JevBrowseSelector
from app.services.browse_route_hints import BrowseRouteHints

CODE_HASH = policy.digest(''.join(Path(__file__).with_name(name).read_text(encoding='utf-8')
    for name in ('browse.py','browse_policy.py','browse_jev.py','browse_route_hints.py')))


class BrowseService:
    def __init__(self, factory, sessions, selector=None):
        self.factory, self.sessions = factory, sessions
        self.selector = selector or JevBrowseSelector(routes=BrowseRouteHints(factory, code_hash=CODE_HASH))

    def handle(self, request, user_id):
        now = int(self.sessions.clock())
        identity = policy.digest(json.dumps(request.model_dump(mode='json'), sort_keys=True, ensure_ascii=False))
        guard = SimpleNamespace(user_id=user_id, session_id=request.session_id, raw_goal=None)
        with self.factory() as db:
            self.sessions.guard_voice_request(db, guard, now)
            row = self.sessions.owned(db, request.session_id, user_id)
            if row.expires_at <= now:
                raise HTTPException(410)
            state = deepcopy(row.workflow or {})
            if request.command == 'snapshot':
                if state.get('version') != policy.VERSION:
                    self.sessions.conflict('NOT_BROWSE_SESSION')
                return BrowseResponse(request_id=request.request_id, session_id=row.id, session_version=row.version,
                    status=row.status if row.status in {'IN_PROGRESS','COMPLETED','BLOCKED','CANCELLED'} else 'CANCELLED',
                    outcome=state.get('outcome', 'unknown'), step=state.get('steps', 0),
                    message='현재 화면 확인을 눌러 새 안내를 받아주세요.' if row.status == 'IN_PROGRESS' else '탐색 안내가 끝났어요.')
            previous = db.scalar(select(AgentTurn).where(AgentTurn.user_id == user_id, AgentTurn.request_id == request.request_id))
            if previous:
                if previous.session_id != row.id or previous.request_hash != identity:
                    self.sessions.conflict('REQUEST_ID_REUSED')
                if previous.response:
                    if previous.version != row.version or row.status == 'CANCELLED' and request.command != 'cancel':
                        self.sessions.conflict('STALE_REPLAY')
                    return BrowseResponse.model_validate(previous.response)
            if row.version != request.expected_version:
                self.sessions.conflict('STALE_VERSION')
            if row.lease_token and row.lease_until > now and request.command != 'cancel':
                self.sessions.conflict('TURN_IN_PROGRESS')
            if request.command == 'start':
                if not policy.enabled() or row.version != 0 or row.episode_id or row.status != 'IN_PROGRESS':
                    self.sessions.conflict('BROWSE_START_NOT_ALLOWED')
                resolution = db.get(AgentGoalResolution, request.goal_resolution_id)
                if (not resolution or resolution.user_id != user_id or resolution.session_id != row.id
                        or resolution.status != 'READY' or resolution.expires_at <= now):
                    self.sessions.conflict('GOAL_INPUTS_NOT_READY')
                prepared = resolution.response['preparation']
                if prepared.get('execution_mode') != 'browse':
                    self.sessions.conflict('PREPARED_GOAL_MISMATCH')
                goal = BrowseGoal.model_validate(prepared.get('browse_goal'))
                state = {'version':policy.VERSION,'goal':goal.model_dump(),'history':[],'steps':0}
            elif state.get('version') != policy.VERSION or row.status != 'IN_PROGRESS':
                self.sessions.conflict('BROWSE_COMMAND_NOT_ALLOWED')
            token = str(uuid4())
            row.lease_token, row.lease_until = token, now + self.sessions.lease_seconds
            if previous is None:
                db.add(AgentTurn(session_id=row.id,user_id=user_id,request_id=request.request_id,
                    request_hash=identity,version=row.version+1,created_at=now))
            db.commit()
        try:
            response, evidence, state = self.decide(request, state, now)
            with self.factory() as db:
                commit_now = int(self.sessions.clock())
                self.sessions.guard_voice_request(db, guard, commit_now)
                row = self.sessions.owned(db, request.session_id, user_id)
                changed = db.execute(update(AgentSession).where(AgentSession.id==row.id,
                    AgentSession.version==request.expected_version,AgentSession.lease_token==token,
                    AgentSession.status=='IN_PROGRESS',
                    AgentSession.lease_until>int(self.sessions.clock()),AgentSession.expires_at>int(self.sessions.clock())
                ).values(version=response.session_version,status=response.status,lease_token=None,lease_until=None))
                if changed.rowcount != 1:
                    self.sessions.conflict('TURN_SUPERSEDED')
                row.workflow, row.task_id, row.state_id = state, 'COUPANG-BROWSE', evidence['page']
                row.accepted_goal_type = state['goal']['goal_type']
                row.goal = {'goal_type':row.accepted_goal_type,'target_app':'coupang','slots':{}}
                row.episode_id = row.episode_id or str(uuid4())
                row.updated_at, row.expires_at = commit_now, commit_now+self.sessions.idle_seconds
                row.last_guide_command_id = response.guide_id
                row.task_outcome = {'technical':{'status':'OPENED' if response.outcome=='verified_navigation' else 'UNVERIFIABLE'},
                    'final':{'verified_navigation':'ACHIEVED','user_reported':'USER_REPORTED_RESOLVED','cancelled':'USER_STOPPED','blocked':'BLOCKED'}.get(response.outcome),
                    'browse_outcome':response.outcome}
                turn = db.scalar(select(AgentTurn).where(AgentTurn.user_id==user_id,AgentTurn.request_id==request.request_id))
                turn.response = response.model_dump(mode='json')
                if request.command == 'start':
                    resolution = db.get(AgentGoalResolution,request.goal_resolution_id)
                    if resolution.status != 'READY' or resolution.expires_at <= commit_now:
                        self.sessions.conflict('STALE_GOAL_RESOLUTION')
                    resolution.status,resolution.selection_request_id = 'ACCEPTED',request.request_id
                append(db,row,request.request_id,{'browse':evidence},kind='browse',now=commit_now)
                db.commit()
            return response
        except Exception:
            self.sessions.release(request.session_id,token)
            raise

    def decide(self, request, state, now):
        state = deepcopy(state)
        step = state.get('steps',0)+1
        evidence = {'schema_version':'browse-event.v1','policy_version':policy.VERSION,
            'step': step, 'command': request.command, 'execution_mode': 'guided', 'action_actor': 'user',
            'code_hash':CODE_HASH,
            'policy_hash':policy.digest(json.dumps(policy.POLICY,sort_keys=True)),
            'goal_type':state['goal']['goal_type'],'page':'unknown','outcome':'in_progress',
            'candidate_count':0,'called':False,'provider_latency_ms':0,'action_correlated':False,
            'reason':'entry_required'}
        result = BrowseResponse(request_id=request.request_id,session_id=request.session_id,
            session_version=request.expected_version+1,status='IN_PROGRESS',outcome='in_progress',step=step,
            message='쿠팡을 열고 현재 화면 확인을 눌러주세요.')
        pending = state.pop('pending',None)
        if request.command in {'cancel','finish'}:
            result.status = 'CANCELLED' if request.command=='cancel' else 'COMPLETED'
            result.outcome = 'cancelled' if request.command=='cancel' else 'user_reported'
            result.message = '탐색 안내를 멈췄어요.' if request.command=='cancel' else '탐색 안내를 마쳤어요. 결과는 사용자가 확인했어요.'
        elif step>policy.POLICY['max_turns']:
            result.status,result.outcome,result.message = 'BLOCKED','blocked','탐색 횟수에 도달했어요. 직접 확인하거나 새 요청으로 시작해 주세요.'
        else:
            screen = policy.sanitize(request.screen,now*1000)
            if request.screen and request.screen.status=='sensitive':
                evidence['reason'] = 'sensitive_screen'
                result.status,result.outcome,result.message = 'BLOCKED','blocked','개인정보나 주문·결제 화면에서는 탐색 안내를 멈춰요.'
            elif request.screen and screen is None:
                evidence['reason'] = 'outside_app' if request.screen.status == 'outside_app' else 'unrecognized_screen'
                result.message = '쿠팡의 공개 상품 화면을 열고 현재 화면 확인을 눌러주세요.'
            elif screen:
                self.observe(request,state,pending,screen,now,result,evidence)
        state.update(steps=step,outcome=result.outcome)
        evidence['outcome'] = result.outcome
        return result,evidence,state

    def observe(self, request, state, pending, screen, now, result, evidence):
        kind = policy.page(screen)
        evidence['page'] = kind
        fingerprint = policy.digest(json.dumps(screen.model_dump(exclude={'observation_id','captured_at_ms'}),sort_keys=True))
        evidence['observation_hash'] = fingerprint
        state['same_screen'] = state.get('same_screen',0)+1 if state.get('screen')==fingerprint else 1
        state['screen'] = fingerprint
        attempted = bool(pending and request.action and request.action.guide_id==pending['guide_id']
            and request.action.observation_id==pending['observation_id'] and request.action.node_id==pending['node_id']
            and pending['captured_at_ms']<=request.action.occurred_at_ms<=screen.captured_at_ms
            and request.action.occurred_at_ms<=now*1000+5000)
        correlated = attempted and fingerprint!=pending['before_hash']
        if attempted:
            evidence.update(action_attempted=True, attempt_hash=policy.digest(pending['guide_id']),
                attempted_action=pending['kind'], attempted_resource=pending['resource'],
                attempted_page=pending.get('page', 'unknown'))
        evidence['action_correlated'] = correlated
        if correlated:
            evidence.update(before_hash=pending['before_hash'],action_kind=pending['kind'],resource=pending['resource'],
                before_page=pending.get('page', 'unknown'))
        same_query = screen.query_hash==policy.query_hash(state['goal']['query'])
        search_success = state['goal']['goal_type'].endswith('search') and kind=='results' and same_query and any(
            policy.node_kind(n)=='product' for n in screen.nodes)
        titles = [policy.query_hash(n.label) for n in screen.nodes if policy.resource(n.resource_id) in policy.POLICY['title_resources'] and n.label]
        detail_success = kind=='detail' and correlated and pending['kind']=='product' and pending['label_hash'] in titles
        if search_success or detail_success:
            evidence['reason'] = 'verified_navigation'
            result.status,result.outcome = 'COMPLETED','verified_navigation'
            result.message = '검색한 상품 목록을 확인했어요.' if search_success else '선택한 상품의 상세 화면을 확인했어요.'
            return
        if state['same_screen']>policy.POLICY['max_same_screen']:
            evidence['reason'] = 'repeated_screen'
            result.outcome,result.message = 'unknown','같은 화면에서 진행을 확인하지 못했어요. 직접 이동한 뒤 다시 확인해 주세요.'
            return
        candidates = [(policy.node_kind(n),n) for n in screen.nodes if policy.node_kind(n)
            and (n.clickable or policy.node_kind(n)=='scroll')]
        candidates = [(k,n) for k,n in candidates if k!='product' or kind=='results' and same_query]
        chosen = None
        evidence['candidate_count'] = len(candidates)
        evidence['reason'] = 'no_candidates'
        if candidates:
            chosen,diagnostics = self.selector.choose(state['goal'],screen,candidates,state.get('history',[]))
            evidence.update({k:v for k,v in diagnostics.items() if k in {'called','model','status','confidence','candidate_count','provider_latency_ms','route_hint_count','selection_policy','selection_metrics'}})
            evidence['reason'] = diagnostics.get('status', 'selector_unavailable')
        if not chosen:
            result.outcome,result.message = 'unknown','안전하게 안내할 상품이나 검색창을 확인하지 못했어요. 직접 이동한 뒤 다시 확인해 주세요.'
            return
        action,node = chosen
        evidence.update(selected_action=action, selected_resource=policy.resource(node.resource_id))
        result.guide_id,result.observation_id = str(uuid4()),screen.observation_id
        result.target_node_id,result.target_bounds,result.action_kind = node.node_id,node.bounds,action
        result.message = {'search':f'검색창을 누르고 “{state["goal"]["query"]}”를 직접 입력해 검색한 뒤 현재 화면 확인을 눌러주세요.',
            'product':'표시한 상품을 눌러 상세 화면으로 이동한 뒤 현재 화면 확인을 눌러주세요.',
            'scroll':'상품 목록을 직접 아래로 스크롤한 뒤 현재 화면 확인을 눌러주세요.'}[action]
        state['pending'] = {'guide_id':result.guide_id,'observation_id':screen.observation_id,'node_id':node.node_id,
            'captured_at_ms':screen.captured_at_ms,'before_hash':fingerprint,'kind':action,
            'page': kind,
            'resource':policy.resource(node.resource_id),'label_hash':policy.query_hash(node.label)}
        state['history'] = (state.get('history',[])+[{'page':kind,'action':action}])[-12:]
