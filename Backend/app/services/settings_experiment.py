"""A/B route planning with a common Jev executor, native verifier and privacy boundary."""
from collections import Counter
from copy import deepcopy
from dataclasses import replace
from hashlib import sha256

from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.matcher import MATCH_THRESHOLD
from ai_agent.runtime.screen_privacy import assess_screen, minimize_nodes, finite_number
from ai_agent.runtime.targeting import eligible_nodes, selector_evidence, target_key
from ai_agent.runtime.task_success import technical_success
from ai_agent.runtime.verification import correlate_action, applied_value, navigation_reachable
from ai_agent.runtime.verification_conditions import summarize_legacy
from app.services.goal_jev import jev_minimum_confidence
from app.services.screen_jev import candidate_rejection
from app.services.settings_planner import GOALS, VERSION, catalogue, navigation_key, supports_graph, completion_key


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
        if self.arm == 'llm' and self.state and not previous.task_completed:
            # Old checkpoints could miss failures on turns that only observed the screen.
            self.sync_failed_count(previous.total_failed_attempts)

    def resolve(self, **kwargs):
        graph, goal = kwargs['graph'], kwargs['goal']
        if not supports_graph(graph):
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
        by_edge = {e.transition_id: e for e in graph.transitions}
        session, verified = kwargs.get('session'), kwargs.get('verification')
        if session and verified and verified.status.value == 'SUCCESS':
            edge = by_edge.get(session.pending_transition_id)
            if edge:
                key = completion_key(graph) if edge.completes_task else navigation_key(edge.to_state)
                if key not in self.state['completed']:
                    self.state['completed'].append(key)
        qwen_recovery = ((self.state.get('replan_requested') and getattr(self.planner, 'provider', None) == 'openrouter')
                         or (self.state['plan_source'] == 'qwen' and self.state['revision'] > 1))
        completed = self.state['completed']
        check_return = qwen_recovery or (self.state['plan_source'] == 'qwen' and bool(completed))
        page_evidence = {} if check_return else None
        page = classify_screen_state(nodes, graph, evidence=page_evidence)
        if check_return:
            try:
                minimum = jev_minimum_confidence()
            except ValueError:
                self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': 'not_configured'}
                return None

        def has_target(edge, strict):
            candidates = eligible_nodes(edge, nodes, graph)
            if strict:
                identities = Counter(target_key(n) for n in candidates
                    if candidate_rejection(edge, n, nodes, graph, kwargs.get('screen_bounds'),
                                           kwargs.get('excluded_target_keys'), minimum, require_identity=True) is None)
                return 1 in identities.values()
            return any(selector_evidence(n, edge, nodes, graph)[0] >= MATCH_THRESHOLD for n in candidates)

        def visible_targets(strict):
            return [key for key, edges in bindings.items() if any(has_target(by_edge[e], strict) for e in edges)]

        visible = visible_targets(qwen_recovery)
        if qwen_recovery and navigation_key(page) in visible:
            # A button leading to the page is not evidence that navigation has finished.
            if not any(c['state_id'] == page and c['title_match'] for c in page_evidence['candidates']):
                page = 'S0_UNKNOWN'
        revisits = set(self.state.get('pending_revisits', ()))
        return_menus = set()
        if check_return and any(key in completed and key != navigation_key(page) for key in visible):
            # A return to an earlier page makes its previously visited navigation path
            # necessary again. Require a native page title and a usable outgoing menu.
            native = [node for node in nodes if node.source_confidence is None]
            native_evidence = page_evidence
            if len(native) != len(nodes):
                native_evidence = {}
                classify_screen_state(native, graph, evidence=native_evidence)
            confirmed_page = native_evidence.get('state_id') == page and any(
                c['state_id'] == page and c['title_match'] for c in native_evidence['candidates'])
            if confirmed_page:
                visited_states = [state for state in graph.states if state != page and navigation_key(state) in completed]
                for edge in graph.transitions_from(page):
                    key = navigation_key(edge.to_state)
                    if (edge.to_state in visited_states and key in visible and edge.transition_id in bindings[key]
                            and has_target(edge, strict=True)):
                        return_menus.add(key)
                        revisits.add(key)
                        revisits.update(navigation_key(state) for state in visited_states
                                        if navigation_reachable(graph, edge.to_state, state))
        satisfied = [key for key in completed if key not in revisits]
        old_steps = (self.state['plan'] or {}).get('subgoals', [])
        if return_menus and self.state['plan_source'] == 'qwen' and return_menus.isdisjoint(old_steps):
            # A plan produced farther down the route may omit the menu needed to get back.
            # Keep it if the next step or the final control is available on this screen.
            if not qwen_recovery:
                visible = visible_targets(strict=True)
            current = navigation_key(page)
            remaining = old_steps[old_steps.index(current) + 1:] if current in old_steps else old_steps
            next_step = next((s for s in remaining if s in revisits or (s not in satisfied and s not in self.state['skipped'])), None)
            if next_step not in visible and completion_key(graph) not in visible:
                self.state['replan_requested'] = True
        self.turn['observation'] = {'state_id': page, 'visible_subgoals': visible, 'node_count': len(nodes)}
        if not self.state['plan'] or self.state.get('replan_requested', False):
            reuse = self.reuse_enabled()
            if self.state.get('replan_requested') and self.state['plan_source'] == 'learned_route':
                # Verified failures on a reused route: never offer it again in this episode,
                # and let AI2 quarantine it from the retained evidence.
                if self.state['route_hash'] not in self.state['rejected_routes']:
                    self.state['rejected_routes'].append(self.state['route_hash'])
            route = self.matching_route(graph, page, visible) if reuse else None
            if route:
                self.state.update(plan={'subgoals': [s for s in route['subgoals'] if s not in satisfied]
                                        or ['adjust_setting']},
                                  plan_source='learned_route', route_hash=route['route_hash'], replan_requested=False)
                self.turn['planner'] = {'called': False, 'status': 'SKIPPED', 'reason_code': 'learned_route',
                                        'route_support': route.get('support')}
            elif self.state['planning_attempts'] >= 3:
                if reuse:
                    self.state.update(plan_source='approved_graph_fallback', replan_requested=False)
                    self.turn['planner'] = {'called': False, 'status': 'SKIPPED', 'reason_code': 'approved_graph_fallback'}
                    return self.delegate(kwargs)
                self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': 'planning_budget_exhausted'}
                self.turn['planner'] = {'called': False, 'status': 'BLOCKED', 'reason_code': 'planning_budget_exhausted'}
                return None
        if not self.state['plan'] or self.state.get('replan_requested', False):
            plan, diagnostic = self.planner.plan(goal=graph.definition.goal_type, targets=targets,
                current_page=graph.states[page].name if page in graph.states else 'unknown',
                visible_targets=visible, completed=satisfied, failures=self.state['failures'],
                replan_from=navigation_key(page) if self.state.get('replan_requested') else None)
            self.turn['planner'] = diagnostic
            self.state['planner_error_cause'] = diagnostic.get('provider_cause') if not plan else None
            if diagnostic.get('reason_code') == 'no_visible_replan_target':
                self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': 'no_candidates'}
                return None
            self.state['planner_calls'] += 1
            self.state['planner_http_attempts'] += len(diagnostic.get('attempts', []))
            if diagnostic.get('failure_kind') == 'provider' or diagnostic.get('reason_code') in {
                    'provider_error', 'missing_api_key', 'invalid_or_unavailable_plan'}:
                self.state['provider_failures'] += 1
            else:
                self.state['planning_attempts'] += 1
            if not plan and reuse and diagnostic.get('failure_kind') == 'provider':
                # Reuse mode only: a planner outage must not block guidance. The switch is
                # explicit in the trace and such runs never become learned routes.
                self.state.update(plan_source='approved_graph_fallback', replan_requested=False)
                return self.delegate(kwargs)
            if not plan:
                status = 'provider_error'
                if diagnostic.get('failure_kind') == 'plan':
                    status = 'ungrounded_replan' if diagnostic.get('reason_code') == 'replan_start_not_visible' else 'invalid_plan'
                self.last_diagnostics = {'provider': 'jev', 'mode': 'primary', 'called': False, 'status': status}
                return None  # A/B mode never silently executes the baseline after planner failure.
            self.state['plan'], self.state['revision'] = plan, self.state['revision'] + 1
            source = 'qwen' if diagnostic.get('provider') == 'openrouter' else 'gemini'
            if source == 'qwen' and self.state.get('replan_requested'):
                # A step bypassed on the previous route can be necessary again now.
                self.state['skipped'] = [s for s in self.state['skipped'] if s not in plan['subgoals']]
            self.state.update(replan_requested=False, plan_source=source, planner_model=diagnostic.get('model'), route_hash=None)
        steps = self.state['plan']['subgoals']
        # A native control or later page may already be open. Skip obsolete navigation;
        # do not count those skipped subgoals as observed successes.
        terminal = completion_key(graph)
        reached = terminal if terminal in visible else navigation_key(page) if navigation_key(page) in steps else None
        if reached in steps:
            revisits.difference_update(steps[:steps.index(reached) + 1])
            for step in steps[:steps.index(reached)]:
                if step not in self.state['completed'] and step not in self.state['skipped']:
                    self.state['skipped'].append(step)
            if reached != terminal and reached not in self.state['completed']:
                self.state['completed'].append(reached)
        self.state['pending_revisits'] = [s for s in steps if s in revisits]
        active = next((s for s in steps if s in revisits or (s not in completed and s not in self.state['skipped'])), None)
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

    def sync_failed_count(self, total):
        previous = self.state.get('failed_count', 0)
        if total // 2 > previous // 2:
            self.state['replan_requested'] = True
        self.state['failed_count'] = total

    def record_failed_attempt(self, graph, edge, verification, total):
        if self.arm != 'llm' or not supports_graph(graph) or total <= self.state.get('failed_count', 0):
            return
        key = completion_key(graph) if edge.completes_task else navigation_key(edge.to_state)
        failure = {'subgoal': key, 'status': verification.status.value}
        self.state['failures'] = (self.state.get('failures', []) + [failure])[-6:]
        self.sync_failed_count(total)


def decision_diagnostic(value):
    return {k: value[k] for k in ('called', 'model', 'status', 'confidence', 'provider_latency_ms',
        'candidate_count', 'transition_id', 'observation_reason', 'failure') if k in value}


def verdict(status, reason_code, condition_type):
    return dict(status=status, reason_code=reason_code, condition_type=condition_type,
        next_action={'SUCCESS':'advance','WAITING':'wait','UNKNOWN':'observe_or_ask_user',
                     'FAILED':'retry_selection','BLOCKED':'handoff_or_replan'}[status])


def trace(resolver, request, result, before, after, graph, elapsed_ms):
    """No raw screen content, source text, coordinates or native node IDs in evidence."""
    if graph is None or not supports_graph(graph):
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
    if blocked:
        final = verdict('BLOCKED', 'private_boundary', 'task_completed')
    elif result.verification_result and result.verification_result.task_completed:
        final = verdict('SUCCESS', 'native_task_effect_verified', 'task_completed')
    elif (before and before.task_completed and after and after.task_completed
          and before.technical_outcome.get('episode_id') == request.episode_id
          and technical_success(before.technical_outcome, graph.definition.goal_type)):
        final = verdict('SUCCESS', 'previously_verified_native_outcome', 'task_completed')
    else:
        final = verdict('UNKNOWN', 'task_effect_not_verified', 'task_completed')
    terminal = completion_key(graph)
    if final['status'] == 'SUCCESS' and terminal not in state['completed']:
        state['completed'].append(terminal)
    if final['status'] == 'SUCCESS':
        state['active_subgoal'] = None
    actual = result.debug.get('ui_selection', {})
    jev = decision_diagnostic(actual)
    pending = before.pending_attempt if before else None
    snapshot = after.last_snapshot if after else None
    setting = graph.definition.required_slots['setting']
    effect = {'setting':setting, 'requested_direction':graph.definition.required_slots.get('direction'),
        'baseline_control_value':finite_number((pending or {}).get('baseline_value')),
        'current_control_value':finite_number(snapshot.slider_values.get(pending['target_node_id'])) if snapshot and pending else None,
        'baseline_system_value':finite_number((pending or {}).get('baseline_setting_value')),
        'current_system_value':finite_number(applied_value(snapshot.applied_settings,setting)) if snapshot else None,
        'range_min':finite_number((pending or {}).get('baseline_range_min')),
        'range_max':finite_number((pending or {}).get('baseline_range_max'))} if not blocked and graph.definition.goal_type in GOALS else None
    verified_transition = (edge.transition_id if edge and action['status'] == 'SUCCESS'
        and subgoal['status'] == 'SUCCESS' and result.verification_result
        and result.verification_result.count_attempt else None)
    from app.clients.checkpoint import graph_version
    from app.clients.runtime_agent import runtime_configuration
    from app.services.settings_planner import settings_planner_model
    configuration = runtime_configuration()
    # Older checkpoints only recorded the plan source. Keep their Gemini
    # provenance after a server switches to Qwen.
    planner_model = state.get('planner_model') or settings_planner_model('gemini' if state.get('plan_source') == 'gemini' else None)
    return dict(version=VERSION, run_ref=sha256((request.session_id+str(request.episode_id)).encode()).hexdigest()[:16],
        trace_turn=state['trace_turns'], first_observation=first_observation,
        verified_transition=verified_transition, base_graph_hash=graph_version(graph),
        scope_hash=state.get('scope_hash'), runtime_code_hash=configuration['code_hash'],
        jev_model=configuration.get('model'), planner_model=planner_model,
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
