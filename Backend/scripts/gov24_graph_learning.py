"""Offline Government24 graph learning worker, outside the HTTP runtime."""
import time
from sqlalchemy import and_, cast, Float, func, select
from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.proposals.gov24_graph import VERSION, SCHEMA, IncompleteGovernmentTrace, generate_graph
from app.models.agent_session import AgentSession
from app.models.graph_proposal import GOV24_GRAPH_CONSUMER, GraphProposal
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.user import Consent
from app.repositories.users import UserRepository
from app.services.gov24_graphs import eligible_events
from app.services.public_guide_policy import CIVIL, CIVIL_GRAPHS, POLICY


class GovernmentGraphLearningService:
    def __init__(self, factory, clock=time.time):
        self.factory, self.clock = factory, clock

    def learn_session(self, session_id, user_id, *, now=None):
        from app.services.gov24_navigation import CODE_HASH
        now = int(self.clock()) if now is None else now
        with self.factory() as db:
            UserRepository(db).lock_user(user_id)
            session = db.get(AgentSession, session_id)
            if (not session or session.user_id != user_id
                    or session.status not in {'IN_PROGRESS', 'COMPLETED', 'BLOCKED', 'CANCELLED'}
                    or (session.workflow or {}).get('goal', {}).get('goal_type') not in CIVIL_GRAPHS):
                return {'status': 'INELIGIBLE'}
            events = eligible_events(db, session_id, user_id, now)
            if not events:
                return {'status': 'INELIGIBLE'}
            records = [dict(event_id=e.id, sequence=e.sequence, evidence=e.payload.get('public_guide', {})) for e in events]
            if any(r['evidence'].get('code_hash') != CODE_HASH for r in records):
                return {'status': 'INELIGIBLE'}
            input_hash = fingerprint(dict(generator=VERSION, records=records))
            receipt = db.scalar(select(RuntimeEventConsumption).where(
                RuntimeEventConsumption.consumer == GOV24_GRAPH_CONSUMER, RuntimeEventConsumption.event_id == events[-1].id))
            if receipt and receipt.result.get('input_hash') == input_hash:
                return {'status': 'UNCHANGED', 'proposal_id': receipt.result.get('proposal_id')}
            if receipt:
                db.delete(receipt)
                db.flush()
            result = dict(schema_version='gov24-graph-learning.v1', generator=VERSION, input_hash=input_hash,
                status='NEEDS_DATA', expires_at=min(e.expires_at for e in events), automatic_submission=False)
            receipt = RuntimeEventConsumption(event_id=events[-1].id, consumer=GOV24_GRAPH_CONSUMER,
                result=dict(result), consumed_at=now)
            db.add(receipt)
            db.flush()
            try:
                graph = generate_graph(records, CIVIL, POLICY['package'])
            except IncompleteGovernmentTrace as error:
                result['reason'] = str(error)
            else:
                graph_hash = fingerprint(graph)
                proposal_id = 'gov24-' + fingerprint(dict(graph=graph_hash, input=input_hash))[:48]
                db.add(GraphProposal(id=proposal_id, task_id=graph['definition']['task_id'], base_graph_version='public_guide',
                    proposal_type='new_gov24_task_graph', change=dict(schema_version=SCHEMA, graph=graph),
                    evidence=dict(event_ids=[e.id for e in events], graph_hash=graph_hash, code_hash=CODE_HASH,
                        outcome='verified_navigation', independent_device_validation='NOT_ASSESSED'),
                    proposal_confidence=1.0, requires_human_review=False, created_at=now, runtime_receipt_id=receipt.id))
                result.update(status='DRAFT_CREATED', proposal_id=proposal_id, graph_hash=graph_hash)
            receipt.result = dict(result)
            db.commit()
            return {k: result[k] for k in ('status', 'proposal_id', 'graph_hash', 'reason') if k in result}

    def process(self, *, limit=20, now=None):
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ValueError('invalid_limit')
        now = int(self.clock()) if now is None else now
        with self.factory() as db:
            latest = select(RuntimeEvent.session_id, func.max(RuntimeEvent.sequence).label('seq'),
                func.min(RuntimeEvent.sequence).label('first_sequence'),
                func.min(RuntimeEvent.created_at).label('first_created_at'),
                func.min(RuntimeEvent.expires_at).label('expires_at')).where(
                RuntimeEvent.kind == 'public_guide').group_by(RuntimeEvent.session_id).subquery()
            latest_consent = select(Consent.id).where(Consent.user_id == RuntimeEvent.user_id,
                Consent.purpose == 'model_training').order_by(Consent.recorded_at.desc(), Consent.id.desc())
            latest_consent = latest_consent.correlate(RuntimeEvent).limit(1).scalar_subquery()
            grant_time = ((func.julianday(Consent.recorded_at) - 2440587.5) * 86400
                if db.bind.dialect.name == 'sqlite' else func.extract('epoch', Consent.recorded_at))
            pending = list(db.execute(select(RuntimeEvent.session_id, RuntimeEvent.user_id)
                .join(latest, and_(latest.c.session_id == RuntimeEvent.session_id, latest.c.seq == RuntimeEvent.sequence))
                .join(AgentSession, AgentSession.id == RuntimeEvent.session_id)
                .join(Consent, Consent.id == latest_consent)
                .outerjoin(RuntimeEventConsumption, and_(RuntimeEventConsumption.event_id == RuntimeEvent.id,
                    RuntimeEventConsumption.consumer == GOV24_GRAPH_CONSUMER))
                .where(RuntimeEvent.kind == 'public_guide', AgentSession.accepted_goal_type.in_(CIVIL_GRAPHS),
                    RuntimeEvent.expires_at > now, RuntimeEventConsumption.id.is_(None), Consent.consented.is_(True),
                    latest.c.first_sequence == 1, latest.c.expires_at > now,
                    latest.c.first_created_at >= cast(grant_time, Float))
                .order_by(RuntimeEvent.created_at, RuntimeEvent.id).limit(limit)))
        summary = dict(created=0, needs_data=0, unchanged=0, ineligible=0, errors=0)
        for session_id, user_id in pending:
            try:
                result = self.learn_session(session_id, user_id, now=now)
                summary[{'DRAFT_CREATED': 'created', 'NEEDS_DATA': 'needs_data',
                    'UNCHANGED': 'unchanged', 'INELIGIBLE': 'ineligible'}[result['status']]] += 1
            except Exception:
                summary['errors'] += 1
        return summary
