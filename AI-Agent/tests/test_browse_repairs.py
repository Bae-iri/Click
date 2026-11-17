from copy import deepcopy
import pytest
from ai_agent.graph_proposal.proposals.browse_repairs import propose_repairs
from test_browse_graph_learning import trace, POLICY


def test_repeated_unchanged_action_produces_observation_advice_without_new_selectors():
    records = trace()[:1]
    event = records[0]['evidence']
    event.update(reason='repeated_screen', action_attempted=True, attempt_hash='a' * 64,
        attempted_action='search', attempted_resource='search_bar')
    duplicate = deepcopy(records[0])
    duplicate.update(event_id='duplicate', sequence=2)
    result = propose_repairs([records[0], duplicate], POLICY)
    assert result['advice'][0]['advice'] == 'reobserve_before_repeating'
    assert result['unconfirmed_routes'][0]['unconfirmed_attempts'] == 1
    assert not result['automatic_graph_release']
    recovered = trace()[-1]
    recovered.update(event_id='recovered', sequence=3)
    assert propose_repairs([records[0], duplicate, recovered], POLICY)['unconfirmed_routes'] == []


@pytest.mark.parametrize('problem', ['policy', 'sequence', 'goal', 'code'])
def test_incompatible_or_missing_evidence_cannot_create_repairs(problem):
    records = trace()
    if problem == 'sequence':
        records[-1]['sequence'] = 5
    else:
        records[-1]['evidence'][{'policy':'policy_hash','goal':'goal_type','code':'code_hash'}[problem]] = 'invalid'
    with pytest.raises(ValueError):
        propose_repairs(records, POLICY)
