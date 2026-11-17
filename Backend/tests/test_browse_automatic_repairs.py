"""Observed failures -> background AI2 candidate revisions -> next Jev context."""
import json
from app.schemas.browse import BrowseScreen
from app.services.browse_route_hints import BrowseRouteHints
from app.repositories.users import UserRepository
from tests.test_browse_graph_learning import browse, controlled_graph_worker, consent  # noqa: F401
from tests.test_coupang_browse import prepare, screen, node


def fail(f):
    f.app.state.browse_service.selector.choose.side_effect = lambda *args: (
        None, {'called': True, 'status': 'abstained', 'provider_latency_ms': 1})
    return f.browse('start', goal_resolution_id=prepare(f), screen=screen(node('search_bar', clickable=True)))


def test_failed_live_request_automatically_proposes_idempotent_candidate_and_recovery_supersedes_it(browse):
    user, now = consent(browse)
    assert fail(browse)['outcome'] == 'unknown'
    learner = browse.app.state.browse_graph_learner
    reports = learner.list_improvements(now=now)
    assert len(reports) == 1
    assert reports[0]['advice'] == [{'page': 'search', 'advice': 'request_additional_observation', 'observations': 1}]
    assert reports[0]['automatic_candidate_revision'] and not reports[0]['automatic_graph_release']
    for private in (user, browse.session, '무선 이어폰', 'target_bounds'):
        assert private not in json.dumps(reports, ensure_ascii=False)
    assert learner.learn_session(browse.session, user, now=now)['status'] == 'UNCHANGED'
    assert learner.list_improvements(now=now) == reports
    view = BrowseScreen.model_validate(screen(node('search_bar', clickable=True)))
    hints = BrowseRouteHints(browse.app.state.session_factory).get(
        {'goal_type': 'coupang.product.search'}, view, [('search', view.nodes[0])], now)
    assert hints == [{'advice': 'request_additional_observation', 'supporting_traces': 1}]
    result = browse.browse('observe', screen=screen(node('search_results'),
        node('product_item', '무선 이어폰', True), query='무선 이어폰'))
    assert result['outcome'] == 'verified_navigation'
    assert learner.list_improvements(now=now) == []
    assert BrowseRouteHints(browse.app.state.session_factory).get(
        {'goal_type': 'coupang.product.search'}, view, [('search', view.nodes[0])], now) == []


def test_no_consent_or_withdrawal_or_expiry_never_supplies_repair_hints(browse):
    fail(browse)
    learner = browse.app.state.browse_graph_learner
    assert learner.list_improvements() == []
    # A fresh grant cannot retroactively consume the pre-grant prefix.
    user, now = consent(browse)
    assert learner.process(now=now)['repair_candidates'] == 0
    assert learner.list_improvements(now=now) == []


def test_unchanged_actual_tap_is_retained_as_uncertainty_not_failure(browse):
    user, now = consent(browse)
    before = screen(node('search_bar', clickable=True))
    guide = browse.browse('start', goal_resolution_id=prepare(browse), screen=before)
    browse.browse('observe', screen=screen(node('search_bar', clickable=True)), action={
        'guide_id': guide['guide_id'], 'observation_id': before['observation_id'],
        'node_id': guide['target_node_id'], 'occurred_at_ms': before['captured_at_ms']})
    learner = browse.app.state.browse_graph_learner
    assert learner.process(now=now)['repair_candidates'] == 1
    reports = learner.list_improvements(now=now)
    assert reports[0]['unconfirmed_routes'] == [{'page': 'search', 'action': 'search',
        'resource': 'search_bar', 'advice': 'reobserve_before_repeating', 'unconfirmed_attempts': 1}]
    assert learner.list_improvements(now=now + 8 * 86400) == []
    with browse.app.state.session_factory() as db:
        UserRepository(db).record_consent(user, 'model_training', False, 'withdraw')
    assert learner.list_improvements(now=now) == []
