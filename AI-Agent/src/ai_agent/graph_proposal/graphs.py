"""JSON graph checks/patching without importing or executing Agent 1."""
from copy import deepcopy
from ai_agent.task_graph.settings import CONTROLS, supported_predicates, CHOICE_PREDICATES, choice_spec, choice_predicate
from ai_agent.graph_proposal.contracts import canonical, fingerprint, identifier, finite_number


def graph_errors(body):
    errors = []
    if not isinstance(body, dict) or set(body) - {"definition", "states", "transitions", "synonyms", "policy"}:
        return ["invalid_graph_body"]
    definition, states, transitions = body.get("definition"), body.get("states"), body.get("transitions")
    if not isinstance(definition, dict) or not isinstance(states, dict) or not isinstance(transitions, list):
        return ["invalid_graph_collections"]
    try:
        for key in ("task_id", "goal_type", "target_app"):
            identifier(definition.get(key), key)
        canonical(body)
    except (TypeError, ValueError):
        return ["invalid_graph_identity_or_values"]
    starts, done = definition.get("start_states"), definition.get("done_state")
    if (not isinstance(starts, (list, tuple)) or not starts
            or any(not isinstance(s, str) or s not in states for s in starts)):
        return ["invalid_start_states"]
    if not isinstance(done, str) or done not in states:
        return ["missing_done_state"]
    for key, state in states.items():
        if not isinstance(state, dict) or state.get("state_id") != key:
            errors.append("state_id_mismatch")
        elif not finite_number(state.get("confidence_threshold")) or not 0 <= state["confidence_threshold"] <= 1:
            errors.append("invalid_state_threshold")
    ids, edges = set(), []
    for transition in transitions:
        if not isinstance(transition, dict):
            errors.append("invalid_transition")
            continue
        tid = transition.get("transition_id")
        if not isinstance(tid, str) or not tid or tid in ids:
            errors.append("invalid_transition_id")
        if isinstance(tid, str):
            ids.add(tid)
        source, target = transition.get("from_state"), transition.get("to_state")
        if not isinstance(source, str) or not isinstance(target, str) or source not in states or target not in states:
            errors.append("dangling_transition")
        else:
            edges.append((source, target))
        if source == done:
            errors.append("terminal_has_outgoing_edges")
        if transition.get("action_type") not in ("tap", "drag", "scroll", "observe_only"):
            errors.append("invalid_action_type")
        if transition.get("safety_level") not in ("LOW_RISK", "CONFIRM_REQUIRED", "BLOCKED"):
            errors.append("invalid_safety_level")
        predicate = transition.get("verification_predicate", "state_reached")
        if not isinstance(predicate, str) or predicate not in supported_predicates(
                definition.get('required_slots', {}), definition.get('task_type')):
            errors.append("unsupported_verification_predicate")
        if isinstance(predicate, str) and predicate in CHOICE_PREDICATES:
            slots = definition.get('required_slots', {})
            choice = choice_spec(slots)
            if (not choice or transition.get('action_type') != 'tap'
                    or transition.get('target_role') != 'menu_item' or transition.get('safety_level') != 'CONFIRM_REQUIRED'
                    or not transition.get('completes_task')
                    or transition.get('verification_predicate') != choice_predicate(slots)
                    or not isinstance(transition.get('target_keywords'), (list, tuple))
                    or not all(isinstance(word, str) for word in transition['target_keywords'])
                    or set(transition['target_keywords']) != set(choice['labels'])):
                errors.append('invalid_system_mode_verifier')
        if transition.get('verification_predicate') in ('target_checked_on', 'target_checked_off') and (
                transition.get('action_type') != 'tap' or transition.get('target_role') != 'toggle'
                or transition.get('safety_level') != 'CONFIRM_REQUIRED' or not transition.get('completes_task')
                or transition.get('verification_predicate') != 'target_checked_' + definition.get('required_slots', {}).get('state', '')):
            errors.append('invalid_wifi_verifier')
        if transition.get('verification_predicate') == 'manual_result_required' and (
                definition.get('task_type') != 'settings_assisted' or transition.get('action_type') != 'observe_only'
                or transition.get('requires_user_action', True) or transition.get('target_role') is not None
                or transition.get('safety_level') != 'LOW_RISK' or not transition.get('completes_task')
                or not states.get(source, {}).get('required_keyword_groups')):
            errors.append('invalid_manual_handoff')
        if definition.get('task_type') == 'settings_assisted' and (
                transition.get('action_type') not in {'tap', 'observe_only'}
                or transition.get('target_role') not in {None, 'menu_item', 'app_icon'}
                or (transition.get('completes_task') and transition.get('verification_predicate') != 'manual_result_required')):
            errors.append('invalid_assisted_action')
        if transition.get('verification_predicate') == 'settings_page_visible'  and (
                definition.get('task_type') != 'settings_navigation'
                or definition.get('required_slots', {}).get('action') != 'open'
                or transition.get('action_type') != 'observe_only'
                or transition.get('requires_user_action', True)
                or transition.get('safety_level') != 'LOW_RISK'
                or not transition.get('completes_task')
                or not states.get(source, {}).get('required_keyword_groups')):
            errors.append('invalid_navigation_completion')
        if definition.get('task_type') == 'settings_navigation' and (
                transition.get('action_type') not in {'tap', 'observe_only'}
                or transition.get('target_role') not in {None, 'menu_item', 'app_icon'}):
            errors.append('invalid_navigation_action')
        if transition.get("safety_level") == "CONFIRM_REQUIRED" and not transition.get("confirmation_message"):
            errors.append("missing_confirmation")
        if transition.get("completes_task") and transition.get("action_type") != "observe_only" and transition.get("safety_level") != "CONFIRM_REQUIRED":
            errors.append("unconfirmed_mutation")
        if transition.get("requires_user_action", True) and not transition.get("target_keywords"):
            errors.append("missing_target_keywords")
        if type(transition.get("priority", 0)) is not int:
            errors.append("invalid_priority")
    reachable = set(starts)
    for _ in states:
        reachable.update(target for source, target in edges if source in reachable)
    if done not in reachable:
        errors.append("unreachable_done_state")
    # Every declared start must have a route to done. Cycles for recovery are legal.
    reverse = {done}
    for _ in states:
        reverse.update(source for source, target in edges if target in reverse)
    if any(start not in reverse for start in starts):
        errors.append("start_cannot_finish")
    if not any(t.get("completes_task") for t in transitions if isinstance(t, dict)):
        errors.append("missing_completion_predicate")
    policy = body.get("policy", {})
    if not isinstance(policy, dict):
        errors.append("invalid_runtime_policy")
    else:
        for key in ("retry_limit", "settle_ms", "observation_timeout_ms", "recovery_limit", "max_failed_attempts"):
            if key in policy and (type(policy[key]) is not int or policy[key] <= 0):
                errors.append("invalid_runtime_policy")
        timeout, settle = policy.get("observation_timeout_ms", 5000), policy.get("settle_ms", 500)
        if type(timeout) is int and type(settle) is int and timeout < settle:
            errors.append("invalid_runtime_policy")
    return sorted(set(errors))


def apply_proposal(base, proposal):
    """Only reorder existing edges. Never synthesize actions, targets or safety rules."""
    if proposal.task_id != base["definition"]["task_id"]:
        raise ValueError("Proposal task mismatch")
    if proposal.evidence.get("base_graph_hash") != fingerprint(base):
        raise ValueError("Stale proposal base hash")
    if proposal.proposal_type != "transition_priority" or set(proposal.change) != {"priorities"}:
        raise ValueError("Unsupported proposal change")
    priorities = proposal.change["priorities"]
    if not isinstance(priorities, dict) or not priorities or any(type(v) is not int or abs(v) > 10000 for v in priorities.values()):
        raise ValueError("Invalid transition priorities")
    candidate = deepcopy(base)
    edges = {t["transition_id"]: t for t in candidate["transitions"]}
    if priorities.keys() - edges.keys() or len({edges[tid]["from_state"] for tid in priorities}) != 1:
        raise ValueError("Priority patch must address existing alternatives in one state")
    for tid, priority in priorities.items():
        edges[tid]["priority"] = priority
    if fingerprint(candidate) == fingerprint(base):
        raise ValueError("Empty proposal")
    errors = graph_errors(candidate)
    if errors:
        raise ValueError("Invalid candidate: " + ",".join(errors))
    return candidate
