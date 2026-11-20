"""Run Agent2 outside the interactive process; publish expiring experience receipts."""
from datetime import timezone
import logging
from pathlib import Path
import sys
import time

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(BACKEND))
sys.path.insert(0,str(BACKEND.parent/'AI-Agent/src'))

from sqlalchemy import delete, select, and_, literal
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.models.graph_proposal import GraphProposal
from app.models.user import Consent
from app.repositories.users import UserRepository, UserNotFoundError
from app.schemas.app_task_learning import TaskExperience
from app.services.app_task_learning_policy import CONSUMER, SEEN, POLICY_HASH, fingerprint
from ai_agent.graph_proposal.workflows.app_tasks import analyze_app_task, VERSION

log = logging.getLogger(__name__)


class AppTaskLearningService:
    def __init__(self,factory,clock=time.time):
        self.factory,self.clock=factory,clock

    def learn_task(self,task_id,user_id):
        now=int(self.clock())
        with self.factory() as db:
            users=UserRepository(db);users.lock_user(user_id)
            consent=users.latest_consent(user_id,'model_training')
            if not consent or not consent.consented:
                return {'status':'INELIGIBLE'}
            granted=consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
            events=list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.kind=='app_task',
                RuntimeEvent.episode_id==task_id,RuntimeEvent.user_id==user_id).limit(67)))
            if (not events or len(events)>66 or any(e.expires_at<=now or e.created_at<int(granted)
                    or e.payload.get('consent_id')!=consent.id or e.payload.get('policy_hash')!=POLICY_HASH
                    or e.payload.get('app_task',{}).get('observed_at_ms',0)<granted*1000 for e in events)):
                return {'status':'INELIGIBLE'}
            bodies=[(e,TaskExperience.model_validate(e.payload['app_task']).model_dump(mode='json')) for e in events]
            bodies.sort(key=lambda pair:pair[1]['sequence'])
            anchor=bodies[-1][0];source_ids=[e.id for e,_ in bodies]
            identity=fingerprint({'version':VERSION,'policy':POLICY_HASH,'consent':consent.id,
                                  'events':[{'id':e.id,'body':b} for e,b in bodies]})
            old=db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==CONSUMER,
                RuntimeEventConsumption.event_id==anchor.id))
            if old and old.result.get('input_hash')==identity:
                return {'status':'UNCHANGED'}
            db.execute(delete(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==CONSUMER,
                RuntimeEventConsumption.event_id.in_(source_ids)))
            analysis=analyze_app_task([{'event_id':e.id,'evidence':b,'received_at_ms':e.payload['received_at_ms']} for e,b in bodies],POLICY_HASH)
            expires=min(e.expires_at for e in events)
            receipt=RuntimeEventConsumption(event_id=anchor.id,consumer=CONSUMER,
                result={'analysis':analysis,'analysis_hash':fingerprint(analysis),'input_hash':identity,
                    'policy_hash':POLICY_HASH,'consent_id':consent.id,'source_event_ids':source_ids,'expires_at':expires},
                consumed_at=now)
            db.add(receipt);db.flush()
            if analysis['steps']:
                proposal_id='app-task-'+identity[:48]
                db.add(GraphProposal(id=proposal_id,task_id='APP-TASK',base_graph_version='app-task.v2',
                    proposal_type='app_task_experience',change={'schema_version':VERSION,
                        'steps':analysis['steps'],'route':analysis['route']},
                    evidence={'analysis_hash':fingerprint(analysis),'source_event_ids':source_ids,
                        'source_kind':'device_reported','independent_device_validation':'NOT_ASSESSED',
                        'automatic_execution':False},proposal_confidence=0.0,requires_human_review=False,
                    created_at=now,runtime_receipt_id=receipt.id))
            for e in events:
                seen=db.scalar(select(RuntimeEventConsumption).where(RuntimeEventConsumption.consumer==SEEN,
                    RuntimeEventConsumption.event_id==e.id))
                if not seen:
                    db.add(RuntimeEventConsumption(event_id=e.id,consumer=SEEN,
                        result={'expires_at':expires,'policy_hash':POLICY_HASH},consumed_at=now))
            db.commit()
            return {'status':analysis['status'],'steps':len(analysis['steps']),'route_steps':len(analysis['route'])}

    def learn_session(self,session_id,user_id):
        with self.factory() as db:
            tasks=list(db.scalars(select(RuntimeEvent.episode_id).where(RuntimeEvent.kind=='app_task',
                RuntimeEvent.session_id==session_id,RuntimeEvent.user_id==user_id).distinct().limit(20)))
        results=[self.learn_task(t,user_id) for t in tasks]
        return {'processed':len(results),'statuses':[r['status'] for r in results]}

    def process(self,limit=20):
        if type(limit) is not int or not 1<=limit<=1000:
            raise ValueError('Invalid processing limit')
        with self.factory() as db:
            latest_consent=select(Consent.id).where(Consent.user_id==RuntimeEvent.user_id,
                Consent.purpose=='model_training').order_by(Consent.recorded_at.desc(),Consent.id.desc()).correlate(RuntimeEvent).limit(1).scalar_subquery()
            pending=list(db.execute(select(RuntimeEvent.episode_id,RuntimeEvent.user_id).outerjoin(
                RuntimeEventConsumption,and_(RuntimeEventConsumption.event_id==RuntimeEvent.id,
                    RuntimeEventConsumption.consumer==SEEN)).join(Consent,Consent.id==latest_consent).where(RuntimeEvent.kind=='app_task',
                    Consent.consented.is_(True),RuntimeEvent.request_id.like(literal('app-task:')+Consent.id+literal(':%')),
                    RuntimeEvent.expires_at>int(self.clock()),RuntimeEventConsumption.id.is_(None))
                .group_by(RuntimeEvent.episode_id,RuntimeEvent.user_id).limit(limit)))
        report={'processed':0,'ineligible':0,'errors':0}
        for task_id,user_id in pending:
            try:
                status=self.learn_task(task_id,user_id)['status']
                report['ineligible' if status=='INELIGIBLE' else 'processed']+=1
            except UserNotFoundError:
                report['ineligible']+=1
            except Exception as error:
                log.warning('AI2 app-task analysis deferred (%s)',type(error).__name__)
                report['errors']+=1
        return report


def main():
    import json
    from app.database import build_engine, build_session_factory
    from app.core.data_encryption import validate_database_keys
    raw=sys.stdin.buffer.read(4097)
    if len(raw)>4096:
        raise ValueError('Oversized job')
    job=json.loads(raw)
    engine=build_engine()
    try:
        validate_database_keys(engine)
        service=AppTaskLearningService(build_session_factory(engine))
        result=(service.process(job.get('limit',20)) if 'session_id' not in job else
                service.learn_session(job['session_id'],job['user_id']))
        print(json.dumps(result))
    finally:
        engine.dispose()


if __name__=='__main__':
    main()
