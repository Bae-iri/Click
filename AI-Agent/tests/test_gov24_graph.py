"""Reject fabricated or disconnected Government24 navigation evidence."""
from copy import deepcopy
from pathlib import Path
import json
import pytest
from ai_agent.graph_proposal.proposals.gov24_graph import generate_graph, validate_graph, IncompleteGovernmentTrace

POLICY = json.loads((Path(__file__).parents[1] / 'goal_catalog/gov24_login_policy.json').read_text(encoding='utf-8'))


def trace():
    common = dict(schema_version='gov24-navigation-event.v1', policy_version=POLICY['civil_navigation']['version'],
        goal_type='gov24.resident_copy.open', code_hash='c' * 64)
    return [dict(event_id='one', sequence=1, evidence=dict(common, page='public_menu', source='a' * 64, outcome='in_progress')),
        dict(event_id='two', sequence=2, evidence=dict(common, page='resident_service', source='b' * 64,
            outcome='verified_navigation', attempt=dict(id='d' * 64, source='a' * 64, option='nav_resident', result='successes')))]


@pytest.mark.parametrize('problem', ['unlinked', 'unknown', 'wrong_source', 'missing_prefix', 'private', 'submission', 'changed_code'])
def test_requires_a_complete_public_action_trace(problem):
    records = trace()
    if problem == 'unlinked': records[1]['evidence'].pop('attempt')
    elif problem == 'unknown': records[1]['evidence']['attempt']['result'] = 'unknown'
    elif problem == 'wrong_source': records[1]['evidence']['attempt']['source'] = 'f' * 64
    elif problem == 'missing_prefix': records = records[1:]
    elif problem == 'private': records[1]['evidence']['outcome'] = 'handoff'
    elif problem == 'submission': records[1]['evidence']['attempt']['option'] = 'boundary_apply'
    else: records[1]['evidence']['code_hash'] = 'f' * 64
    with pytest.raises(IncompleteGovernmentTrace):
        generate_graph(records, POLICY['civil_navigation'], POLICY['package'])


def test_identity_is_deterministic_and_completion_cannot_be_changed_to_issuance():
    graph = generate_graph(trace(), POLICY['civil_navigation'], POLICY['package'])
    assert graph == generate_graph(trace(), POLICY['civil_navigation'], POLICY['package'])
    assert len(graph['transitions']) == 1
    forged = deepcopy(graph)
    forged['completion']['scope'] = 'application_submitted'
    with pytest.raises(ValueError):
        validate_graph(forged, POLICY['civil_navigation'], POLICY['package'])
