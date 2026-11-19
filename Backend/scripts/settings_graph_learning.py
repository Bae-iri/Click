"""Offline AI2 bridge for the fixed Settings A/B pilot. Drafts never alter either arm."""
from datetime import timezone
import time
from sqlalchemy import select
from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.proposals.settings_route import VERSION, generate_route, IncompleteSettingsTrace
from app.models.agent_session import AgentSession
from app.models.graph_proposal import GraphProposal, SETTINGS_GRAPH_CONSUMER
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.users import UserRepository
from app.services.settings_planner import GOALS


class SettingsGraphLearningService:
    def __init__(self, factory):
        self.factory = factory

    def learn_episode(self, session_id, user_id, episode_id, *, now=None):
        now = int(time.time()) if now is None else now
        with self.factory() as db:
            users = UserRepository(db)
            users.lock_user(user_id)
            session = db.get(AgentSession, session_id)
            consent = users.latest_consent(user_id, 'model_training')
            if not session or session.user_id != user_id or not consent or not consent.consented:
                return {'status':'INELIGIBLE'}
            granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
            events = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == session_id,
                RuntimeEvent.user_id == user_id, RuntimeEvent.episode_id == episode_id, RuntimeEvent.kind == 'turn')
                .order_by(RuntimeEvent.sequence).limit(257)))
            if (not events or len(events)>256 or any(e.expires_at<=now or e.created_at<granted for e in events)
                    or any(e.payload.get('source_kind') != 'runtime' for e in events)):
                return {'status':'INELIGIBLE'}
            records = [{'event_id':e.id,'trace':e.payload.get('settings_experiment',{}),
                        'runtime':e.payload.get('runtime',{})} for e in events]
            goal = records[0]['trace'].get('goal_type')
            if goal not in GOALS or records[0]['trace'].get('arm') != 'llm':
                return {'status':'INELIGIBLE'}
            input_hash = fingerprint(dict(generator=VERSION, consent=consent.id, records=records))
            receipt = db.scalar(select(RuntimeEventConsumption).where(
                RuntimeEventConsumption.event_id==events[-1].id, RuntimeEventConsumption.consumer==SETTINGS_GRAPH_CONSUMER))
            if receipt and receipt.result.get('input_hash') == input_hash:
                return {'status':'UNCHANGED'}
            if receipt:
                db.delete(receipt)
                db.flush()
            last = records[-1]['trace']
            # Reuse outcomes let the runtime quarantine routes that failed after being learned.
            rejected = sorted({h for t in records for h in t['trace'].get('rejected_routes', [])
                               if isinstance(h, str) and len(h) <= 128})
            result = dict(schema_version=VERSION, input_hash=input_hash, status='NEEDS_DATA',
                expires_at=min(e.expires_at for e in events), automatic_release=False,
                run_ref=last.get('run_ref'), plan_source=last.get('plan_source'),
                reused_route_hash=last.get('route_hash') if last.get('plan_source') == 'learned_route' else None,
                rejected_route_hashes=rejected, analysis_consent_id=consent.id,
                compatibility={k: records[0]['trace'].get(k) for k in
                    ('scope_hash', 'base_graph_hash', 'runtime_code_hash', 'jev_model')},
                provider_failures=sum(t['trace'].get('planner',{}).get('failure_kind')=='provider' for t in records),
                verified_failures=max(t['trace'].get('total_failed_attempts',0) for t in records))
            receipt = RuntimeEventConsumption(event_id=events[-1].id, consumer=SETTINGS_GRAPH_CONSUMER,
                result=dict(result), consumed_at=now)
            db.add(receipt)
            db.flush()
            from app.models.task_graph import ApprovedTaskGraph
            reference = events[0].payload.get('graph_reference', {})
            artifact = db.scalar(select(ApprovedTaskGraph).where(ApprovedTaskGraph.graph_hash==reference.get('sha256')))
            try:
                if not artifact or not artifact.approved:
                    raise IncompleteSettingsTrace('missing_approved_base')
                if any(e.payload.get('graph_reference') != reference for e in events):
                    raise IncompleteSettingsTrace('base_graph_changed')
                route = generate_route(records, artifact.body)
            except (IncompleteSettingsTrace, KeyError, TypeError) as error:
                result['reason_code'] = str(error) if isinstance(error, IncompleteSettingsTrace) else 'invalid_trace'
            else:
                route_hash = fingerprint(route)
                proposal_id = 'settings-' + fingerprint(dict(route=route_hash,input=input_hash))[:48]
                db.add(GraphProposal(id=proposal_id,task_id=route['definition']['task_id'],
                    base_graph_version=reference.get('version','unknown'),proposal_type='settings_observed_route',
                    change={'schema_version':VERSION,'graph':route},
                    evidence={'event_ids':[e.id for e in events],'route_hash':route_hash,
                        'independent_device_validation':'NOT_ASSESSED','automatic_release':False},
                    proposal_confidence=1.0,requires_human_review=True,created_at=now,runtime_receipt_id=receipt.id))
                result.update(status='DRAFT_CREATED',proposal_id=proposal_id,route_hash=route_hash)
            receipt.result=dict(result)
            db.commit()
            return {k:result[k] for k in ('status','proposal_id','route_hash','reason_code') if k in result}

    def process(self, *, limit=100, now=None):
        if type(limit) is not int or not 1<=limit<=1000:
            raise ValueError('invalid_limit')
        now = int(time.time()) if now is None else now
        with self.factory() as db:
            # Select the latest event for each episode before limiting, so old sessions cannot starve new ones.
            from sqlalchemy import func, and_
            latest=select(RuntimeEvent.session_id,RuntimeEvent.episode_id,
                func.max(RuntimeEvent.sequence).label('seq')).where(RuntimeEvent.kind=='turn',
                RuntimeEvent.expires_at>now).group_by(RuntimeEvent.session_id,RuntimeEvent.episode_id).subquery()
            rows=list(db.execute(select(RuntimeEvent.session_id,RuntimeEvent.user_id,RuntimeEvent.episode_id)
                .join(latest,and_(latest.c.session_id==RuntimeEvent.session_id,
                    latest.c.episode_id==RuntimeEvent.episode_id,latest.c.seq==RuntimeEvent.sequence))
                .join(AgentSession,AgentSession.id==RuntimeEvent.session_id)
                .outerjoin(RuntimeEventConsumption,and_(RuntimeEventConsumption.event_id==RuntimeEvent.id,
                    RuntimeEventConsumption.consumer==SETTINGS_GRAPH_CONSUMER))
                .where(RuntimeEventConsumption.id.is_(None),RuntimeEvent.expires_at>now,
                    AgentSession.accepted_goal_type.in_(GOALS),
                    RuntimeEvent.payload.is_not(None)).order_by(RuntimeEvent.created_at.desc()).limit(limit)))
        counts=dict(created=0,needs_data=0,ineligible=0,unchanged=0,errors=0)
        for session,user,episode in rows:
            try:
                result=self.learn_episode(session,user,episode,now=now)
                counts[{'DRAFT_CREATED':'created','NEEDS_DATA':'needs_data','INELIGIBLE':'ineligible',
                        'UNCHANGED':'unchanged'}[result['status']]]+=1
            except Exception:
                counts['errors']+=1
        return counts
