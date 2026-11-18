"""Read-only live view of graphless Coupang learning and actual selections."""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import time

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND.parent / 'AI-Agent/src'))

from sqlalchemy import func, select
from app import models  # noqa: F401
from app.models.graph_proposal import BROWSE_GRAPH_CONSUMER
from app.models.runtime_event import RuntimeEvent, RuntimeEventConsumption
from app.repositories.users import UserRepository
from app.services import browse_policy as policy
from app.services.browse import CODE_HASH
from ai_agent.task_graph.success_selection import (
    MIN_ATTEMPTS, MIN_SUCCESS_RATE, outcome_metrics, select_candidate,
)


def snapshot(factory, *, now=None, limit=100):
    """Match online selection's latest-receipt window and evidence fences.

    Candidate ranking is hypothetical until the live screen uniquely binds it.
    Raw screen content, queries, account IDs and session IDs never leave here.
    """
    now = int(time.time()) if now is None else now
    if not 1 <= limit <= 100:
        raise ValueError('limit must be between 1 and 100')
    counts = defaultdict(lambda: dict(successes=0, failures=0, unknown=0))
    report = dict(sessions=0, pending=0, analyzed=0, drafts=0, incompatible=0,
                  routes=[], events=[], window=limit)
    expected_policy = policy.digest(json.dumps(policy.POLICY, sort_keys=True))
    with factory() as db:
        users = UserRepository(db)
        def eligible(event):
            consent = users.latest_consent(event.user_id, 'model_training')
            return bool(consent and consent.consented and event.created_at >=
                        consent.recorded_at.replace(tzinfo=timezone.utc).timestamp())

        latest = select(RuntimeEvent.session_id, func.max(RuntimeEvent.sequence).label('seq')).where(
            RuntimeEvent.kind == 'browse').group_by(RuntimeEvent.session_id).subquery()
        # Online choose() also caps this exact ordered receipt window at 100.
        receipts = db.execute(select(RuntimeEventConsumption, RuntimeEvent)
            .join(RuntimeEvent, RuntimeEventConsumption.event_id == RuntimeEvent.id)
            .join(latest, (latest.c.session_id == RuntimeEvent.session_id) &
                  (latest.c.seq == RuntimeEvent.sequence))
            .where(RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER,
                   RuntimeEventConsumption.expires_at > now, RuntimeEvent.expires_at > now)
            .order_by(RuntimeEvent.created_at.desc(), RuntimeEvent.id).limit(limit)).all()
        analyzed_ids = set()
        for receipt, event in receipts:
            if not eligible(event):
                continue
            analyzed_ids.add(event.id)
            report['analyzed'] += 1
            result = receipt.result
            outcomes = result.get('outcomes')
            if not isinstance(outcomes, dict):
                continue
            hashed = policy.digest(json.dumps(outcomes, sort_keys=True, ensure_ascii=False,
                separators=(',', ':'), allow_nan=False))
            if (result.get('outcomes_hash') != hashed or result.get('expires_at', 0) <= now
                    or outcomes.get('schema_version') != 'browse-outcomes.v1'
                    or outcomes.get('goal_type') not in policy.GOALS
                    or outcomes.get('policy_hash') != expected_policy
                    or outcomes.get('code_hash') != CODE_HASH):
                report['incompatible'] += 1
                continue
            report['drafts'] += int(result.get('status') == 'DRAFT_CREATED')
            for route in outcomes.get('routes', []):
                if not safe_route(route):
                    continue
                key = (outcomes['goal_type'], route['page'], route['action'], route['resource'])
                metrics = outcome_metrics(route['successes'], route['failures'], route['unknown'])
                for name in ('successes', 'failures', 'unknown'):
                    counts[key][name] += metrics[name]

        latest_events = db.scalars(select(RuntimeEvent).join(latest,
            (latest.c.session_id == RuntimeEvent.session_id) & (latest.c.seq == RuntimeEvent.sequence))
            .where(RuntimeEvent.expires_at > now)
            .order_by(RuntimeEvent.created_at.desc(), RuntimeEvent.id).limit(limit)).all()
        for event in latest_events:
            if eligible(event):
                report['sessions'] += 1
                report['pending'] += int(event.id not in analyzed_ids)

        events = db.scalars(select(RuntimeEvent).where(RuntimeEvent.kind == 'browse',
            RuntimeEvent.expires_at > now).order_by(RuntimeEvent.created_at.desc(),
                RuntimeEvent.session_id, RuntimeEvent.sequence.desc()).limit(limit)).all()
        for event in events:
            if not eligible(event):
                continue
            value = event.payload.get('browse', {})
            if (value.get('code_hash') != CODE_HASH or value.get('policy_hash') != expected_policy
                    or value.get('goal_type') not in policy.GOALS):
                continue
            # Explicit allowlists keep even malformed persisted strings out of terminal output.
            route = dict(page=value.get('page'), action=value.get('selected_action'),
                         resource=value.get('selected_resource'))
            selected = safe_route(route)
            reason = value.get('reason')
            status = ('AI2_SELECTED' if reason == 'ai2_selected' else
                      'VERIFIED' if reason == 'verified_navigation' else
                      'OTHER_GUIDE' if selected else 'OBSERVED')
            item = dict(id=event.id, at=event.created_at, goal=value['goal_type'], status=status,
                        page=value['page'] if value.get('page') in {'search', 'results', 'detail'} else 'unknown')
            if selected:
                item.update(action=route['action'], resource=route['resource'])
            metrics = value.get('selection_metrics')
            if status == 'AI2_SELECTED' and isinstance(metrics, dict):
                item['metrics'] = outcome_metrics(metrics['successes'], metrics['failures'], metrics['unknown'])
            report['events'].append(item)

    groups = defaultdict(list)
    for (goal, page, action, resource), values in sorted(counts.items()):
        metrics = outcome_metrics(**values)
        row = dict(goal=goal, page=page, action=action, resource=resource, **metrics)
        row['state'] = ('NEEDS_DATA' if metrics['attempts'] < MIN_ATTEMPTS else
                        'LOW_SUCCESS' if metrics['success_rate'] < MIN_SUCCESS_RATE else 'ELIGIBLE')
        report['routes'].append(row)
        groups[(goal, page)].append(dict(key=(action, resource), **metrics))
    for row in report['routes']:
        winner = select_candidate(groups[(row['goal'], row['page'])])
        if winner and winner['key'] == (row['action'], row['resource']):
            row['state'] = 'STAT_LEADER'
    return report


def safe_route(route):
    resources = dict(search='search_resources', product='product_resources', scroll='results_resources')
    action = route.get('action')
    return (route.get('page') in {'search', 'results'} and action in resources
            and route.get('resource') in policy.POLICY[resources[action]])


def render(report, previous=None):
    lines = [f'\n[{datetime.now().astimezone():%H:%M:%S}] AI2 쿠팡 탐색 | '
             f"최근 세션 {report['sessions']} / 분석 {report['analyzed']} / 대기 {report['pending']} / "
             f"그래프 초안 {report['drafts']} / 버전·무결성 제외 {report['incompatible']}"]
    old = {(r['goal'], r['page'], r['action'], r['resource']): r for r in (previous or {}).get('routes', [])}
    lines.append('목표 / 화면 / 행동 / 리소스 | 성공 실패 미확인 | 성공률 | Wilson 하한 | 상태 | 직전 대비')
    for row in report['routes']:
        key = tuple(row[k] for k in ('goal', 'page', 'action', 'resource'))
        before = old.get(key)
        change = f"{(row['success_rate'] - before['success_rate']) * 100:+.1f}pp" if before else '신규 집계'
        lines.append(f"{' / '.join(key)} | {row['successes']} {row['failures']} {row['unknown']} | "
                     f"{row['success_rate']:.1%} | {row['score']:.1%} | {row['state']} | {change}")
    if not report['routes']:
        lines.append('경로 통계 대기 중: 학습 동의, 같은 DB·서버 코드, 실제 행동 후 화면 관측을 확인하세요.')
    if set(old) - {tuple(r[k] for k in ('goal', 'page', 'action', 'resource')) for r in report['routes']}:
        lines.append('일부 후보가 집계 범위에서 제외됐습니다 (만료·철회·버전·최근 기록 범위 확인).')
    seen = {e['id'] for e in (previous or {}).get('events', [])}
    new = [e for e in report['events'] if e['id'] not in seen]
    for event in reversed(new[:10] if previous is None else new):
        stamp = datetime.fromtimestamp(event['at']).astimezone().strftime('%H:%M:%S')
        route = f" → {event['action']}/{event['resource']}" if 'action' in event else ''
        metrics = event.get('metrics')
        score = f" (선택 당시 {metrics['successes']}/{metrics['attempts']}, {metrics['success_rate']:.1%})" if metrics else ''
        lines.append(f"[{stamp}] {event['status']} {event['goal']} {event['page']}{route}{score}")
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, help='Same environment file as the tested backend')
    parser.add_argument('--target', choices=('coupang', 'gov24'), default='coupang')
    parser.add_argument('--interval', type=float, default=2, help='Polling seconds (default: 2)')
    parser.add_argument('--once', action='store_true')
    parser.add_argument('--json', action='store_true', help='JSON snapshot per change')
    args = parser.parse_args(argv)
    if not 0.5 <= args.interval <= 60:
        parser.error('--interval must be between 0.5 and 60 seconds')
    if args.env_file:
        if not args.env_file.is_file():
            parser.error('--env-file does not exist')
        from dotenv import load_dotenv
        load_dotenv(args.env_file, override=True)
    raw = os.getenv('DATABASE_URL')
    if not raw:
        parser.error('Provide DATABASE_URL or --env-file from the tested backend')
    # Match relative SQLite URLs to the backend working directory.
    os.chdir(BACKEND)
    from app.database import build_engine, build_session_factory
    from app.core.data_encryption import validate_database_keys
    from scripts.render_start import database_url
    engine = None
    try:
        from sqlalchemy.engine import make_url
        target = make_url(raw)
        if target.drivername.startswith('sqlite') and (
                not target.database or target.database == ':memory:' or not Path(target.database).is_file()):
            raise ValueError('An existing shared SQLite database is required')
        engine = build_engine(database_url(raw) if raw.startswith(('postgres://', 'postgresql://')) else raw)
        validate_database_keys(engine)
        factory = build_session_factory(engine)
        if args.target == 'gov24':
            from app.services.gov24_navigation import CODE_HASH as gov24_hash
            from app.services.gov24_routes import route_snapshot
            read_snapshot = lambda: route_snapshot(factory, gov24_hash)
            renderer = render_gov24
            if not args.json:
                print('AI2 정부24 주민등록등본 경로 모니터 (조회 전용, Ctrl+C 종료)\n'
                      '발급 안내 화면 도착까지 / 실제 탭 후 관측으로 경로 후보 생성\n'
                      '최근 100개 세션, 동의·만료·서버 코드 일치 필터 적용\n'
                      'STAT_LEADER=통계상 후보, ai2_selected=실제 선택. 단계 성공률은 발급 완료율과 다릅니다.\n'
                      '조회 시 새 관측을 바로 집계하며, 변경이 있을 때만 출력합니다.', flush=True)
        else:
            read_snapshot = lambda: snapshot(factory)
            renderer = render
        if not args.json and args.target == 'coupang':
            print('AI2 쿠팡 실시간 모니터 (조회 전용, Ctrl+C 종료)\n'
                  '성공률 = 성공 / (성공+실패+미확인), 최소 3회·70%\n'
                  'STAT_LEADER=통계상 우선 후보 (현재 화면 적용 보장 아님), AI2_SELECTED=실제 선택\n'
                  '최근 최대 100개 기록 범위. 직전 대비는 관측 통계 변화이며 개선 효과의 인과 증명은 아닙니다.\n'
                  f'조회 {args.interval:g}초 간격, 학습 주기 '
                  f"{os.getenv('CLICK_AI2_BROWSE_GRAPH_INTERVAL_SECONDS', '60')}초 "
                  f"(작업자 설정 {'ON' if os.getenv('CLICK_AI2_BROWSE_GRAPH_ENABLED', '1') == '1' else 'OFF'})",
                  flush=True)
        previous = None
        while True:
            report = read_snapshot()
            if report != previous:
                print(json.dumps(report, ensure_ascii=False) if args.json else renderer(report, previous), flush=True)
                previous = report
            if args.once:
                return 0
            time.sleep(args.interval)
    except KeyboardInterrupt:
        return 0
    except Exception as error:
        # Do not emit SQL parameters, payloads, encryption keys or connection URLs.
        print(f'Monitor unavailable ({type(error).__name__}); check the backend DB, keys and migrations.', file=sys.stderr)
        return 1
    finally:
        if engine is not None:
            engine.dispose()


def render_gov24(report, previous=None):
    lines = [f"\n[{datetime.now().astimezone():%H:%M:%S}] 정부24 | 학습 세션 {report['sessions']} / 버전 제외 {report['incompatible']}",
             '출발 화면 / 메뉴 | 성공 실패 미확인 | 성공률 | Wilson 하한 | 상태 | 직전 대비']
    lines.append(f"검증된 공개 작업 그래프 {len(report.get('graphs', []))}개")
    old = {(r['source'], r['option']): r for r in (previous or {}).get('routes', [])}
    for row in report['routes']:
        before = old.get((row['source'], row['option']))
        delta = f"{100 * (row['success_rate'] - before['success_rate']):+.1f}pp" if before else '새 후보'
        state = ('STAT_LEADER' if row['leader'] else 'NEEDS_DATA' if row['attempts'] < MIN_ATTEMPTS
                 else 'LOW_SUCCESS' if row['success_rate'] < MIN_SUCCESS_RATE else 'ELIGIBLE')
        lines.append(f"{row['source'][:8]} / {row['option']} | {row['successes']} {row['failures']} {row['unknown']} | "
                     f"{row['success_rate']:.1%} | {row['score']:.1%} | {state} | {delta}")
    if not report['routes']:
        lines.append('경로 후보 대기: 학습 동의 후 메뉴를 누르고 현재 화면 확인을 눌러주세요.')
    seen = {e['id'] for e in (previous or {}).get('events', [])}
    new = [e for e in report['events'] if e['id'] not in seen]
    for event in new[-10:] if previous is None else new:
        stamp = datetime.fromtimestamp(event['at']).astimezone().strftime('%H:%M:%S')
        attempt = event.get('attempt')
        observed = f" / 결과 {attempt['option']}={attempt['result']}" if attempt else ''
        if event.get('selection_policy') == 'ai2_gov24_task_graph':
            observed += ' / AI2 작업 그래프 선택'
        lines.append(f"[{stamp}] {event['status']} {event['page']} → {event.get('option', '-')}{observed}")
    return '\n'.join(lines)


if __name__ == '__main__':
    raise SystemExit(main())
