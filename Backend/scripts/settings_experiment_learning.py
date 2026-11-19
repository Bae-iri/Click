"""Consent-fenced AI2 Settings learning. Default CLI only lists retained results."""
import argparse
import json
import os
from pathlib import Path
import sys
import time

BACKEND=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(BACKEND),str(BACKEND.parent/'AI-Agent/src')]


def report(factory, now=None):
    from sqlalchemy import select
    from app.models.graph_proposal import GraphProposal, SETTINGS_GRAPH_CONSUMER
    from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
    from app.repositories.users import UserRepository
    now=int(time.time()) if now is None else now
    rows=[]
    with factory() as db:
        receipts=db.execute(select(RuntimeEventConsumption,RuntimeEvent)
            .join(RuntimeEvent,RuntimeEventConsumption.event_id==RuntimeEvent.id)
            .where(RuntimeEventConsumption.consumer==SETTINGS_GRAPH_CONSUMER,
                RuntimeEventConsumption.expires_at>now,RuntimeEvent.expires_at>now)
            .order_by(RuntimeEventConsumption.consumed_at.desc()).limit(200)).all()
        for receipt,event in receipts:
            consent=UserRepository(db).latest_consent(event.user_id,'model_training')
            if not consent or not consent.consented:continue
            result=receipt.result
            candidate=db.scalar(select(GraphProposal).where(GraphProposal.runtime_receipt_id==receipt.id))
            rows.append(dict(run_ref=(event.payload.get('settings_experiment') or {}).get('run_ref'),
                goal_type=(event.payload.get('settings_experiment') or {}).get('goal_type'),
                status=result.get('status'),reason_code=result.get('reason_code'),
                route_hash=result.get('route_hash'),provider_failures=result.get('provider_failures',0),
                verified_failures=result.get('verified_failures',0),expires_at=receipt.expires_at,
                transition_count=len(candidate.change['graph']['transitions']) if candidate else 0,
                automatic_release=False,independent_device_validation='NOT_ASSESSED'))
    from app.services.settings_graphs import route_inventory, reuse_enabled, min_support
    routes=route_inventory(factory,now=now)
    return {'schema_version':'settings-learning-report.v2','results':rows,
        'drafts':sum(r['status']=='DRAFT_CREATED' for r in rows),
        'routes':routes,'route_min_support':min_support(),'route_reuse_enabled':reuse_enabled(),
        'active_learned_graphs':sum(r['status']=='VALIDATED' for r in routes) if reuse_enabled() else 0}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',type=Path,default=BACKEND/'instance/settings-experiment/.env')
    parser.add_argument('--process',action='store_true')
    parser.add_argument('--worker',action='store_true',help=argparse.SUPPRESS)
    args=parser.parse_args()
    worker=args.worker
    if worker:
        raw=sys.stdin.buffer.read(4097)
        job=json.loads(raw)
        if len(raw)>4096 or not isinstance(job,dict) or set(job)!={'limit'}:
            raise ValueError('invalid_settings_learning_job')
    else:
        from dotenv import load_dotenv
        if not args.env_file.is_file():parser.error('Prepare experiment environment first')
        load_dotenv(args.env_file,override=True)
        job={'limit':100}
    from app.database import build_engine,build_session_factory
    from app.core.data_encryption import validate_database_keys
    from scripts.settings_graph_learning import SettingsGraphLearningService
    if not os.getenv('DATABASE_URL'):parser.error('DATABASE_URL required')
    engine=build_engine()
    try:
        validate_database_keys(engine)
        factory=build_session_factory(engine)
        result=(SettingsGraphLearningService(factory).process(limit=job['limit'])
            if worker or args.process else report(factory))
        print(json.dumps(result,ensure_ascii=False))
        return int(bool(result.get('errors')))
    finally:
        engine.dispose()


if __name__=='__main__':
    try:raise SystemExit(main())
    except Exception:
        print(json.dumps({'status':'ERROR'}))
        raise SystemExit(1)
