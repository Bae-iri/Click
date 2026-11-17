"""Synthetic captured-action traces test draft generation, not device quality."""
from copy import deepcopy
import json
from pathlib import Path

import pytest
from ai_agent.graph_proposal.contracts import fingerprint
from ai_agent.graph_proposal.proposals.browse_graph import (
    IncompleteBrowseTrace, generate_browse_graph, policy_hash, validate_browse_graph,
)

POLICY = json.loads((Path(__file__).parents[1] / 'goal_catalog/coupang_browse_policy.json').read_text(encoding='utf-8'))


def test_browse_assessment_distinguishes_missing_candidates_from_jev_abstention():
    from ai_agent.graph_proposal.analysis.browse import assess_sessions
    base = {'schema_version': 'browse-event.v1', 'page': 'search', 'outcome': 'unknown'}
    result = assess_sessions([[{**base, 'reason': 'no_candidates'},
        {**base, 'reason': 'abstained', 'called': True, 'provider_latency_ms': 12,
         'route_hint_count': 1}]])
    assert result['route_blockers'] == {'no_candidates': 1, 'abstained': 1}
    assert result['ai2_hint_steps'] == 1 and result['jev_calls'] == 1
    assert result['outcomes'] == {'unknown': 1}


def trace(details=False):
    goal = 'coupang.product.details' if details else 'coupang.product.search'
    pages = ['results', 'detail'] if details else ['search', 'results']
    common = {'schema_version': 'browse-event.v1', 'goal_type': goal,
        'policy_version': POLICY['version'], 'policy_hash': policy_hash(POLICY), 'code_hash': 'c' * 64}
    return [{'event_id': 'event-' + str(i), 'sequence': i + 1,
        'evidence': {**common, 'page': page, 'observation_hash': fingerprint(page),
            'outcome': 'verified_navigation' if i else 'in_progress',
            **({'action_correlated': True, 'before_hash': fingerprint(pages[0]),
                'action_kind': 'product' if details else 'search',
                'resource': 'product_title' if details else 'search_bar'} if i else {})}}
        for i, page in enumerate(pages)]


@pytest.mark.parametrize('details', [False, True])
def test_builds_a_new_parameterized_graph_with_actual_states_targets_and_completion(details):
    records = trace(details)
    graph = generate_browse_graph(records, POLICY)
    assert validate_browse_graph(graph, POLICY) == graph
    assert graph['definition']['inputs'] == {'query': 'confirmed_request.query'}
    assert len(graph['states']) == 2 and len(graph['transitions']) == 1
    edge = graph['transitions'][0]
    assert edge['from_state'] == 'B0' and edge['to_state'] == 'B1'
    assert edge['manual_only'] is True
    assert graph['completion']['predicate'] == (
        'selected_product_detail_visible' if details else 'query_results_visible')
    assert generate_browse_graph(deepcopy(records), POLICY) == graph
    assert not any(s in json.dumps(graph) for s in ['event-', 'observation_hash', 'code_hash', 'before_hash'])


def test_produces_multiple_edges_from_a_linked_search_then_product_trace():
    records = trace()
    for r in records:
        r['evidence']['goal_type'] = 'coupang.product.details'
        r['evidence']['outcome'] = 'in_progress'
    last = trace(True)[-1]
    last['sequence'], last['event_id'] = 3, 'event-2'
    records.append(last)
    body = generate_browse_graph(records, POLICY)
    assert [e['action_kind'] for e in body['transitions']] == ['search', 'product']
    assert body['definition']['done_state'] == 'B2'


@pytest.mark.parametrize('change', [
    {'outcome': 'user_reported'}, {'outcome': 'blocked'}, {'outcome': 'unknown'},
    {'action_correlated': False}, {'before_hash': '0' * 64}, {'resource': 'buy_now'},
    {'action_kind': 'scroll', 'resource': 'product_list'}, {'policy_hash': '0' * 64},
    {'code_hash': 'd' * 64}, {'goal_type': 'coupang.order.checkout'}, {'page': 'unknown'},
])
def test_incomplete_or_sensitive_evidence_cannot_become_a_graph(change):
    records = trace()
    records[-1]['evidence'].update(change)
    with pytest.raises(IncompleteBrowseTrace):
        generate_browse_graph(records, POLICY)


@pytest.mark.parametrize('sequences', [[2, 3], [1, 3], [1, 1], [True, 2]])
def test_missing_duplicate_and_invalid_sequence_is_not_a_new_path(sequences):
    records = trace()
    for r, sequence in zip(records, sequences):
        r['sequence'] = sequence
    with pytest.raises(IncompleteBrowseTrace):
        generate_browse_graph(records, POLICY)


def test_wait_observations_do_not_become_edges_and_no_label_is_copied():
    records = trace()
    wait = deepcopy(records[0])
    wait.update(event_id='wait', sequence=2)
    wait['evidence']['label'] = 'private query or product name'
    records[-1]['sequence'] = 3
    result = generate_browse_graph([records[0], wait, records[-1]], POLICY)
    assert len(result['transitions']) == 1
    assert 'private' not in json.dumps(result)


def test_tampered_graph_or_action_cannot_gain_execution_or_completion_authority():
    body = generate_browse_graph(trace(), POLICY)
    for change in ('automatic', 'target', 'terminal', 'edge'):
        altered = deepcopy(body)
        if change == 'automatic':
            altered['transitions'][0]['manual_only'] = False
        elif change == 'target':
            altered['transitions'][0]['target']['resource_key'] = 'checkout'
        elif change == 'terminal':
            altered['completion']['predicate'] = 'payment_success'
        else:
            altered['transitions'][0]['to_state'] = 'B0'
        with pytest.raises(ValueError):
            validate_browse_graph(altered, POLICY)
