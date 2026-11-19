"""Encrypted execution outbox and read-only recall of Agent2's retained analysis."""
from collections import defaultdict
from datetime import timezone
import logging
import re
import time
from uuid import uuid4

from fastapi import HTTPException
from sqlalchemy import func, select

from app.models.agent_session import AgentSession
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.users import UserRepository
from app.schemas.app_task import TaskAction, TaskExpectation
from app.schemas.app_task_learning import TaskMemoryResponse, TaskExperienceReceipt, TaskEnvironment, TaskUserResultReceipt
from app.services.app_task_learning_policy import CONSUMER, POLICY_HASH, digest, fingerprint, target_key, screen_key, ZERO
from app.services.app_guide import PRIVATE, BOUNDARY

log = logging.getLogger(__name__)


class AppTaskExperienceService:
    def __init__(self, factory, clock=time.time):
        self.factory, self.clock = factory, clock

    def user_result(self,request,user_id):
        """Explicit task control, independent of the optional training consent."""
        now=int(self.clock())
        with self.factory() as db:
            UserRepository(db).lock_user(user_id)
            row=db.scalar(select(AgentSession).where(AgentSession.id==request.session_id,AgentSession.user_id==user_id).with_for_update())
            if row is None or row.user_id!=user_id:
                raise HTTPException(404)
            previous=row.task_outcome or {}
            if row.status=='COMPLETED' and previous.get('app_task_id')==request.task_id:
                return TaskUserResultReceipt(request_id=request.request_id,session_id=row.id,task_id=request.task_id)
            if (row.status not in {'IN_PROGRESS','WAITING_CONFIRMATION','WAITING_RESULT','WAITING_ADJUSTMENT','PAUSED'}
                    or row.expires_at<=now or row.graph_hash is not None or row.episode_id is not None
                    or row.lease_token and (row.lease_until or 0)>now):
                raise HTTPException(409,'APP_TASK_RESULT_CONFLICT')
            row.status='COMPLETED';row.version+=1;row.updated_at=now
            row.task_outcome={'technical':{'status':'UNVERIFIABLE'},'final':'USER_REPORTED_RESOLVED',
                              'source':'app-task-user-result.v1','app_task_id':request.task_id}
            db.commit()
        return TaskUserResultReceipt(request_id=request.request_id,session_id=request.session_id,task_id=request.task_id)

    def ingest(self, request, user_id):
        now = int(self.clock())
        ack = [e.event_id for e in request.events]
        with self.factory() as db:
            users = UserRepository(db)
            users.lock_user(user_id)
            session = db.get(AgentSession, request.session_id)
            if session is None or session.user_id != user_id:
                raise HTTPException(404)
            consent = users.latest_consent(user_id, 'model_training')
            if not consent or not consent.consented or request.consent_id!=consent.id:
                return TaskExperienceReceipt(status='ineligible', acknowledged=ack)
            granted_ms = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()*1000
            # Reserve a separate sequence range; never mutate the running agent's version.
            sequence = max(1_000_000, db.scalar(select(func.max(RuntimeEvent.sequence)).where(
                RuntimeEvent.session_id==request.session_id, RuntimeEvent.kind=='app_task')) or 0)
            tasks = {}
            for event in request.events:
                body = event.model_dump(mode='json')
                if not max(granted_ms, (now-86400)*1000) <= event.observed_at_ms <= (now+60)*1000:
                    continue
                request_id = 'app-task:'+consent.id+':'+event.event_id
                existing = db.scalar(select(RuntimeEvent).where(RuntimeEvent.user_id==user_id,
                    RuntimeEvent.kind=='app_task', RuntimeEvent.request_id==request_id))
                if existing:
                    if existing.session_id!=request.session_id or existing.payload.get('app_task')!=body:
                        raise HTTPException(409, 'EXPERIENCE_ID_REUSED')
                    continue
                if event.task_id not in tasks:
                    rows = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.user_id==user_id,
                        RuntimeEvent.kind=='app_task', RuntimeEvent.episode_id==event.task_id).limit(67)))
                    if len(rows)>66:
                        raise HTTPException(409, 'EXPERIENCE_BUDGET')
                    tasks[event.task_id] = {r.payload['app_task']['sequence']:r.payload['app_task'] for r in rows}
                task = tasks[event.task_id]
                if event.sequence in task or len(task)>=66 or any(
                        e['goal_key']!=event.goal_key for e in task.values()):
                    raise HTTPException(409, 'EXPERIENCE_SEQUENCE_CONFLICT')
                task[event.sequence] = body
                sequence += 1
                db.add(RuntimeEvent(session_id=session.id,user_id=user_id,episode_id=event.task_id,
                    sequence=sequence,request_id=request_id,kind='app_task',graph_hash=POLICY_HASH,
                    payload={'app_task':body,'consent_id':consent.id,'policy_hash':POLICY_HASH,
                        'source_kind':'device_reported','received_at_ms':int(self.clock()*1000)},created_at=now,
                    expires_at=min(now+7*86400,event.observed_at_ms//1000+7*86400)))
                db.flush()
            db.commit()
        return TaskExperienceReceipt(status='accepted', acknowledged=ack)

    def recall(self, request, user_id):
        screen = request.screen
        result = TaskMemoryResponse(request_id=request.request_id, observation_id=screen.observation_id,
                                    fingerprint=screen.fingerprint)
        if not -2000<=self.clock()*1000-screen.captured_at_ms<=15000:
            return result
        now = int(self.clock())
        try:
            with self.factory() as db:
                users = UserRepository(db)
                consent = users.latest_consent(user_id,'model_training')
                if not consent or not consent.consented:
                    result.status='disabled'
                    return result
                result.collection_enabled=True;result.consent_id=consent.id
                granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
                import math
                result.consent_recorded_at_ms=math.ceil(granted*1000)
                if screen.status!='public':return result
                try:
                    env=TaskEnvironment.model_validate({'package':screen.package,**request.environment})
                    if env.package!=screen.package or env.app_version=='unknown':return result
                except ValueError:return result
                rows = list(db.execute(select(RuntimeEventConsumption,RuntimeEvent).join(
                    RuntimeEvent,RuntimeEventConsumption.event_id==RuntimeEvent.id).where(
                    RuntimeEventConsumption.consumer==CONSUMER,RuntimeEvent.user_id==user_id,
                    RuntimeEventConsumption.expires_at>now,RuntimeEvent.expires_at>now,
                    RuntimeEvent.created_at>=int(granted)).order_by(RuntimeEvent.created_at.desc(),RuntimeEvent.id).limit(100)))
                ids = {i for r,_ in rows for i in r.result.get('source_event_ids',[])[:66]}
                sources = {e.id:e for e in db.scalars(select(RuntimeEvent).where(RuntimeEvent.id.in_(ids),
                    RuntimeEvent.user_id==user_id,RuntimeEvent.kind=='app_task',RuntimeEvent.expires_at>now,
                    RuntimeEvent.created_at>=int(granted)))}
                memories = []
                for receipt,anchor in rows:
                    r = receipt.result; analysis = r.get('analysis',{})
                    if (r.get('consent_id')!=consent.id or r.get('policy_hash')!=POLICY_HASH
                            or r.get('analysis_hash')!=fingerprint(analysis)
                            or analysis.get('schema_version')!='ai2-app-task-analysis.v1'
                            or analysis.get('status')!='ANALYZED'):
                        continue
                    source_ids = r.get('source_event_ids',[])
                    if (not source_ids or not all(i in sources and sources[i].payload.get('consent_id')==consent.id
                            and sources[i].episode_id==anchor.episode_id for i in source_ids)):
                        continue
                    # A source deleted from another host session invalidates the whole memory.
                    memories.append((anchor.episode_id,analysis))
            return self._ground(result,request,env,memories)
        except Exception as error:
            log.warning('AI2 app-task recall unavailable (%s)',type(error).__name__)
            result.status='unavailable';result.collection_enabled=False;result.consent_id=None
            return result

    @staticmethod
    def _ground(result,request,environment,memories):
        screen = request.screen; goal = digest(request.goal); current_key = screen_key(screen)
        nodes = defaultdict(list)
        for n in screen.nodes:
            if (n.source=='accessibility' and not PRIVATE.search(n.resource) and not BOUNDARY.search(n.label+' '+n.resource)
                    and not re.search(r'동의|허용|승인|accept|allow',n.label,re.I)):
                nodes[target_key(n)].append(n)
        grouped, tactics = defaultdict(list),defaultdict(set)
        active = next((g for g in request.state.subgoals if g.status=='pending'),None)
        for task_id,analysis in memories:
            for t in analysis.get('tactics',[]):
                tactics[(t['tool'],t['role'],t['advice'])].add(task_id)
            if analysis.get('goal_key')!=goal:
                continue
            for step in analysis.get('steps',[]):
                if step.get('environment')!=environment.model_dump() or step.get('screen_key')!=current_key:
                    continue
                if active:
                    if step.get('subgoal_key')!=digest(active.title):continue
                elif (not step.get('has_initial_context') or step.get('path_prefix')!=request.path_prefix
                        or not request.path_prefix and any(a.outcome=='verified' for a in request.state.attempts)):
                    continue
                a = step['action']
                # Competing destinations remain competing; there is no invented merged route.
                key = (a['tool'],a['target_key'],a['expectation'],a['expected_package'],step['after_key'])
                grouped[key].append((task_id,{**step,'confirmed_route':bool(analysis.get('route') and analysis.get('user_confirmed'))}))
        result.tactics = [{'tool':t,'role':r,'advice':a,'supporting_tasks':len(tasks)}
            for (t,r,a),tasks in sorted(tactics.items()) if len(tasks)>=2][:12]
        choices = []
        adverse_by_target=defaultdict(int);adverse_arrival=defaultdict(int)
        for (tool,target,*_),records in grouped.items():
            adverse_by_target[(tool,target)]=max(adverse_by_target[(tool,target)],max(
                (s['observed_at_ms'] for _,s in records if s['outcome']!='verified'),default=0))
            adverse_arrival[(tool,target)]=max(adverse_arrival[(tool,target)],max(
                (s['received_at_ms'] for _,s in records if s['outcome']!='verified'),default=0))
        for key,records in grouped.items():
            tool,target,kind,package,after_key = key
            match = nodes.get(target,[])
            if tool not in {'back','open_app'} and len(match)!=1:
                continue
            node = match[0] if match else None
            if tool=='open_app' and package not in {a.package for a in request.apps}:
                continue
            if node and (tool=='tap' and (not node.clickable or node.editable) or tool=='set_text' and not node.editable
                         or tool.startswith('scroll_') and not node.scrollable):
                continue
            adverse = adverse_by_target[(tool,target)]
            successful=[s for _,s in records if s['outcome']=='verified' and s['confirmed_route']
                and s['observed_at_ms']>adverse and s['received_at_ms']>adverse_arrival[(tool,target)]]
            supports = {t for t,s in records if s in successful}
            counts = {name:len({t for t,s in records if s['outcome']==outcome}) for name,outcome in
                      [('successes','verified'),('rejections','rejected'),('unconfirmed','unconfirmed')]}
            result.evidence.append({'tool':tool,'target_id':node.id if node else None,**counts,
                                    'supporting_tasks_after_adverse':len(supports)})
            if len(supports)<2 or tool not in {'back','open_app'} and node is None:
                continue
            last = max(successful,key=lambda s:s['observed_at_ms'])
            expectation = TaskExpectation(kind=kind if kind!='target_visible' else 'screen_changed',
                package=package,screen_key=after_key if kind in {'screen_changed','target_visible'} else '')
            choices.append(TaskAction(id=str(uuid4()),tool=tool,target_id=node.id if node else None,
                bounds=node.bounds if node else None,package=package if tool=='open_app' else screen.package,
                expectation=expectation,advances_subgoal=bool(active and digest(active.title)==last['subgoal_key']
                                                            and last['action']['advances_subgoal'])))
        result.evidence = result.evidence[:12]
        if len(choices)==1:
            result.action=choices[0];result.status='remembered'
        return result
