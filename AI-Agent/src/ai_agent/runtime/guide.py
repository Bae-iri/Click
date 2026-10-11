"""Guide command building and safety handling."""

from __future__ import annotations

from ai_agent.runtime.types import (
    ActionType,
    CommandType,
    GuideCommand,
    MatchResult,
    SafetyLevel,
    TaskGraph,
    Transition,
)


def recovery_command(session_id, plan):
    messages = {
        "TARGET_NOT_VISIBLE": f"{plan.target_label or '다음'} 항목이 현재 화면에 보이지 않아요. 안내 창을 접고 목록을 위로 밀어 아래 항목을 살펴보세요. 화면이 바뀌면 이어서 안내할게요.",
        "STATE_AMBIGUOUS": "현재 화면을 정확히 구분하지 못했어요. 화면 제목을 확인하거나 이전 화면으로 돌아간 뒤 다시 확인해 주세요.",
        "STATE_UNKNOWN": "현재 화면을 작업 경로와 연결하지 못했어요. 팝업이 있다면 닫거나 설정 화면으로 이동한 뒤 다시 확인해 주세요.",
        "TARGET_AMBIGUOUS": "비슷한 항목이 여러 개라 누를 곳을 확정하지 못했어요. 직접 원하는 메뉴로 이동하거나 화면을 다시 확인해 주세요.",
        "ACTION_EVENT_MISSING": "값이 바뀌었지만 안내한 조작인지 확인하지 못했어요. 추가로 조작하지 말고 현재 화면을 다시 확인해 주세요.",
        "ACTION_RESULT_UNOBSERVABLE": "조작 결과를 아직 확인하지 못했어요. 추가로 조작하지 말고 화면을 다시 확인해 주세요.",
        "VISION_UNAVAILABLE": "화면 분석으로도 다음 항목을 확인하지 못했어요. 직접 메뉴로 이동하거나 화면을 다시 확인해 주세요.",
        "ACTION_BUDGET_EXHAUSTED": "여러 번 시도했지만 진행을 확인하지 못했어요. 현재 위치와 작업 경로를 다시 확인해야 해요.",
        "GRAPH_PATH_UNAVAILABLE": "현재 화면에서 이어갈 작업 경로가 없어요. 이전 화면으로 돌아가거나 화면을 다시 확인해 주세요.",
    }
    message = messages.get(plan.reason_code, "다음 항목을 확인하지 못했어요. 직접 메뉴로 이동하거나 화면을 다시 확인해 주세요.")
    policy = {"avoid_covering_target": False, "recovery": plan.to_dict()}
    if plan.next_action == "reobserve":
        message = "화면이 바뀌었는지 다시 확인하고 있어요. 잠시 기다려 주세요."
        policy["fallback_action"] = {"type": "request_fresh_observation", "reason_code": plan.reason_code}
    if plan.decision == "handoff":
        policy["backend_handoff"] = {"type": "task_graph_reselection_required", "reason_code": plan.reason_code}
    return GuideCommand(guide_command_id=f"guide_{session_id}_recovery", command_type=CommandType.HIGHLIGHT,
        action_type=ActionType.OBSERVE_ONLY, message=message, safety_level=SafetyLevel.LOW_RISK,
        requires_user_action=False, render_policy=policy)


def build_guide_command(
    *,
    session_id: str,
    match_result: MatchResult,
    graph: TaskGraph,
    user_confirmation: str | None = None,
    task_completed: bool = False,
) -> GuideCommand:
    transition = _transition_by_id(graph, match_result.transition_id)

    if transition and transition.safety_level == SafetyLevel.BLOCKED:
        return _blocked_command(session_id, transition, "이 작업은 자동 안내를 중단해야 해요.")

    if user_confirmation == "rejected":
        return _blocked_command(session_id, transition, "요청을 중단했어요.")

    if task_completed:
        return GuideCommand(
            guide_command_id=f"guide_{session_id}_done",
            command_type=CommandType.DONE,
            action_type=ActionType.OBSERVE_ONLY,
            message=transition.guide_message if transition else "요청한 변경을 확인했어요.",
            safety_level=SafetyLevel.LOW_RISK,
            requires_user_action=False,
        )

    if match_result.fallback_reason:
        if match_result.transition_id == "S0_TO_S1_OPEN_SETTINGS":
            return GuideCommand(
                guide_command_id=f"guide_{session_id}_{match_result.state_id}_open_settings_recovery",
                command_type=CommandType.HIGHLIGHT,
                action_type=ActionType.OBSERVE_ONLY,
                message="설정 앱을 현재 화면에서 찾지 못했어요. 설정 앱을 열 수 있는 화면으로 이동해 주세요.",
                safety_level=SafetyLevel.LOW_RISK,
                requires_user_action=True,
                render_policy={
                    "avoid_covering_target": False,
                    "preferred_position": "center",
                    "recovery_action": {
                        "type": "open_android_settings_or_show_app_drawer",
                        "target_app": "android_settings",
                        "android_intent_action": "android.settings.SETTINGS",
                        "fallback_screen": "launcher_or_app_drawer",
                        "requires_user_activation": True,
                        "reason": "settings_app_icon_not_visible",
                    },
                    "expected_next_observation": {
                        "state_id": "S1_SETTINGS_HOME",
                        "package_candidates": [
                            "com.android.settings",
                            "com.samsung.android.settings",
                        ],
                        "send_fresh_accessibility_tree": True,
                        "send_user_action_event": "manual_override",
                    },
                },
            )
        return GuideCommand(
            guide_command_id=f"guide_{session_id}_{match_result.state_id}_fallback",
            command_type=CommandType.HIGHLIGHT,
            action_type=ActionType.OBSERVE_ONLY,
            message="현재 화면에서 다음에 누를 항목을 찾지 못했어요.",
            safety_level=SafetyLevel.LOW_RISK,
            requires_user_action=False,
            render_policy={
                "avoid_covering_target": False,
                "preferred_position": "center",
                "fallback_action": {
                    "type": "request_fresh_observation",
                    "reason": match_result.fallback_reason,
                    "expected_next_observation": {
                        "send_fresh_accessibility_tree": True,
                        "send_vision_snapshot_if_target_missing": True,
                    },
                },
            },
        )

    if transition and transition.safety_level == SafetyLevel.CONFIRM_REQUIRED:
        if user_confirmation != "accepted":
            return GuideCommand(
                guide_command_id=f"guide_{session_id}_{transition.transition_id}_confirm",
                command_type=CommandType.CONFIRMATION,
                action_type=ActionType.OBSERVE_ONLY,
                target_node_id=match_result.target_node_id,
                target_label=match_result.target_label,
                target_bounds=match_result.target_bounds,
                message=transition.confirmation_message
                or "이 설정이 변경될 수 있어요. 계속할까요?",
                safety_level=SafetyLevel.CONFIRM_REQUIRED,
                requires_user_action=True,
            )

    if transition and transition.to_state == graph.definition.done_state:
        return GuideCommand(guide_command_id=f"guide_{session_id}_unverified",
            command_type=CommandType.BLOCKED, action_type=ActionType.OBSERVE_ONLY,
            message="작업 완료를 확인할 근거가 부족해요.", safety_level=SafetyLevel.BLOCKED,
            requires_user_action=False)

    message = transition.guide_message if transition else "다음 항목을 눌러주세요."
    safety_level = transition.safety_level if transition else SafetyLevel.LOW_RISK
    return GuideCommand(
        guide_command_id=f"guide_{session_id}_{match_result.transition_id or match_result.state_id}",
        command_type=CommandType.HIGHLIGHT,
        action_type=match_result.action_type,
        target_node_id=match_result.target_node_id,
        target_label=match_result.target_label,
        target_bounds=match_result.target_bounds,
        message=message,
        safety_level=safety_level,
        requires_user_action=transition.requires_user_action if transition else True,
    )


def clarification_command(
    *,
    session_id: str,
    message: str,
) -> GuideCommand:
    return GuideCommand(
        guide_command_id=f"guide_{session_id}_clarification",
        command_type=CommandType.CONFIRMATION,
        action_type=ActionType.OBSERVE_ONLY,
        message=message,
        safety_level=SafetyLevel.CONFIRM_REQUIRED,
        requires_user_action=True,
    )


def vision_fallback_command(
    *,
    session_id: str,
    message: str,
    command_type: CommandType = CommandType.CONFIRMATION,
    safety_level: SafetyLevel = SafetyLevel.CONFIRM_REQUIRED,
) -> GuideCommand:
    return GuideCommand(
        guide_command_id=f"guide_{session_id}_vision_fallback",
        command_type=command_type,
        action_type=ActionType.OBSERVE_ONLY,
        message=message,
        safety_level=safety_level,
        requires_user_action=command_type == CommandType.CONFIRMATION,
        render_policy={
            "avoid_covering_target": False,
            "preferred_position": "center",
            "vision_fallback": {
                "requires_user_consent": command_type == CommandType.CONFIRMATION,
                "consent_scope": "single_analysis",
                "processing": "on_device_ocr",
                "accepted_payloads": [
                    "vision_snapshot.elements",
                    "vision_snapshot.ocr_text",
                ],
                "raw_screenshot_allowed": False,
                "send_fresh_accessibility_tree": True,
            },
        },
    )


def _transition_by_id(graph: TaskGraph, transition_id: str | None) -> Transition | None:
    if not transition_id:
        return None
    for transition in graph.transitions:
        if transition.transition_id == transition_id:
            return transition
    return None


def _blocked_command(
    session_id: str,
    transition: Transition | None,
    message: str,
) -> GuideCommand:
    command_suffix = transition.transition_id if transition else "blocked"
    return GuideCommand(
        guide_command_id=f"guide_{session_id}_{command_suffix}_blocked",
        command_type=CommandType.BLOCKED,
        action_type=ActionType.OBSERVE_ONLY,
        message=message,
        safety_level=SafetyLevel.BLOCKED,
        requires_user_action=False,
    )
