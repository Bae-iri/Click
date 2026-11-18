"""Monitor real retained browsing evidence, without fabricating live selections."""
from copy import deepcopy
import json

import pytest
from sqlalchemy import select
from app.models.runtime_event import RuntimeEventConsumption
from app.repositories.users import UserRepository
from app.services.browse import CODE_HASH
from app.services.browse_jev import JevBrowseSelector
from app.services.browse_route_hints import BrowseRouteHints
from scripts.agent2_monitor import render, snapshot
from tests.test_browse_graph_learning import browse, controlled_graph_worker, consent, complete  # noqa: F401
from tests.test_coupang_browse import BrowseFlow, node, prepare, screen


def trials(f, count=3):
    user, now = consent(f)
    complete(f)
    for _ in range(count - 1):
        complete(BrowseFlow(f.client, f.app, '쿠팡 상품 검색해줘'))
    return user, now


def test_counts_match_online_selection_and_repeated_reads_do_not_learn(browse):
    _, now = trials(browse)
    factory = browse.app.state.session_factory
    report = snapshot(factory, now=now)
    assert snapshot(factory, now=now) == report
    route, = report['routes']
    assert route['successes'] == route['attempts'] == 3
    assert route['state'] == 'STAT_LEADER'
    assert not any(e['status'] == 'AI2_SELECTED' for e in report['events'])
    from app.schemas.browse import BrowseScreen
    view = BrowseScreen.model_validate(screen(node('search_bar', clickable=True)))
    _, diagnostics = BrowseRouteHints(factory, code_hash=CODE_HASH).choose(
        {'goal_type': 'coupang.product.search'}, view, [('search', view.nodes[0])], now)
    for field, value in diagnostics['selection_metrics'].items():
        assert route[field] == value
    output = json.dumps(report, ensure_ascii=False) + render(report)
    assert '무선 이어폰' not in output
    assert browse.session not in output


def test_actual_ai2_choice_and_pending_analysis_are_visible(browse, monkeypatch):
    _, now = trials(browse)
    monkeypatch.setattr('app.services.browse_jev.time.time', lambda: now)
    factory = browse.app.state.session_factory
    browse.app.state.browse_service.selector = JevBrowseSelector(routes=BrowseRouteHints(factory, code_hash=CODE_HASH))
    f = BrowseFlow(browse.client, browse.app, '쿠팡 상품 검색해줘')
    f.browse('start', goal_resolution_id=prepare(f), screen=screen(node('search_bar', clickable=True)))
    report = snapshot(factory, now=now)
    selected = [e for e in report['events'] if e['status'] == 'AI2_SELECTED']
    assert len(selected) == 1
    assert selected[0]['resource'] == 'search_bar'
    assert selected[0]['metrics']['successes'] == 3
    assert report['pending'] == 1
    assert 'AI2_SELECTED' in render(report)


@pytest.mark.parametrize('problem', ['withdrawn', 'expired', 'tampered', 'old_code'])
def test_monitor_excludes_ineligible_evidence(browse, problem):
    user, now = trials(browse, 1)
    factory = browse.app.state.session_factory
    if problem == 'withdrawn':
        with factory() as db:
            UserRepository(db).record_consent(user, 'model_training', False, 'test')
    elif problem == 'expired':
        now += 8 * 86400
    else:
        from ai_agent.graph_proposal.contracts import fingerprint
        with factory() as db:
            receipt = db.scalar(select(RuntimeEventConsumption))
            result = deepcopy(receipt.result)
            if problem == 'tampered':
                result['outcomes']['routes'][0]['successes'] = 999
            else:
                result['outcomes']['code_hash'] = 'b' * 64
                result['outcomes_hash'] = fingerprint(result['outcomes'])
            receipt.result = result
            db.commit()
    report = snapshot(factory, now=now)
    assert report['routes'] == []
    if problem in {'withdrawn', 'expired'}:
        assert report['events'] == []
    else:
        assert report['incompatible'] == 1


def test_unknown_attempt_reduces_rate_and_render_shows_change(browse):
    _, now = trials(browse)
    factory = browse.app.state.session_factory
    before = snapshot(factory, now=now)
    f = BrowseFlow(browse.client, browse.app, '쿠팡 상품 검색해줘')
    view = screen(node('search_bar', clickable=True))
    guide = f.browse('start', goal_resolution_id=prepare(f), screen=view)
    f.browse('observe', screen=view, action={'guide_id': guide['guide_id'],
        'observation_id': view['observation_id'], 'node_id': guide['target_node_id'],
        'occurred_at_ms': view['captured_at_ms']})
    f.browse('cancel')
    after = snapshot(factory, now=now)
    route, = after['routes']
    assert route['successes'] == 3 and route['unknown'] == 1
    assert route['success_rate'] == .75
    assert '-25.0pp' in render(after, before)
    assert snapshot(factory, now=now) == after
