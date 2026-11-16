"""Consent and retention enforced offline; browsing never waits for AI2."""
from datetime import timezone
import time
from sqlalchemy import select
from app.models.runtime_event import RuntimeEvent
from app.models.agent_session import AgentSession
from app.repositories.users import UserRepository
from ai_agent.graph_proposal.analysis.browse import assess_sessions


def assess_browsing(factory, limit=100, now=None):
    if type(limit) is not int or not 1 <= limit <= 1000:
        raise ValueError('limit must be between 1 and 1000')
    now = int(time.time()) if now is None else now
    records = []
    with factory() as db:
        sessions = list(db.execute(select(RuntimeEvent.session_id,RuntimeEvent.user_id).where(
            RuntimeEvent.kind=='browse',RuntimeEvent.expires_at>now).distinct().limit(limit+1)))
        if len(sessions)>limit:
            raise ValueError('Browse assessment exceeds session budget')
        users = UserRepository(db)
        for session_id,user_id in sessions:
            users.lock_user(user_id)
            consent = users.latest_consent(user_id,'model_training')
            if not consent or not consent.consented:
                continue
            granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
            events = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id==session_id,
                RuntimeEvent.kind=='browse',RuntimeEvent.created_at>=granted,RuntimeEvent.expires_at>now)
                .order_by(RuntimeEvent.sequence).limit(1001)))
            if len(events)>1000:
                raise ValueError('Browse assessment exceeds event budget')
            if events:
                evidence = [event.payload['browse'] for event in events]
                row = db.get(AgentSession,session_id)
                if row.status in {'CANCELLED','EXPIRED'} and evidence[-1]['outcome'] in {'in_progress','unknown'}:
                    evidence[-1] = {**evidence[-1],'outcome':'cancelled' if row.status=='CANCELLED' else 'unknown'}
                records.append(evidence)
        return assess_sessions(records)
