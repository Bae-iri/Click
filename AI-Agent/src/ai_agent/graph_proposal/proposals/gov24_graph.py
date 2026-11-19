"""Offline Government24 proposals from correlated public navigation evidence."""
from ai_agent.task_graph.fingerprints import fingerprint
from ai_agent.task_graph.gov24 import VERSION, SCHEMA, PAGES, hashed, identity, contract, validate_graph


class IncompleteGovernmentTrace(ValueError):
    pass


def generate_graph(records, policy, package):
    if not records or len(records) > 25:
        raise IncompleteGovernmentTrace('trace_size')
    events = [r['evidence'] for r in records]
    if ([r['sequence'] for r in records] != list(range(1, len(records) + 1))
            or len({r['event_id'] for r in records}) != len(records)):
        raise IncompleteGovernmentTrace('incomplete_event_sequence')
    goal = events[-1].get('goal_type')
    try:
        destination_page, predicate, _ = contract(policy, goal)
    except ValueError as error:
        raise IncompleteGovernmentTrace('unsupported_goal') from error
    code = events[0].get('code_hash')
    if not hashed(code) or any(e.get('schema_version') != 'gov24-navigation-event.v1'
            or e.get('policy_version') != policy['version'] or e.get('goal_type') != goal
            or e.get('code_hash') != code or e.get('outcome') not in {'in_progress', 'verified_navigation'} for e in events):
        raise IncompleteGovernmentTrace('incompatible_trace')
    if events[-1].get('outcome') != 'verified_navigation' or events[-1].get('page') != destination_page:
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
    body = dict(schema_version=SCHEMA, definition=dict(goal_type=goal, target_app='gov24',
        package=package, start_state='G0', done_state=done), states=states, transitions=edges,
        completion=dict(state_id=done, predicate=predicate, scope='public_screen_only'),
        policy_version=policy['version'], policy_hash=fingerprint(policy), code_hash=code)
    body['definition']['task_id'] = identity(body)
    try:
        return validate_graph(body, policy, package)
    except (ValueError, TypeError, KeyError) as error:
        raise IncompleteGovernmentTrace('unsupported_observed_route') from error
