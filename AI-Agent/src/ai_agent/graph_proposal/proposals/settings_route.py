"""Extract a Settings route from correlated actions, never from a proposed plan alone."""
from copy import deepcopy
from ai_agent.graph_proposal.contracts import fingerprint

VERSION = 'settings-route-learning.v1'


class IncompleteSettingsTrace(ValueError):
    pass


def generate_route(records, base):
    def require(condition, reason):
        if not condition:
            raise IncompleteSettingsTrace(reason)

    require(bool(records) and len(records) <= 256, 'missing_or_oversized_trace')
    traces = [r['trace'] for r in records]
    first = traces[0]
    require(first.get('first_observation') is True, 'missing_prefix')
    require([t.get('trace_turn') for t in traces] == list(range(1, len(traces)+1)), 'trace_gap')
    require(len({r['event_id'] for r in records}) == len(records), 'duplicate_event')
    require(all(t.get('arm') == 'llm' for t in traces), 'not_llm_evidence')
    keys = ('run_ref', 'goal_type', 'scope_hash', 'base_graph_hash', 'runtime_code_hash', 'jev_model')
    require(all(first.get(k) and all(t.get(k) == first[k] for t in traces) for k in keys), 'changed_context')
    require(first['goal_type'] == base['definition']['goal_type'] and
            first['base_graph_hash'] == fingerprint(base), 'base_graph_changed')
    # A validated Gemini plan or a reused AI2 route supplies the plan; either way only
    # correlated native actions below become route evidence.
    require(any(t.get('planner', {}).get('status') == 'SUCCESS' for t in traces)
            or any(t.get('plan_source') == 'learned_route' and t.get('route_hash') for t in traces),
            'no_validated_plan')
    require(not any(t.get('plan_source') == 'approved_graph_fallback' for t in traces), 'fallback_baseline')
    require(traces[-1]['verification']['final']['status'] == 'SUCCESS', 'final_effect_unverified')
    require(records[-1].get('runtime',{}).get('task_completed') is True, 'missing_native_completion')
    edges = {e['transition_id']: e for e in base['transitions']}
    current = first.get('observation', {}).get('state_id')
    require(current in base['states'], 'unknown_start')
    start, path, seen = current, [], set()
    for record,trace in zip(records,traces):
        require(not any(v.get('status') == 'BLOCKED' for v in trace['verification'].values()), 'blocked_trace')
        transition = trace.get('verified_transition')
        if not transition:
            # A new screen without a verified action leaves a gap, even if later steps work.
            observed = trace.get('observation', {}).get('state_id')
            require(observed in {None, current, 'S_DONE'} or trace['verification']['final']['status'] == 'SUCCESS',
                    'uncorrelated_navigation')
            continue
        attempt = trace.get('attempt_ref')
        require(bool(attempt), 'missing_attempt')
        runtime = record.get('runtime',{})
        require(runtime.get('verification_status') == 'SUCCESS' and runtime.get('count_attempt') is True
                and str(runtime.get('attempt_hash',''))[:16] == attempt
                and runtime.get('before_observation_hash') and runtime.get('after_observation_hash')
                and runtime['before_observation_hash'] != runtime['after_observation_hash'], 'missing_native_action')
        if attempt in seen:
            continue
        require(all(trace['verification'][level]['status'] == 'SUCCESS' for level in ('action', 'subgoal')),
                'unverified_transition')
        edge = edges.get(transition)
        require(edge is not None and edge['from_state'] == current, 'disconnected_path')
        require(edge['safety_level'] != 'BLOCKED', 'unsafe_transition')
        path.append(deepcopy(edge))
        seen.add(attempt)
        current = edge['to_state']
    require(bool(path) and path[-1]['completes_task'], 'missing_completion_action')
    require(len(path) <= 8 and len({e['transition_id'] for e in path}) == len(path), 'cyclic_or_long_route')
    states = {s for edge in path for s in (edge['from_state'], edge['to_state'])}
    return dict(schema_version=VERSION, definition=deepcopy(base['definition']),
        start_state=start, states={s: deepcopy(base['states'][s]) for s in sorted(states)}, transitions=path,
        compatibility={k:first[k] for k in ('scope_hash','base_graph_hash','runtime_code_hash','jev_model')},
        completion='native_effect_verified', independent_device_validation='NOT_ASSESSED')
