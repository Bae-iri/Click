"""Bound graph replacement without losing failure evidence or authorizations fences."""
from copy import deepcopy
from uuid import uuid4
from app.repositories.task_graphs import NoCompatibleGraphError, AmbiguousGraphError
from app.schemas.agent_turn import TypedGoalRequest

MAX_RESELECTIONS = 2
MAX_FAILED_ATTEMPTS = 12


def prepare(recovery, checkpoint, graph, reference, response, graphs):
    recovery = deepcopy(recovery or {})
    count = int((checkpoint or {}).get("total_failed_attempts", 0))
    delta = max(0, count - recovery.get("current_graph_failures", 0))
    recovery["failed_attempts"] = recovery.get("failed_attempts", 0) + delta
    recovery["current_graph_failures"] = count
    handoff = response.guide_command.render_policy.get("backend_handoff") or {}
    if handoff.get("type") != "task_graph_reselection_required":
        return {"recovery": recovery}
    attempts = recovery.get("reselection_count", 0)
    excluded = list(dict.fromkeys([*recovery.get("excluded_graph_hashes", []), reference["sha256"]]))
    recovery["excluded_graph_hashes"] = excluded
    stopped = "reselection_budget_exhausted" if attempts >= MAX_RESELECTIONS else (
        "failure_budget_exhausted" if recovery["failed_attempts"] >= MAX_FAILED_ATTEMPTS else None)
    selected = None
    if not stopped:
        try:
            goal = TypedGoalRequest(**{key: checkpoint["typed_goal"][key] for key in ("goal_type", "target_app", "slots")},
                                    catalog_version="2.1.0")
            selected = graphs.select(goal, excluded_hashes=excluded)
        except NoCompatibleGraphError:
            stopped = "no_compatible_alternative"
        except AmbiguousGraphError:
            stopped = "ambiguous_alternative"
        except ValueError:
            stopped = "invalid_alternative"
    if stopped:
        response.task_status = "IN_PROGRESS"
        guide = response.guide_command
        guide.command_type, guide.action_type, guide.safety_level = "highlight", "observe_only", "LOW_RISK"
        guide.target_node_id = guide.target_label = guide.target_bounds = None
        guide.requires_user_action = False
        guide.message = "다른 작업 경로를 확인하지 못했어요. 직접 메뉴로 이동하거나 화면을 다시 확인해 주세요."
        policy = {"schema_version": "ai1-recovery.v1", "decision": "handoff",
            "reason_code": "GRAPH_PATH_UNAVAILABLE", "next_action": "reselect_graph", "waiting_for": "user",
            "recoverable": True, "attempts": attempts, "retry_after_ms": 0,
            "options": ["retry", "manual_navigation", "cancel"]}
        guide.render_policy = {**guide.render_policy, "recovery": policy}
        response.metadata["recovery"] = policy
        response.metadata["reselection"] = {"status": "stopped", "reason_code": stopped,
            "count": attempts, "failed_attempts": recovery["failed_attempts"]}
        return {"recovery": recovery}
    replacement, new_reference = selected
    old_transitions = {item.transition_id: item.to_dict() for item in graph.transitions}
    common = {item.transition_id for item in replacement.transitions
              if old_transitions.get(item.transition_id) == item.to_dict()}
    fresh = deepcopy(checkpoint)
    fresh.update(task_id=replacement.definition.task_id, current_state_id="S0_UNKNOWN",
        pending_transition_id=None, pending_confirmation_transition_id=None, pending_attempt=None, pending_selection=None,
        last_guide_command_id=None, last_target_node_id=None, last_snapshot=None, last_user_action_event=None,
        recovery_count=0, recovery_state={}, vision_state={}, state_candidates=(), total_failed_attempts=0, task_completed=False,
        observed_path=(), progress_steps=(), technical_outcome={},
        failed_target_keys={key: value for key, value in checkpoint.get("failed_target_keys", {}).items() if key in common},
        transition_attempts={key: value for key, value in checkpoint.get("transition_attempts", {}).items()
                             if key.split(":", 1)[0] in common})
    fresh["typed_goal"].update(task_id=replacement.definition.task_id, task_type=replacement.definition.task_type)
    recovery.update(reselection_count=attempts + 1, current_graph_failures=0)
    response.task_id = replacement.definition.task_id
    response.task_status = "IN_PROGRESS"
    response.state_id = "S0_UNKNOWN"
    response.step = 0
    response.state_confidence = 0
    from ai_agent.runtime.progress import graph_progress
    response.total_steps = graph_progress(replacement, "S0_UNKNOWN")[1]
    guide = response.guide_command
    guide.command_type = "highlight"
    guide.action_type = "observe_only"
    guide.target_node_id = guide.target_label = guide.target_bounds = None
    guide.safety_level = "LOW_RISK"
    guide.requires_user_action = False
    guide.message = "다른 작업 경로를 찾았어요. 현재 화면을 다시 확인할게요."
    guide.render_policy = {"fallback_action": {"type": "request_fresh_observation",
        "expected_next_observation": {"send_fresh_accessibility_tree": True}},
        "recovery": {"schema_version": "ai1-recovery.v1", "decision": "recover",
            "reason_code": "GRAPH_RESELECTED", "next_action": "reobserve", "waiting_for": "observation",
            "recoverable": True, "attempts": 0, "retry_after_ms": 0, "options": []}}
    response.metadata["recovery"] = guide.render_policy["recovery"]
    response.metadata["reselection"] = {"status": "restarted", "count": attempts + 1,
        "failed_attempts": recovery["failed_attempts"], "previous_graph_hash": reference["sha256"],
        "new_graph_hash": new_reference["sha256"]}
    return {"recovery": recovery, "checkpoint": fresh, "graph_reference": new_reference,
            "episode_id": str(uuid4())}
