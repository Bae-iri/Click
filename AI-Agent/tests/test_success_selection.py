from copy import deepcopy
import pytest
from ai_agent.task_graph.success_selection import select_candidate, outcome_metrics
from ai_agent.graph_proposal.analysis.browse_outcomes import browse_outcomes
from ai_agent.graph_proposal.proposals.browse_graph import policy_hash


def test_selection_requires_evidence_and_is_not_one_lucky_success():
    assert select_candidate([{'key': 'new', 'successes': 1, 'failures': 0}]) is None
    winner = select_candidate([{'key': 'small', 'successes': 3, 'failures': 0},
                              {'key': 'proven', 'successes': 90, 'failures': 10}])
    assert winner['key'] == 'proven'
    assert winner['success_rate'] == .9


def test_failures_unknowns_and_ties_do_not_get_hidden():
    assert select_candidate([{'key': 'weak', 'successes': 2, 'failures': 1}]) is None
    assert select_candidate([{'key': 'unknown', 'successes': 3, 'failures': 0, 'unknown': 5}]) is None
    assert select_candidate([{'key': k, 'successes': 3, 'failures': 0} for k in ('a', 'b')]) is None
    with pytest.raises(ValueError):
        outcome_metrics(True, 0)


def trace():
    policy = {'version': 'test', 'max_turns': 16, 'search_resources': ['search_bar'],
              'product_resources': ['product_title'], 'results_resources': ['search_results']}
    event = {'schema_version': 'browse-event.v1', 'goal_type': 'coupang.product.search',
             'code_hash': 'a'*64, 'policy_version': 'test', 'policy_hash': policy_hash(policy),
             'action_attempted': True, 'attempt_hash': 'b'*64, 'attempted_action': 'search',
             'attempted_resource': 'search_bar', 'attempted_page': 'search',
             'page': 'search', 'action_correlated': False}
    return policy, [{'event_id': '1', 'sequence': 1, 'evidence': event}]


def test_repeated_attempt_is_counted_once_and_later_success_resolves_unknown():
    policy, records = trace()
    records.append({'event_id': '2', 'sequence': 2, 'evidence': deepcopy(records[0]['evidence'])})
    result = browse_outcomes(records, policy)
    assert result['routes'][0]['unknown'] == 1
    records.append({'event_id': '3', 'sequence': 3, 'evidence': {
        **records[0]['evidence'], 'action_correlated': True, 'page': 'results'}})
    result = browse_outcomes(records, policy)
    assert result['routes'][0]['successes'] == 1 and result['routes'][0]['unknown'] == 0


def test_observed_wrong_destination_counts_as_failure_and_unlinked_views_do_not_count():
    policy, records = trace()
    records[0]['evidence'].update(action_correlated=True, page='detail')
    assert browse_outcomes(records, policy)['routes'][0]['failures'] == 1
    records[0]['evidence']['action_attempted'] = False
    assert browse_outcomes(records, policy)['routes'] == []
