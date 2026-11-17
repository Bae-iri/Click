"""Durable Agent(2) draft generation, after the online browsing response.

Only encrypted graph proposals are written. ApprovedTaskGraph is deliberately
not a destination: a captured successful route still needs independent review.
"""
from datetime import timezone
import logging
import time
from pathlib import Path
import sys

BACKEND = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(BACKEND), str(BACKEND.parent / "AI-Agent" / "src")]

from sqlalchemy import and_, cast, Float, func, select

from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.proposals.browse_graph import (
    VERSION, GRAPH_SCHEMA, IncompleteBrowseTrace, generate_browse_graph,
)
from app.models.agent_session import AgentSession
from app.models.graph_proposal import BROWSE_GRAPH_CONSUMER, GraphProposal
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.user import Consent
from app.repositories.users import UserNotFoundError, UserRepository
from app.services.browse_policy import POLICY

log = logging.getLogger(__name__)


class BrowseGraphLearningService:
    def __init__(self, factory, clock=time.time):
        self.factory, self.clock = factory, clock

    def learn_session(self, session_id, user_id, *, now=None):
        now = int(self.clock()) if now is None else now
        with self.factory() as db:
            users = UserRepository(db)
            users.lock_user(user_id)  # Serializes with consent, deletion and other learners.
            session = db.get(AgentSession, session_id)
            if (session is None or session.user_id != user_id or session.status != 'COMPLETED'
                    or (session.workflow or {}).get('version') != POLICY['version']):
                return {'status': 'INELIGIBLE'}
            consent = users.latest_consent(user_id, 'model_training')
            if not consent or not consent.consented:
                return {'status': 'INELIGIBLE'}
            granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
            # Read the complete trace before applying consent/retention fences.
            # A missing/expired/pre-grant prefix cannot become a claimed full route.
            events = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == session_id,
                RuntimeEvent.user_id == user_id, RuntimeEvent.kind == 'browse')
                .order_by(RuntimeEvent.sequence).limit(POLICY['max_turns'] + 1)))
            if (not events or len(events) > POLICY['max_turns']
                    or any(e.expires_at <= now or e.created_at < granted for e in events)):
                return {'status': 'INELIGIBLE'}
            anchor = events[-1]
            input_hash = fingerprint({'generator': VERSION, 'consent': consent.id,
                'events': [{'id': e.id, 'payload': e.payload} for e in events]})
            receipt = db.scalar(select(RuntimeEventConsumption).where(
                RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER,
                RuntimeEventConsumption.event_id == anchor.id))
            if receipt and receipt.result.get('input_hash') == input_hash:
                return {'status': 'UNCHANGED', 'proposal_id': receipt.result.get('proposal_id')}
            if receipt:
                # Replacing a receipt deletes its old draft and any evaluation.
                db.delete(receipt)
                db.flush()
            result = {'schema_version': 'browse-graph-learning.v1', 'generator': VERSION,
                'input_hash': input_hash, 'expires_at': min(e.expires_at for e in events),
                'status': 'NEEDS_DATA', 'automatic_release': False}
            receipt = RuntimeEventConsumption(event_id=anchor.id, consumer=BROWSE_GRAPH_CONSUMER,
                result=dict(result), consumed_at=now)
            db.add(receipt)
            db.flush()
            records = [{'event_id': e.id, 'sequence': e.sequence, 'evidence': e.payload.get('browse', {})}
                       for e in events]
            try:
                body = generate_browse_graph(records, POLICY)
            except IncompleteBrowseTrace as error:
                result['reason'] = str(error)
            else:
                graph_hash = fingerprint(body)
                proposal_id = 'browse-' + fingerprint({'graph': graph_hash, 'input': input_hash})[:48]
                evidence = {'schema_version': 'browse-graph-evidence.v1', 'generator': VERSION,
                    'event_ids': [e.id for e in events], 'graph_hash': graph_hash,
                    'code_hash': records[0]['evidence']['code_hash'], 'policy_hash': body['policy_hash'],
                    'outcome': 'verified_navigation', 'confidence_kind': 'linked_trace_completeness',
                    'independent_device_validation': 'NOT_ASSESSED'}
                db.add(GraphProposal(id=proposal_id, task_id=body['definition']['task_id'],
                    base_graph_version='graphless', proposal_type='new_browse_task_graph',
                    change={'schema_version': GRAPH_SCHEMA, 'graph': body}, evidence=evidence,
                    proposal_confidence=1.0, requires_human_review=True, created_at=now,
                    runtime_receipt_id=receipt.id))
                result.update(status='DRAFT_CREATED', proposal_id=proposal_id, graph_hash=graph_hash,
                    goal_type=body['definition']['goal_type'], requires_human_review=True)
            receipt.result = dict(result)
            db.commit()
            return {k: result[k] for k in ('status', 'proposal_id', 'reason', 'graph_hash') if k in result}

    def process(self, *, limit=20, now=None):
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ValueError('limit must be between 1 and 1000')
        now = int(self.clock()) if now is None else now
        with self.factory() as db:
            latest = select(RuntimeEvent.session_id, func.max(RuntimeEvent.sequence).label('sequence'),
                func.min(RuntimeEvent.sequence).label('first_sequence'),
                func.min(RuntimeEvent.created_at).label('first_created_at'),
                func.min(RuntimeEvent.expires_at).label('expires_at')).where(
                RuntimeEvent.kind == 'browse').group_by(RuntimeEvent.session_id).subquery()
            latest_consent = select(Consent.id).where(Consent.user_id == RuntimeEvent.user_id,
                Consent.purpose == 'model_training').order_by(Consent.recorded_at.desc(), Consent.id.desc())
            latest_consent = latest_consent.correlate(RuntimeEvent).limit(1).scalar_subquery()
            grant_time = ((func.julianday(Consent.recorded_at) - 2440587.5) * 86400
                          if db.bind.dialect.name == 'sqlite' else func.extract('epoch', Consent.recorded_at))
            pending = list(db.execute(select(RuntimeEvent.session_id, RuntimeEvent.user_id)
                .join(latest, and_(latest.c.session_id == RuntimeEvent.session_id,
                                  latest.c.sequence == RuntimeEvent.sequence))
                .join(AgentSession, AgentSession.id == RuntimeEvent.session_id)
                .join(Consent, Consent.id == latest_consent)
                .outerjoin(RuntimeEventConsumption, and_(RuntimeEventConsumption.event_id == RuntimeEvent.id,
                    RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER))
                .where(RuntimeEvent.kind == 'browse', RuntimeEvent.expires_at > now,
                    AgentSession.status == 'COMPLETED', Consent.consented.is_(True),
                    latest.c.first_sequence == 1, latest.c.expires_at > now,
                    latest.c.first_created_at >= cast(grant_time, Float), RuntimeEventConsumption.id.is_(None))
                .order_by(RuntimeEvent.created_at, RuntimeEvent.id).limit(limit + 1)))
        summary = {'created': 0, 'needs_data': 0, 'unchanged': 0, 'ineligible': 0, 'errors': 0,
                   'has_more': len(pending) > limit}
        for session_id, user_id in pending[:limit]:
            try:
                result = self.learn_session(session_id, user_id, now=now)
                key = {'DRAFT_CREATED': 'created', 'NEEDS_DATA': 'needs_data',
                       'UNCHANGED': 'unchanged', 'INELIGIBLE': 'ineligible'}[result['status']]
                summary[key] += 1
            except UserNotFoundError:
                summary['ineligible'] += 1
            except Exception as error:
                # No raw SQL parameters, screen payloads or account identifiers.
                log.warning('AI2 browse draft generation failed (%s)', type(error).__name__)
                summary['errors'] += 1
        return summary

    def list_drafts(self, *, limit=100, now=None):
        """Operator metadata only; no user/session IDs or stored payload export."""
        if type(limit) is not int or not 1 <= limit <= 1000:
            raise ValueError('limit must be between 1 and 1000')
        now = int(self.clock()) if now is None else now
        with self.factory() as db:
            rows = db.execute(select(GraphProposal, RuntimeEventConsumption.expires_at)
                .join(RuntimeEventConsumption, GraphProposal.runtime_receipt_id == RuntimeEventConsumption.id)
                .where(RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER,
                    RuntimeEventConsumption.expires_at > now)
                .order_by(GraphProposal.created_at.desc(), GraphProposal.id).limit(limit)).all()
            return [{'proposal_id': row.id, 'task_id': row.task_id,
                'goal_type': row.change['graph']['definition']['goal_type'],
                'graph_hash': row.evidence['graph_hash'], 'states': len(row.change['graph']['states']),
                'transitions': len(row.change['graph']['transitions']), 'requires_human_review': True,
                'created_at': row.created_at, 'expires_at': expires} for row, expires in rows]


def main():
    import json
    import os
    from app.database import build_engine, build_session_factory
    from app.core.data_encryption import validate_database_keys
    raw = sys.stdin.buffer.read(4097)
    engine = None
    try:
        job = json.loads(raw)
        if len(raw) > 4096 or not isinstance(job, dict) or set(job) not in ({'session_id', 'user_id'}, {'limit'}):
            raise ValueError('Invalid graph learning job')
        if not os.getenv('DATABASE_URL'):
            raise ValueError('DATABASE_URL is required')
        engine = build_engine()
        validate_database_keys(engine)
        learner = BrowseGraphLearningService(build_session_factory(engine))
        if 'session_id' in job:
            if any(not isinstance(job[k], str) or not 1 <= len(job[k]) <= 200 for k in ('session_id', 'user_id')):
                raise ValueError('Invalid graph learning job')
            result = learner.learn_session(job['session_id'], job['user_id'])
        else:
            result = learner.process(limit=job['limit'])
        print(json.dumps(result))
        return int(bool(result.get('errors')))
    except UserNotFoundError:
        print(json.dumps({'status': 'INELIGIBLE'}))
        return 0
    except Exception:
        # Never export connection strings, SQL values or source evidence.
        print(json.dumps({'status': 'ERROR'}))
        return 1
    finally:
        if engine is not None:
            engine.dispose()


if __name__ == '__main__':
    raise SystemExit(main())
