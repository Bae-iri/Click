"""Real browse evidence reaches the online selector without developer approval."""
from copy import deepcopy
from unittest.mock import Mock
import pytest
from sqlalchemy import select
from app.models.runtime_event import RuntimeEventConsumption
from app.models.graph_proposal import BROWSE_GRAPH_CONSUMER
from app.repositories.users import UserRepository
from app.schemas.browse import BrowseScreen
from app.services.browse import CODE_HASH
from app.services.browse_route_hints import BrowseRouteHints
from app.services.browse_jev import JevBrowseSelector
from tests.test_browse_graph_learning import browse, controlled_graph_worker, consent, complete  # noqa: F401
from tests.test_coupang_browse import BrowseFlow, screen, node, prepare


def evidence(f):
    user, now = consent(f)
    complete(f)
    for _ in range(2):
        complete(BrowseFlow(f.client, f.app, '쿠팡 상품 검색해줘'))
    return user, now


def selection(f, now, *, code_hash=CODE_HASH):
    view = BrowseScreen.model_validate(screen(node('search_bar', clickable=True)))
    store = BrowseRouteHints(f.app.state.session_factory, code_hash=code_hash)
    return store.choose({'goal_type': 'coupang.product.search', 'query': '생수'},
                        view, [('search', view.nodes[0])], now)


def test_three_successes_select_without_jev_key_or_approval(browse, monkeypatch):
    _, now = evidence(browse)
    selected, diagnostics = selection(browse, now)
    assert selected[0] == 'search'
    assert diagnostics['selection_metrics']['successes'] == 3
    assert diagnostics['selection_metrics']['success_rate'] == 1
    monkeypatch.delenv('TYPESAFE_API_KEY', raising=False)
    monkeypatch.setattr('app.services.browse_jev.time.time', lambda: now)
    sender = Mock(side_effect=AssertionError('Agent2 winner must not call Jev'))
    view = BrowseScreen.model_validate(screen(node('search_bar', clickable=True)))
    chosen, report = JevBrowseSelector(sender, BrowseRouteHints(browse.app.state.session_factory)).choose(
        {'goal_type': 'coupang.product.search', 'query': '생수'}, view, [('search', view.nodes[0])], [])
    assert chosen is not None and report['status'] == 'ai2_selected' and not report['called']
    sender.assert_not_called()


@pytest.mark.parametrize('problem', ['withdrawn', 'expired', 'tampered', 'different_code'])
def test_invalid_or_incompatible_evidence_cannot_select(browse, problem):
    user, now = evidence(browse)
    if problem == 'withdrawn':
        with browse.app.state.session_factory() as db:
            UserRepository(db).record_consent(user, 'model_training', False, 'test')
    elif problem == 'expired':
        now += 8 * 86400
    elif problem == 'tampered':
        with browse.app.state.session_factory() as db:
            for receipt in db.scalars(select(RuntimeEventConsumption).where(
                    RuntimeEventConsumption.consumer == BROWSE_GRAPH_CONSUMER)):
                result = deepcopy(receipt.result)
                result['outcomes']['routes'][0]['successes'] = 999
                receipt.result = result
            db.commit()
    selected, _ = selection(browse, now, code_hash='b'*64 if problem == 'different_code' else CODE_HASH)
    assert selected is None


def test_ambiguous_live_controls_are_not_arbitrarily_bound(browse):
    _, now = evidence(browse)
    view = BrowseScreen.model_validate(screen(node('search_bar', clickable=True)))
    first = view.nodes[0]
    second = first.model_copy(update={'node_id': 'another'})
    selected, _ = BrowseRouteHints(browse.app.state.session_factory).choose(
        {'goal_type': 'coupang.product.search'}, view, [('search', first), ('search', second)], now)
    assert selected is None


@pytest.mark.parametrize('destination', ['wrong_page', 'unchanged'])
def test_failed_or_unresolved_attempts_reduce_live_success_rate(browse, destination):
    _, now = evidence(browse)
    for _ in range(2):
        f = BrowseFlow(browse.client, browse.app, '쿠팡 상품 검색해줘')
        before = screen(node('search_bar', clickable=True))
        guide = f.browse('start', goal_resolution_id=prepare(f), screen=before)
        after = (screen(node('product_detail'), node('product_detail_title', '다른 상품'))
                 if destination == 'wrong_page' else before)
        f.browse('observe', screen=after, action={'guide_id': guide['guide_id'],
            'observation_id': before['observation_id'], 'node_id': guide['target_node_id'],
            'occurred_at_ms': before['captured_at_ms']})
        f.browse('cancel')  # End the unfinished request before starting the next trial.
    selected, _ = selection(browse, now)
    assert selected is None  # 3/5, rather than success-only 3/3.
