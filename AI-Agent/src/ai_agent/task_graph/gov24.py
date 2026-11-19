"""Government24 public graph schema and validation, shared without either agent."""
from copy import deepcopy
import re
from ai_agent.task_graph.serialization import fingerprint

VERSION = 'gov24-graph-learner.v1'
SCHEMA = 'gov24-task-graph.v1'
PAGES = {'public_menu', 'services', 'resident_service'}


def hashed(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) is not None


def identity(body):
    material = deepcopy(body)
    material['definition'].pop('task_id', None)
    return 'AI2-GOV24-' + fingerprint(material)[:32]


def contract(policy, goal):
    graph = policy.get('graphs', {}).get(goal)
    if graph:
        return graph['completion']['page'], graph['completion']['predicate'], {e['target_option'] for e in graph['transitions']}
    if goal == policy['goal_type']:
        return 'resident_service', 'resident_service_visible', {'nav_resident', 'nav_services', 'nav_frequent'}
    raise ValueError('unsupported_goal')


def validate_graph(body, policy, package):
    if not isinstance(body, dict) or set(body) != {
            'schema_version', 'definition', 'states', 'transitions', 'completion', 'policy_version', 'policy_hash', 'code_hash'}:
        raise ValueError('invalid_graph_fields')
    definition = body['definition']
    if not isinstance(definition, dict):
        raise ValueError('invalid_definition')
    goal = definition.get('goal_type')
    destination_page, predicate, allowed_options = contract(policy, goal)
    if (body['schema_version'] != SCHEMA or body['policy_version'] != policy['version']
            or body['policy_hash'] != fingerprint(policy) or not hashed(body['code_hash'])
            or not isinstance(definition, dict) or set(definition) != {
                'task_id', 'goal_type', 'target_app', 'package', 'start_state', 'done_state'}
            or definition['target_app'] != 'gov24'
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
                or state['state_id'] != name or state['page'] not in {'public_menu', 'services', destination_page} or not hashed(state['source_signature'])):
            raise ValueError('invalid_public_state')
    for i, edge in enumerate(edges):
        if not isinstance(edge, dict) or set(edge) != {
                'transition_id', 'from_state', 'to_state', 'option_id', 'binding', 'manual_only', 'verification'}:
            raise ValueError('invalid_edge_fields')
        option = policy['options'].get(edge['option_id'], {})
        if (option.get('group') != 'navigation' or edge['option_id'] not in allowed_options or edge['transition_id'] != 'E' + str(i)
                or edge['from_state'] != names[i] or edge['to_state'] != names[i + 1]
                or edge['binding'] != 'fresh_public_candidate' or edge['manual_only'] is not True
                or edge['verification'] != 'correlated_public_destination'):
            raise ValueError('invalid_public_action')
        source, destination = states[names[i]], states[names[i + 1]]
        if (source['page'] == destination_page or source['source_signature'] == destination['source_signature']
                or not (destination['page'] == destination_page or edge['option_id'] in {'nav_services', 'nav_frequent'}
                        and destination['page'] == 'services' and source['page'] != 'services')):
            raise ValueError('unverified_destination')
    if (states[names[-1]]['page'] != destination_page or body['completion'] != {
            'state_id': names[-1], 'predicate': predicate, 'scope': 'public_screen_only'}
            or definition['task_id'] != identity(body)):
        raise ValueError('invalid_completion')
    return body
