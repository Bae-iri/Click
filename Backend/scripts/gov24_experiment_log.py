"""Read-only pilot traces from the tested backend DB; no model calls or raw AX export."""
import argparse
import json
import os
from pathlib import Path
import sys
import time

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent' / 'src'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=BACKEND / '.env')
    parser.add_argument('--session', help='Optional session ID to restrict the report')
    parser.add_argument('--follow', action='store_true')
    args = parser.parse_args()
    from dotenv import load_dotenv
    if not args.env_file.is_file():
        parser.error('Environment file does not exist')
    load_dotenv(args.env_file, override=True)
    os.chdir(BACKEND)
    from sqlalchemy import select
    from sqlalchemy.engine import make_url
    from app.database import build_engine, build_session_factory
    from app.models.runtime_event import RuntimeEvent
    from app.core.data_encryption import validate_database_keys
    from scripts.render_start import database_url
    raw = os.getenv('DATABASE_URL', '')
    if not raw:
        parser.error('DATABASE_URL must identify the tested backend database')
    target = make_url(raw)
    if target.drivername.startswith('sqlite') and (not target.database or not Path(target.database).is_file()):
        parser.error('An existing database is required')
    engine = build_engine(database_url(raw) if raw.startswith(('postgres://', 'postgresql://')) else raw)
    try:
        validate_database_keys(engine)
        factory = build_session_factory(engine)
        seen = set()
        while True:
            with factory() as db:
                query = select(RuntimeEvent).where(RuntimeEvent.kind == 'public_guide', RuntimeEvent.expires_at > int(time.time()))
                if args.session:
                    query = query.where(RuntimeEvent.session_id == args.session)
                events = list(db.scalars(query.order_by(RuntimeEvent.created_at.desc(), RuntimeEvent.sequence.desc()).limit(200)))
                for event in reversed(events):
                    trace = event.payload.get('public_guide', {}).get('experiment')
                    if trace and event.id not in seen:
                        print(json.dumps({'at': event.created_at, 'step': event.sequence, **trace}, ensure_ascii=False), flush=True)
                        seen.add(event.id)
            if not args.follow:
                break
            time.sleep(2)
    finally:
        engine.dispose()


if __name__ == '__main__':
    main()
