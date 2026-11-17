"""Create new, parameterized graph drafts from linked public-browsing traces.

This learner has no model, DB, user identifiers or release authority. Every edge
must already have an observed, correlated action and a changed public screen.
"""
import re
from copy import deepcopy

from ai_agent.graph_proposal.contracts import fingerprint

VERSION = 'browse-graph-learner.v1'
GRAPH_SCHEMA = 'browse-task-graph.v1'
GOALS = {
    'coupang.product.search': ('results', 'query_results_visible'),
    'coupang.product.details': ('detail', 'selected_product_detail_visible'),
}


class IncompleteBrowseTrace(ValueError):
    """A stable reason code, never a copy of screen or user text."""


def policy_hash(policy):
    # Matches the online event's existing policy identity.
    import json
    from hashlib import sha256
    return sha256(json.dumps(policy, sort_keys=True).encode()).hexdigest()


def _hash(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def _resource_allowed(kind, name, policy):
    key = {'search': 'search_resources', 'product': 'product_resources'}.get(kind)
    return bool(key and isinstance(name, str) and name in policy[key]
                and not any(token in name for token in policy['blocked_resource_tokens']))


def _identity(body):
    material = deepcopy(body)
    material['definition'].pop('task_id', None)
    return 'AI2-BROWSE-' + fingerprint(material)[:32]


def validate_browse_graph(body, policy):
    """Closed graph contract, separate from approved native-settings graphs."""
    if not isinstance(body, dict) or set(body) != {
            'schema_version', 'definition', 'states', 'transitions', 'completion', 'policy_version', 'policy_hash'}:
        raise ValueError('Invalid browse graph fields')
    definition = body['definition']
    if not isinstance(definition, dict) or set(definition) != {
            'task_id', 'goal_type', 'target_app', 'package', 'inputs', 'start_state', 'done_state'}:
        raise ValueError('Invalid browse graph definition')
    goal = definition['goal_type']
    if (body['schema_version'] != GRAPH_SCHEMA or goal not in GOALS or definition['target_app'] != 'coupang'
            or definition['package'] != policy['package'] or body['policy_version'] != policy['version']
            or body['policy_hash'] != policy_hash(policy)
            or definition['inputs'] != {'query': 'confirmed_request.query'}):
        raise ValueError('Unsupported browse graph contract')
    states, edges = body['states'], body['transitions']
    if not isinstance(states, dict) or not isinstance(edges, list) or not 1 <= len(edges) < policy['max_turns']:
        raise ValueError('Invalid browse graph size')
    expected_states = ['B' + str(i) for i in range(len(edges) + 1)]
    if (set(states) != set(expected_states) or definition['start_state'] != 'B0'
            or definition['done_state'] != expected_states[-1]):
        raise ValueError('Invalid browse graph topology')
    for key, state in states.items():
        if (not isinstance(state, dict) or set(state) != {'state_id', 'page'} or state['state_id'] != key
                or state['page'] not in {'search', 'results', 'detail'}):
            raise ValueError('Invalid browse graph state')
    for i, edge in enumerate(edges):
        if not isinstance(edge, dict) or set(edge) != {
                'transition_id', 'from_state', 'to_state', 'action_kind', 'target', 'manual_only', 'verification'}:
            raise ValueError('Invalid browse graph transition')
        target = edge['target']
        if (not isinstance(target, dict) or set(target) != {'resource_key', 'match', 'binding'}
                or not _resource_allowed(edge['action_kind'], target['resource_key'], policy)
                or target['match'] != 'normalized_browse_resource.v1'
                or target['binding'] != 'fresh_public_candidate'
                or edge['manual_only'] is not True or edge['verification'] != 'correlated_public_screen_change'
                or edge['transition_id'] != 'E' + str(i) or edge['from_state'] != expected_states[i]
                or edge['to_state'] != expected_states[i + 1]):
            raise ValueError('Invalid browse graph action')
        source, dest = states[expected_states[i]]['page'], states[expected_states[i + 1]]['page']
        if (edge['action_kind'] == 'product' and (source, dest) != ('results', 'detail')
                or edge['action_kind'] == 'search' and (source not in {'search', 'results'} or dest not in {'search', 'results'})):
            raise ValueError('Unsupported browse graph edge')
    page, predicate = GOALS[goal]
    if (states[expected_states[-1]]['page'] != page or body['completion'] != {
            'state_id': expected_states[-1], 'predicate': predicate, 'binding': 'current_confirmed_goal'}
            or definition['task_id'] != _identity(body)):
        raise ValueError('Invalid browse graph completion or identity')
    return body


def generate_browse_graph(records, policy):
    """Return an actual state/edge graph, never a transcript or outcome summary."""
    if not records or len(records) > policy['max_turns']:
        raise IncompleteBrowseTrace('trace_size')
    events = [r['evidence'] for r in records]
    goal = events[-1].get('goal_type')
    if goal not in GOALS or events[-1].get('outcome') != 'verified_navigation':
        raise IncompleteBrowseTrace('verified_navigation_required')
    sequences = [r['sequence'] for r in records]
    if (any(type(s) is not int or s < 1 for s in sequences)
            or sequences != list(range(1, len(sequences) + 1))
            or len({r['event_id'] for r in records}) != len(records)):
        raise IncompleteBrowseTrace('incomplete_event_sequence')
    code = events[0].get('code_hash')
    if not _hash(code):
        raise IncompleteBrowseTrace('missing_code_identity')
    for event in events:
        if (event.get('schema_version') != 'browse-event.v1' or event.get('goal_type') != goal
                or event.get('policy_hash') != policy_hash(policy) or event.get('policy_version') != policy['version']
                or event.get('code_hash') != code):
            raise IncompleteBrowseTrace('incompatible_trace')
        if event.get('outcome') not in {'in_progress', 'verified_navigation'}:
            raise IncompleteBrowseTrace('unverified_trace')
    observations = [(r, e) for r, e in zip(records, events) if e.get('observation_hash')]
    if not observations or len(observations) < 2:
        raise IncompleteBrowseTrace('linked_actions_required')
    first = observations[0][1]
    if not _hash(first['observation_hash']) or first.get('page') not in {'search', 'results'}:
        raise IncompleteBrowseTrace('unsupported_start_screen')
    current, pages, edges = first['observation_hash'], [first['page']], []
    for record, event in observations[1:]:
        observed = event['observation_hash']
        if not _hash(observed):
            raise IncompleteBrowseTrace('invalid_observation_identity')
        if observed == current:
            if event.get('page') != pages[-1]:
                raise IncompleteBrowseTrace('inconsistent_observation')
            continue  # Repeated observations are not additional actions.
        kind, resource = event.get('action_kind'), event.get('resource')
        if (event.get('action_correlated') is not True or event.get('before_hash') != current
                or not _resource_allowed(kind, resource, policy)):
            raise IncompleteBrowseTrace('uncorrelated_navigation')
        i = len(edges)
        edges.append({'transition_id': 'E' + str(i), 'from_state': 'B' + str(i), 'to_state': 'B' + str(i + 1),
            'action_kind': kind, 'target': {'resource_key': resource,
                'match': 'normalized_browse_resource.v1', 'binding': 'fresh_public_candidate'}, 'manual_only': True,
            'verification': 'correlated_public_screen_change'})
        pages.append(event.get('page'))
        current = observed
    if not edges or current != events[-1].get('observation_hash'):
        raise IncompleteBrowseTrace('linked_actions_required')
    done = 'B' + str(len(edges))
    body = {'schema_version': GRAPH_SCHEMA,
        'definition': {'goal_type': goal, 'target_app': 'coupang', 'package': policy['package'],
            'inputs': {'query': 'confirmed_request.query'}, 'start_state': 'B0', 'done_state': done},
        'states': {'B' + str(i): {'state_id': 'B' + str(i), 'page': page} for i, page in enumerate(pages)},
        'transitions': edges,
        'completion': {'state_id': done, 'predicate': GOALS[goal][1], 'binding': 'current_confirmed_goal'},
        'policy_version': policy['version'], 'policy_hash': policy_hash(policy)}
    body['definition']['task_id'] = _identity(body)
    try:
        return validate_browse_graph(body, policy)
    except ValueError as error:
        raise IncompleteBrowseTrace('unsupported_observed_route') from error
