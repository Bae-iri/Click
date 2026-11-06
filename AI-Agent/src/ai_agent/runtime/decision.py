"""Choose one final guide before committing the turn's execution state."""
from dataclasses import dataclass, replace

from ai_agent.runtime.guide import build_guide_command, command_for_vision_analysis, recovery_command
from ai_agent.runtime.types import ActionType, CommandType, GuideCommand, SafetyLevel


@dataclass(frozen=True)
class GuideDecision:
    guide: GuideCommand
    keep_attempt: bool


def _limit_command(graph, target):
    increase = graph.definition.required_slots.get("direction") == "increase"
    if graph.definition.required_slots.get("setting") == "font_size":
        message = ("표시한 슬라이더가 이미 가장 큰 글자 크기예요. 더 크게 조절할 수 없어요." if increase else
                   "표시한 슬라이더가 이미 가장 작은 글자 크기예요. 더 작게 조절할 수 없어요.")
    else:
        message = ("이미 조절 가능한 최댓값이에요. 더 높일 수 없어요." if increase else
                   "이미 조절 가능한 최솟값이에요. 더 낮출 수 없어요.")
    return GuideCommand("limit", CommandType.HIGHLIGHT, ActionType.OBSERVE_ONLY, message,
        SafetyLevel.LOW_RISK, False, target_node_id=target.node_id, target_bounds=target.bounds,
        target_label=target.label, render_policy={"avoid_covering_target": True, "adjustment_limit": True})


def decide_guide(*, request, graph, match, confirmation, completed, attempt, keep_attempt,
                 awaiting_observation, recovery, cycle, vision_analysis, primary,
                 limit_target=None, vocabulary_key=""):
    """Safety/stop, completion, result wait, acquisition, selection, then action.

    Each branch returns a single command. Lower-priority decisions never replace
    a rejection, privacy block, completed task or an outstanding action result.
    """
    primary_wait = primary is not None and primary.match is None
    masking_failed = vision_analysis and vision_analysis.status == "masking_failed"
    transition = next((t for t in graph.transitions if t.transition_id == match.transition_id), None)
    if masking_failed:
        guide = command_for_vision_analysis(request.session_id, vision_analysis)
        keep = False
    elif request.user_confirmation == "rejected" and not cycle.command:
        guide = build_guide_command(session_id=request.session_id, match_result=match,
            graph=graph, user_confirmation="rejected")
        keep = False
    elif transition and transition.safety_level == SafetyLevel.BLOCKED:
        guide = build_guide_command(session_id=request.session_id, match_result=match, graph=graph)
        keep = False
    elif completed:
        guide = build_guide_command(session_id=request.session_id, match_result=match,
            graph=graph, task_completed=True)
        keep = False
    elif limit_target is not None:
        guide = _limit_command(graph, limit_target)
        keep = False
    elif primary is None and recovery.next_action == "go_back":
        guide = recovery_command(request.session_id, recovery)
        keep = False
    elif awaiting_observation:
        if recovery.decision != "continue" and recovery.waiting_for == "user":
            guide = recovery_command(request.session_id, recovery)
        else:
            guide = GuideCommand(attempt["guide_command_id"], CommandType.HIGHLIGHT,
                ActionType.OBSERVE_ONLY, "조작 결과를 확인하고 있어요.", SafetyLevel.LOW_RISK, False,
                render_policy={"fallback_action": {"type": "request_fresh_observation",
                    "expected_next_observation": {"send_fresh_accessibility_tree": True}}})
        keep = keep_attempt
    elif cycle.command and (primary is None or primary_wait):
        guide = replace(cycle.command, render_policy={**cycle.command.render_policy, "vision_cycle": cycle.state})
        keep = False
    elif primary_wait:
        guide = recovery_command(request.session_id, recovery)
        keep = False
    elif (primary is None and recovery.decision != "continue"
            and recovery.next_action != "open_settings"):
        guide = recovery_command(request.session_id, recovery)
        keep = bool(attempt and recovery.reason_code == "ACTION_EVENT_MISSING")
    else:
        guide = build_guide_command(session_id=request.session_id, match_result=match,
            graph=graph, user_confirmation=confirmation)
        keep = keep_attempt
        if match.fallback_reason and vision_analysis:
            transition = next((t for t in graph.transitions if t.transition_id == match.transition_id), None)
            guide = command_for_vision_analysis(request.session_id, vision_analysis,
                manual_instruction=transition.guide_message if transition else None) or guide

    policy = dict(guide.render_policy)
    if recovery.decision != "continue" and guide.command_type != CommandType.BLOCKED:
        policy.setdefault("recovery", recovery.to_dict())
    if "vision_fallback" in policy:
        policy["vision_fallback"] = {**policy["vision_fallback"], "vocabulary_key": vocabulary_key}
    if primary is not None:
        policy.update(ui_selector="jev", revalidate_before_action=True)
    return GuideDecision(replace(guide, render_policy=policy), keep)
