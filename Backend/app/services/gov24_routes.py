"""Outcome candidates from retained, consented Government24 navigation events.

No task graph is assumed. A route is an observed source screen and public option.
The same bounded snapshot serves live choice and the terminal monitor.
"""
from collections import defaultdict
from datetime import timezone
import time

from sqlalchemy import func, select
from app.models.runtime_event import RuntimeEvent
from app.repositories.users import UserRepository
from app.services.public_guide_policy import CIVIL, CIVIL_OPTIONS
from ai_agent.task_graph.success_selection import outcome_metrics, select_candidate

SCHEMA = 'gov24-navigation-event.v1'


def route_snapshot(factory, code_hash, *, now=None):
    now = int(time.time()) if now is None else now
    counts = defaultdict(lambda: dict(successes=0, failures=0, unknown=0))
    report = dict(sessions=0, incompatible=0, routes=[], events=[])
    with factory() as db:
        sessions = db.execute(select(RuntimeEvent.session_id, func.max(RuntimeEvent.created_at).label('at'))
            .where(RuntimeEvent.kind == 'public_guide', RuntimeEvent.expires_at > now)
            .group_by(RuntimeEvent.session_id).order_by(func.max(RuntimeEvent.created_at).desc(),
                RuntimeEvent.session_id).limit(100)).all()
        for session_id, _ in sessions:
            events = list(db.scalars(select(RuntimeEvent).where(RuntimeEvent.session_id == session_id,
                RuntimeEvent.kind == 'public_guide').order_by(RuntimeEvent.sequence).limit(26)))
            if not events or len(events) > 25:
                continue
            consent = UserRepository(db).latest_consent(events[0].user_id, 'model_training')
            if not consent or not consent.consented:
                continue
            granted = consent.recorded_at.replace(tzinfo=timezone.utc).timestamp()
            if any(e.expires_at <= now or e.created_at < granted for e in events):
                continue
            if [e.sequence for e in events] != list(range(1, len(events) + 1)):
                continue
            values = [e.payload.get('public_guide', {}) for e in events]
            if not all(v.get('goal_type') == CIVIL['goal_type'] for v in values):
                continue
            if not all(v.get('schema_version') == SCHEMA and v.get('code_hash') == code_hash
                       and v.get('policy_version') == CIVIL['version'] for v in values):
                report['incompatible'] += 1
                continue
            report['sessions'] += 1
            attempts = {}
            for event, value in zip(events, values):
                attempt = value.get('attempt')
                if isinstance(attempt, dict) and valid_attempt(attempt):
                    key = (attempt['source'], attempt['option'])
                    previous = attempts.get(attempt['id'])
                    if previous and previous[0] != key:
                        continue
                    if not previous or previous[1] == 'unknown':
                        attempts[attempt['id']] = (key, attempt['result'])
                # Only stable semantic fields enter operator output.
                entry = dict(id=event.id, at=event.created_at, page=value.get('page', 'unknown'),
                    status=value.get('reason', 'observed'))
                if value.get('selected_option') in CIVIL_OPTIONS:
                    entry['option'] = value['selected_option']
                if isinstance(attempt, dict) and valid_attempt(attempt):
                    entry['attempt'] = {k: attempt[k] for k in ('source', 'option', 'result')}
                report['events'].append(entry)
            for key, label in attempts.values():
                counts[key][label] += 1
    groups = defaultdict(list)
    for (source, option), values in sorted(counts.items()):
        row = dict(source=source, option=option, **outcome_metrics(**values))
        report['routes'].append(row)
        groups[source].append(dict(key=option, **row))
    for row in report['routes']:
        winner = select_candidate(groups[row['source']])
        row['leader'] = bool(winner and winner['key'] == row['option'])
    report['events'].sort(key=lambda e: (e['at'], e['id']))
    report['events'] = report['events'][-100:]
    return report


def valid_attempt(attempt):
    import re
    return (isinstance(attempt.get('id'), str) and re.fullmatch('[0-9a-f]{64}', attempt['id'])
        and isinstance(attempt.get('source'), str) and re.fullmatch('[0-9a-f]{64}', attempt['source'])
        and attempt.get('option') in CIVIL_OPTIONS
        and CIVIL_OPTIONS[attempt['option']]['group'] == 'navigation'
        and attempt.get('result') in {'successes', 'failures', 'unknown'})


def choose_route(factory, code_hash, source, options, now):
    rows = route_snapshot(factory, code_hash, now=now)['routes']
    return select_candidate([dict(key=r['option'], **r) for r in rows
                             if r['source'] == source and r['option'] in options])
