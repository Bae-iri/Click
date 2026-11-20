"""A/B route planning with a common Jev executor, native verifier and privacy boundary."""
from copy import deepcopy
from dataclasses import replace
from hashlib import sha256

from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.matcher import MATCH_THRESHOLD
from ai_agent.runtime.screen_privacy import assess_screen, minimize_nodes, finite_number
from ai_agent.runtime.targeting import eligible_nodes, selector_evidence
from ai_agent.runtime.verification import correlate_action, applied_value
from ai_agent.runtime.verification_conditions import summarize_legacy
from app.services.settings_planner import GOALS, VERSION, catalogue, navigation_key


class SettingsExperimentResolver:
    provider = 'jev'

    def __init__(self, inner, planner, learned_routes=()):
        self.inner, self.planner = inner, planner
        self.learned_routes = list(learned_routes or ())
        self.state, self.last_diagnostics = {}, {}
        self.turn = {}

    def reuse_enabled(self):
        from app.services.settings_graphs import reuse_enabled
        return reuse_enabled()

    def matching_route(self, graph, page, visible):
        """A validated AI2 route recorded under exactly this device scope, graph, code and Jev."""
        if not self.learned_routes or not self.state.get('scope_hash'):
            return None
        from app.clients.checkpoint import graph_version
        from app.clients.runtime_agent import runtime_configuration
        configuration = runtime_configuration()
        current = dict(scope_hash=self.state['scope_hash'], base_graph_hash=graph_version(graph),
                       runtime_code_hash=configuration['code_hash'], jev_model=configuration.get('model'))
        rejected = set(self.state.get('rejected_routes', []))
        for route in sorted(self.learned_routes, key=lambda r: (-r.get('support', 0), r['route_hash'])):
            steps = route.get('subgoals') or []
            if (route.get('goal_type') != graph.definition.goal_type or route['route_hash'] in rejected
                    or route.get('compatibility') != current or not steps or steps[-1] != 'adjust_setting'
                    or any(s != 'adjust_setting' and s not in self.state.get('catalogue_ids', ()) for s in steps)):
                continue
            # The current screen must be on the stored path; otherwise the route is stale here.
            if page == route.get('start_state') or navigation_key(page) in steps or 'adjust_setting' in visible:
                return route
        return None

    def delegate(self, kwargs):
        result = self.inner.resolve(**kwargs)
        self.last_diagnostics = self.inner.last_diagnostics
        self.turn['jev'] = decision_diagnostic(self.last_diagnostics)
        return result

    def prepare(self, previous, arm, episode_id):
        old = previous.settings_experiment if previous else {}
        self.state = deepcopy(old) if old and old.get('episode_id') == episode_id else {}
        self.arm = self.state.get('arm', arm if arm in {'graph', 'llm'} else 'graph')
        self.episode_id = episode_id
        self.turn = {'planner': {'called': False}, 'jev': {'called': False}}

    def resolve(self, **kwargs):
        graph, goal = kwargs['graph'], kwargs['goal']
        if graph.definition.goal_type not in GOALS:
            result = self.inner.resolve(**kwargs)
            self.last_diagnostics = self.inner.last_diagnostics
            return result
        self.state.setdefault('version', VERSION)
        self.state.setdefault('arm', self.arm)
        self.state.setdefault('goal_type', graph.definition.goal_type)
        self.state.setdefault('episode_id', self.episode_id)
        self.state.setdefault('plan', None)
        self.state.setdefault('completed', [])
        self.state.setdefault('skipped', [])
        self.state.setdefault('failures', [])
        self.state.setdefault('planner_calls', 0)
        # Older checkpoints counted provider outages as calls; revisions are validated plans.
        self.state.setdefault('planning_attempts', self.state.get('revision', 0))
        self.state.setdefault('provider_failures', 0)
        self.state.setdefault('planner_http_attempts', 0)
        self.state.setdefault('revision', 0)
        self.state.setdefault('failed_count', 0)
        self.state.setdefault('plan_source', None)
        self.state.setdefault('route_hash', None)
        self.state.setdefault('rejected_routes', [])
        if self.arm == 'graph':
            return self.delegate(kwargs)
        if self.state['plan_source'] == 'approved_graph_fallback':
            # Planner outage in reuse mode: the reviewed baseline graph serves the rest of the episode.
            self.turn['planner'] = {'called': False, 'status': 'SKIPPED', 'reason_code': 'approved_graph_fallback'}
            return self.delegate(kwargs)
        nodes, raw = kwargs['nodes'], kwargs.get('raw_nodes', ())
        setting = graph.definition.required_slots.get('setting', '')
        if raw:
            assessment = assess_screen(raw, setting)
            if assessment.status:
                self.last_diagnostics = {'provider': 'jev', 'called': False, 'mode': 'primary',
                    'status': assessment.status, 'observation_reason': assessment.reason}
                return None
        nodes = minimize_nodes(nodes, setting, raw_nodes=raw)
        if not nodes:
            self.last_diagnostics = {'provider': 'jev', 'called': False, 'mode': 'primary', 'status': 'empty_observation'}
            return None
        targets, bindings = catalogue(graph)
        self.state['catalogue'] = targets
        self.state['catalogue_ids'] = sorted(bindings)
        page = classify_screen_state(nodes, graph)
        by_edge = {e.transition_id: e for e in graph.transitions}
        visible = [key for key, edges in bindings.items() if any(
            selector_evidence(n, by_edge[e], nodes, graph)[0] >= MATCH_THRESHOLD
            for e in edges for n in eligible_nodes(by_edge[e], nodes, graph))]
        self.turn['observation'] = {'state_id': page, 'visible_subgoals': visible, 'node_count': len(nodes)}
        self.advance(kwargs.get('session'), kwargs.get('verification'), by_edge)
        if not self.state['plan'] or self.state.get('replan_requested', False):
            reuse = self.reuse_enabled()
            if self.state.get('replan_requested') and self.state['plan_source'] == 'learned_route':
                # Verified failures on a reused route: never offer it again in this episode,
                # and let AI2 quarantine it from the retained evidence.
                if self.state['route_hash'] not in self.state['rejected_routes']:
                    self.state['rejected_routes'].append(self.state['route_hash'])
            route = self.matching_route(graph, page, visible) if reuse else None
            if route:
                self.state.update(plan={'subgoals': [s for s in route['subgoals'] if s not in self.state['completed']]
                                        or ['adjust_setting']},
                                  plan_source='learned_route', route_hash=route['route_hash'], replan_requested=False)
                self.turn['planner'] = {'called': False, 'status': 'SKIPPED', 'reason_code': 'learned_route',
                                        'route_support': route.get('support')}
            elif self.state['planning_attempts'] >= 3:
                if reuse:
                    self.state.update(plan_source='approved_graph_fallback', replan_requested=False)
                    self.turn['planner'] = {'called': False, 'status': 'SKIPPED', 'reason_code': 'approved_graph_fallback'}
                    return self.delegate(kwargs)
                self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': 'provider_error'}
                self.turn['planner'] = {'called': False, 'status': 'BLOCKED', 'reason_code': 'planning_budget_exhausted'}
                return None
        if not self.state['plan'] or self.state.get('replan_requested', False):
            self.state['planner_calls'] += 1
            plan, diagnostic = self.planner.plan(goal=graph.definition.goal_type, targets=targets,
                current_page=graph.states[page].name if page in graph.states else 'unknown',
                visible_targets=visible, completed=self.state['completed'], failures=self.state['failures'])
            self.turn['planner'] = diagnostic
            self.state['planner_error_cause'] = diagnostic.get('provider_cause') if not plan else None
            self.state['planner_http_attempts'] += len(diagnostic.get('attempts', []))
            if diagnostic.get('failure_kind') == 'provider' or diagnostic.get('reason_code') in {
                    'provider_error', 'missing_api_key', 'invalid_or_unavailable_plan'}:
                self.state['provider_failures'] += 1
            else:
                self.state['planning_attempts'] += 1
            if not plan and reuse and diagnostic.get('failure_kind') == 'provider':
                # Reuse mode only: a Gemini outage must not block guidance. The switch is
                # explicit in the trace and such runs never become learned routes.
                self.state.update(plan_source='approved_graph_fallback', replan_requested=False)
                return self.delegate(kwargs)
            if not plan:
                self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': 'provider_error'}
                return None  # A/B mode never silently executes the baseline after planner failure.
            self.state['plan'], self.state['revision'] = plan, self.state['revision'] + 1
            self.state.update(replan_requested=False, plan_source='gemini', route_hash=None)
        steps = self.state['plan']['subgoals']
        # A native control or later page may already be open. Skip obsolete navigation;
        # do not count those skipped subgoals as observed successes.
        reached = 'adjust_setting' if 'adjust_setting' in visible else navigation_key(page) if navigation_key(page) in steps else None
        if reached in steps:
            for step in steps[:steps.index(reached)]:
                if step not in self.state['completed'] and step not in self.state['skipped']:
                    self.state['skipped'].append(step)
            if reached != 'adjust_setting' and reached not in self.state['completed']:
                self.state['completed'].append(reached)
        active = next((s for s in steps if s not in self.state['completed'] and s not in self.state['skipped']), None)
        self.state['active_subgoal'] = active
        if not active:
            self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': 'waiting'}
            return None
        restricted = replace(graph, transitions=tuple(e for e in graph.transitions if e.transition_id in bindings[active]))
        selected = self.inner.resolve(**{**kwargs, 'graph': restricted,
            'subgoal': {'id': active, 'goal_type': graph.definition.goal_type}})
        self.last_diagnostics = self.inner.last_diagnostics
        self.turn['jev'] = decision_diagnostic(self.last_diagnostics)
        self.turn['allowed_transitions'] = list(bindings[active])
        if selected and selected.transition_id not in bindings[active]:
            self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': 'invalid_choice'}
            return None
        return selected

    def advance(self, session, verification, by_edge):
        if not session or not verification:
            return
        edge = by_edge.get(session.pending_transition_id)
        if not edge:
            return
        key = 'adjust_setting' if edge.completes_task else navigation_key(edge.to_state)
        if verification.status.value == 'SUCCESS' and key not in self.state['completed']:
            self.state['completed'].append(key)
        elif verification.count_attempt and verification.status.value in {'NO_CHANGE', 'WRONG_STATE'}:
            event = (session.pending_attempt or {}).get('attempt_id')
            if event and event != self.state.get('last_failed_attempt'):
                self.state['last_failed_attempt'] = event
                self.state['failed_count'] += 1
                self.state['failures'] = (self.state['failures'] + [{'subgoal': key, 'status': verification.status.value}])[-6:]
                if self.state['failed_count'] % 2 == 0:
                    self.state['replan_requested'] = True


def decision_diagnostic(value):
    return {k: value[k] for k in ('called', 'model', 'status', 'confidence', 'provider_latency_ms',
        'candidate_count', 'transition_id', 'observation_reason', 'failure') if k in value}


def verdict(status, reason_code, condition_type):
    return dict(status=status, reason_code=reason_code, condition_type=condition_type,
        next_action={'SUCCESS':'advance','WAITING':'wait','UNKNOWN':'observe_or_ask_user',
                     'FAILED':'retry_selection','BLOCKED':'handoff_or_replan'}[status])


def trace(resolver, request, result, before, after, graph, elapsed_ms):
    """No raw screen content, source text, coordinates or native node IDs in evidence."""
    if graph is None or graph.definition.goal_type not in GOALS:
        return None
    state = resolver.state
    first_observation = not state.get('trace_turns')
    state['trace_turns'] = state.get('trace_turns', 0) + 1
    if not state.get('version'):
        # Privacy/settling may prevent resolver invocation. Still preserve the arm and log it.
        state.update(version=VERSION, arm=resolver.arm, episode_id=request.episode_id,
            goal_type=graph.definition.goal_type, plan=None, completed=[], skipped=[], planner_calls=0, revision=0)
    selection = result.debug.get('ui_selection', {})
    blocked = (selection.get('observation_reason') in {'protected_screen', 'credential_input', 'input_present'}
               or selection.get('status') == 'sensitive_screen')
    summary = summarize_legacy(result.verification_result, blocker='app_restriction' if blocked else None)
    subgoal = verdict(summary['status'], summary['reason_code'], summary.get('condition_type') or 'screen_entered')
    if result.verification_result is None and result.guide_command.requires_user_action:
        subgoal = verdict('WAITING', 'guidance_presented_awaiting_user', 'screen_entered')
    edge = next((e for e in graph.transitions if before and e.transition_id == before.pending_transition_id), None)
    attempt, _ = correlate_action(before, request, edge) if before and edge else (None, ())
    action = verdict('SUCCESS','correlated_native_action','action_performed') if attempt and attempt.get('event_key') else (
        verdict('WAITING','awaiting_user_action','action_performed') if before and before.pending_attempt and not request.user_action_event
        else verdict('UNKNOWN','no_correlated_action','action_performed'))
    if (before and before.pending_selection and request.user_action_event is None
            and not (attempt and attempt.get('event_key'))):
        action = verdict('WAITING','awaiting_user_action','action_performed')
    final = verdict('BLOCKED','private_boundary','task_completed') if blocked else verdict(
        'SUCCESS' if result.verification_result and result.verification_result.task_completed else 'UNKNOWN',
        'native_task_effect_verified' if result.verification_result and result.verification_result.task_completed else 'task_effect_not_verified',
        'task_completed')
    if final['status'] == 'SUCCESS' and 'adjust_setting' not in state['completed']:
        state['completed'].append('adjust_setting')
    if final['status'] == 'SUCCESS':
        state['active_subgoal'] = None
    actual = result.debug.get('ui_selection', {})
    jev = decision_diagnostic(actual)
    pending = before.pending_attempt if before else None
    snapshot = after.last_snapshot if after else None
    setting = graph.definition.required_slots['setting']
    effect = {'setting':setting, 'requested_direction':graph.definition.required_slots['direction'],
        'baseline_control_value':finite_number((pending or {}).get('baseline_value')),
        'current_control_value':finite_number(snapshot.slider_values.get(pending['target_node_id'])) if snapshot and pending else None,
        'baseline_system_value':finite_number((pending or {}).get('baseline_setting_value')),
        'current_system_value':finite_number(applied_value(snapshot.applied_settings,setting)) if snapshot else None,
        'range_min':finite_number((pending or {}).get('baseline_range_min')),
        'range_max':finite_number((pending or {}).get('baseline_range_max'))} if not blocked else None
    verified_transition = (edge.transition_id if edge and action['status'] == 'SUCCESS'
        and subgoal['status'] == 'SUCCESS' and result.verification_result
        and result.verification_result.count_attempt else None)
    from app.clients.checkpoint import graph_version
    from app.clients.runtime_agent import runtime_configuration
    from app.services.settings_planner import model_name
    configuration = runtime_configuration()
    return dict(version=VERSION, run_ref=sha256((request.session_id+str(request.episode_id)).encode()).hexdigest()[:16],
        trace_turn=state['trace_turns'], first_observation=first_observation,
        verified_transition=verified_transition, base_graph_hash=graph_version(graph),
        scope_hash=state.get('scope_hash'), runtime_code_hash=configuration['code_hash'],
        jev_model=configuration.get('model'), planner_model=model_name(),
        guide_ref=sha256(result.guide_command.guide_command_id.encode()).hexdigest()[:16],
        arm=state['arm'], goal_type=graph.definition.goal_type, base_task_id=graph.definition.task_id,
        plan=state['plan'], plan_source=state.get('plan_source'), route_hash=state.get('route_hash'),
        rejected_routes=list(state.get('rejected_routes', [])),
        active_subgoal=state.get('active_subgoal'), completed=list(state['completed']),
        subgoal_catalogue=state.get('catalogue',[]),
        skipped=list(state['skipped']), revision=state['revision'], planner_calls=state['planner_calls'],
        planning_attempts=state.get('planning_attempts', 0), provider_failures=state.get('provider_failures', 0),
        planner_http_attempts=state.get('planner_http_attempts', 0),
        planner=resolver.turn.get('planner', {'called':False}), jev=jev,
        observation=resolver.turn.get('observation', {'state_id':result.current_state_id}),
        verification={'action':action,'subgoal':subgoal,'final':final},
        native_effect=effect,
        attempt_ref=sha256(str(pending.get('attempt_id')).encode()).hexdigest()[:16] if pending else None,
        turn_latency_ms=elapsed_ms, total_failed_attempts=after.total_failed_attempts if after else 0,
        command_type=result.guide_command.command_type.value)
