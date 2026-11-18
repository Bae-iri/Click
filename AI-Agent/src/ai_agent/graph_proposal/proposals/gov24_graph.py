"""Build public Government24 task graphs only from correlated successful navigation."""
from copy import deepcopy
import re
from ai_agent.graph_proposal.contracts import fingerprint

VERSION = 'gov24-graph-learner.v1'
SCHEMA = 'gov24-task-graph.v1'
PAGES = {'public_menu', 'services', 'resident_service'}


class IncompleteGovernmentTrace(ValueError):
    pass


def hashed(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def identity(body):
    material = deepcopy(body)
    material['definition'].pop('task_id', None)
    return 'AI2-GOV24-' + fingerprint(material)[:32]


def validate_graph(body, policy, package):
    if not isinstance(body, dict) or set(body) != {
            'schema_version', 'definition', 'states', 'transitions', 'completion', 'policy_version', 'policy_hash', 'code_hash'}:
        raise ValueError('invalid_graph_fields')
    definition = body['definition']
    if (body['schema_version'] != SCHEMA or body['policy_version'] != policy['version']
            or body['policy_hash'] != fingerprint(policy) or not hashed(body['code_hash'])
            or not isinstance(definition, dict) or set(definition) != {
                'task_id', 'goal_type', 'target_app', 'package', 'start_state', 'done_state'}
            or definition['goal_type'] != policy['goal_type'] or definition['target_app'] != 'gov24'
            or definition['package'] != package):
        raise ValueError('unsupported_graph_contract')
    edges, states = body['transitions'], body['states']
    if not isinstance(edges, list) or not 1 <= len(edges) <= 24 or not isinstance(states, dict):
        raise ValueError('invalid_graph_size')
    names = ['G' + str(i) for i in range(len(edges) + 1)]
    if set(states) != set(names) or definition['start_state'] != names[0] or definition['done_state'] != names[-1]:
        raise ValueError('invalid_graph_topology')
    for name, state in states.items():
        if (not isinstance(state, dict) or set(state) != {'state_id', 'page', 'source_signature'}
                or state['state_id'] != name or state['page'] not in PAGES or not hashed(state['source_signature'])):
            raise ValueError('invalid_public_state')
    for i, edge in enumerate(edges):
        if not isinstance(edge, dict) or set(edge) != {
                'transition_id', 'from_state', 'to_state', 'option_id', 'binding', 'manual_only', 'verification'}:
            raise ValueError('invalid_edge_fields')
        option = policy['options'].get(edge['option_id'], {})
        if (option.get('group') != 'navigation' or edge['transition_id'] != 'E' + str(i)
                or edge['from_state'] != names[i] or edge['to_state'] != names[i + 1]
                or edge['binding'] != 'fresh_public_candidate' or edge['manual_only'] is not True
                or edge['verification'] != 'correlated_public_destination'):
            raise ValueError('invalid_public_action')
        source, destination = states[names[i]], states[names[i + 1]]
        if (source['page'] == 'resident_service' or source['source_signature'] == destination['source_signature']
                or not (destination['page'] == 'resident_service' or edge['option_id'] in {'nav_services', 'nav_frequent'}
                        and destination['page'] == 'services' and source['page'] != 'services')):
            raise ValueError('unverified_destination')
    if (states[names[-1]]['page'] != 'resident_service' or body['completion'] != {
            'state_id': names[-1], 'predicate': 'resident_service_visible', 'scope': 'public_screen_only'}
            or definition['task_id'] != identity(body)):
        raise ValueError('invalid_completion')
    return body


def generate_graph(records, policy, package):
    if not records or len(records) > 25:
        raise IncompleteGovernmentTrace('trace_size')
    events = [r['evidence'] for r in records]
    if ([r['sequence'] for r in records] != list(range(1, len(records) + 1))
            or len({r['event_id'] for r in records}) != len(records)):
        raise IncompleteGovernmentTrace('incomplete_event_sequence')
    code = events[0].get('code_hash')
    if not hashed(code) or any(e.get('schema_version') != 'gov24-navigation-event.v1'
            or e.get('policy_version') != policy['version'] or e.get('goal_type') != policy['goal_type']
            or e.get('code_hash') != code or e.get('outcome') not in {'in_progress', 'verified_navigation'} for e in events):
        raise IncompleteGovernmentTrace('incompatible_trace')
    if events[-1].get('outcome') != 'verified_navigation' or events[-1].get('page') != 'resident_service':
        raise IncompleteGovernmentTrace('verified_navigation_required')
    observations = [e for e in events if e.get('source')]
    if len(observations) < 2 or observations[0].get('page') not in {'public_menu', 'services'}:
        raise IncompleteGovernmentTrace('linked_actions_required')
    current = observations[0]
    states = {'G0': {'state_id': 'G0', 'page': current['page'], 'source_signature': current['source']}}
    edges, seen_attempts = [], set()
    for event in observations[1:]:
        if event['source'] == current['source']:
            if event.get('page') != current.get('page'):
                raise IncompleteGovernmentTrace('inconsistent_observation')
            continue
        attempt = event.get('attempt') or {}
        if (attempt.get('source') != current['source'] or attempt.get('result') != 'successes'
                or not hashed(attempt.get('id')) or attempt['id'] in seen_attempts):
            raise IncompleteGovernmentTrace('uncorrelated_navigation')
        seen_attempts.add(attempt['id'])
        i = len(edges)
        edges.append(dict(transition_id='E' + str(i), from_state='G' + str(i), to_state='G' + str(i + 1),
            option_id=attempt.get('option'), binding='fresh_public_candidate', manual_only=True,
            verification='correlated_public_destination'))
        name = 'G' + str(i + 1)
        states[name] = dict(state_id=name, page=event.get('page'), source_signature=event['source'])
        current = event
    done = 'G' + str(len(edges))
    body = dict(schema_version=SCHEMA, definition=dict(goal_type=policy['goal_type'], target_app='gov24',
        package=package, start_state='G0', done_state=done), states=states, transitions=edges,
        completion=dict(state_id=done, predicate='resident_service_visible', scope='public_screen_only'),
        policy_version=policy['version'], policy_hash=fingerprint(policy), code_hash=code)
    body['definition']['task_id'] = identity(body)
    try:
        return validate_graph(body, policy, package)
    except (ValueError, TypeError, KeyError) as error:
        raise IncompleteGovernmentTrace('unsupported_observed_route') from error
