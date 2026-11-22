"""Production goal-resolution audit; synthetic account, no physical-screen success claims."""
import argparse
import json
import random
import secrets
import statistics
import time
from pathlib import Path
from uuid import uuid4

import httpx

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='https://click-db.onrender.com')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    goals = json.loads((ROOT / 'AI-Agent/goal_catalog/mvp_goals.json').read_text())['goals']
    schedule = []
    for goal in goals:
        name = goal['display_name']
        phrases = [next(iter(goal.get('utterances', [])), name + ' 해줘'),
                   name + ' 도와줘', name + ' 하고 싶어']
        for repeat, phrase in enumerate(phrases):
            schedule.append(dict(goal_type=goal['goal_type'], execution_status=goal['execution_status'],
                                 target_app=goal['target_app'], repeat=repeat, text=phrase))
    random.Random(20261122).shuffle(schedule)
    report = dict(schema_version='click.production-resolution-audit.v1', scope='api_goal_resolution_only',
                  physical_execution=False, heldout_phrasing=False, schedule_seed=20261122,
                  cases=[], account_deleted=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    with httpx.Client(base_url=args.base_url, timeout=30) as client:
        report['server_before'] = client.get('/version').raise_for_status().json()
        # Unrelated to the user's account and device credentials; credentials live only in memory.
        credentials = dict(name='요청해석 평가 임시계정', phone_number='0100000' + f'{secrets.randbelow(10000):04}',
                           password=f'{secrets.randbelow(10000):04}')
        response = client.post('/api/v1/auth/register', json=credentials)
        response.raise_for_status()
        tokens = response.json()
        user_id = tokens['user_id']
        client.headers['Authorization'] = 'Bearer ' + tokens['access_token']
        try:
            for item in schedule:
                row = dict(item, exact_goal=False)
                report['cases'].append(row)
                queued_id = str(uuid4())
                session = None
                try:
                    started = time.monotonic()
                    client.post('/api/v1/voice-requests/enqueue', json=dict(id=queued_id, raw_goal=item['text'], priority='NORMAL')).raise_for_status()
                    queue = client.post('/api/v1/voice-requests/next/claim', json={}).raise_for_status().json()
                    running = queue.get('request') or queue['running']
                    if running['id'] != queued_id:
                        raise RuntimeError('AUDIT_QUEUE_NOT_EMPTY')
                    session = running['agent_session_id']
                    request_id = str(uuid4())
                    body = dict(contract_version='agent-fe-contract.v0.3', request_id=request_id, session_id=session,
                                expected_version=0, command_type='resolve_goal', source_text=item['text'], preparation_version='goal-inputs.v1',
                                device_context=dict(platform='android', locale='ko-KR', capabilities=dict(
                                    coupang_browse=True, gov24_login=True, gov24_civil=True, gov24_services=True)),
                                execution_context=dict(protocol_version='execution-state.v1', request_id=request_id,
                                                       session_id=session, session_version=0, episode_id=None, observation_id=None))
                    sent = time.monotonic()
                    response = client.post('/api/v1/agent/commands', json=body)
                    row.update(response_ms=round((time.monotonic()-sent)*1000, 1),
                               input_to_resolution_ms=round((time.monotonic()-started)*1000, 1), http_status=response.status_code)
                    response.raise_for_status()
                    data = response.json()
                    prep = data.get('preparation') or {}
                    selected = (data.get('goal') or {}).get('goal_type') or (prep.get('public_guide_goal') or {}).get('goal_type')
                    row.update(selected_goal=selected, exact_goal=selected==item['goal_type'],
                               resolution_status=data.get('resolution_status'), preparation_status=prep.get('status'),
                               family=prep.get('family'), clarification_field=(prep.get('question') or {}).get('field'))
                except Exception as error:
                    row['error_type'] = type(error).__name__
                finally:
                    try:
                        client.post('/api/v1/voice-requests/'+queued_id+'/cancel', json=dict(reason='USER_ABORTED')).raise_for_status()
                    except Exception as error:
                        row['cleanup_error'] = type(error).__name__
                    save()
                if len(report['cases']) % 20 == 0:
                    print(json.dumps(dict(completed=len(report['cases']), exact=sum(c['exact_goal'] for c in report['cases'])), ensure_ascii=False), flush=True)
            report['server_after'] = client.get('/version').raise_for_status().json()
            report['source_stable'] = report['server_before']['source_revision'] == report['server_after']['source_revision']
        finally:
            deletion = client.delete('/api/v1/users/'+user_id)
            report['account_deleted'] = deletion.status_code == 204
            save()
    print(json.dumps(dict(completed=len(report['cases']), account_deleted=report['account_deleted'])), flush=True)


if __name__ == '__main__':
    main()
