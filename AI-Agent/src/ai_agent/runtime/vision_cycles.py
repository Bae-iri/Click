"""Three fresh capture/OCR/match cycles; no LLM is configured or called here."""
from dataclasses import dataclass

from ai_agent.runtime.guide import vision_fallback_command
from ai_agent.runtime.types import ActionType, CommandType, GuideCommand, SafetyLevel

MAX_FAILURES = 3
STRATEGIES = ("full_frame", "upscale", "focus_or_tiles")


@dataclass(frozen=True)
class CycleDecision:
    state: dict
    command: GuideCommand | None = None


def llm_handoff_placeholder(*, ax_empty: bool) -> dict:
    # Connection point only. Never send images, call a provider or invent a target.
    return {"status": "not_configured", "reason_code": "target_not_found",
            "accessibility_tree_empty": ax_empty, "raw_image_egress": False}


def decide_vision_cycle(*, request, session, graph, match, analysis, completed=False):
    old = dict(session.vision_state) if session else {}
    step = match.transition_id
    # A blank observation cannot prove navigation to a different graph step.
    if match.state_id == "S0_UNKNOWN" and old.get("step_id"):
        step = old["step_id"]
    scope = [request.episode_id, graph.definition.task_id, step]
    state = old if old.get("scope") == scope else {}
    must_stop = request.user_confirmation == "rejected" or request.vision_failure in {"masking_failed", "unsupported"} or (
        analysis and analysis.status in {"masking_failed", "not_configured"})
    if not must_stop and (completed or (match.target_node_id and not match.fallback_reason)):
        return CycleDecision({})
    if analysis is None and not state:
        return CycleDecision({})
    state = {**state, "scope": scope, "step_id": step,
             "failed_cycles": state.get("failed_cycles", 0), "max_failed_cycles": MAX_FAILURES,
             "accessibility_tree_empty": not bool(request.accessibility_tree), "return_to": "agent1"}
    failure = request.vision_failure
    if state.get("status") == "waiting_for_stable_screen" and request.user_action_event and request.user_action_event.action_type.value == "manual_override":
        state["status"] = "retrying"
    if failure == "masking_failed" or (analysis and analysis.status == "masking_failed"):
        state.update(status="blocked", last_cycle_reason="masking_failed")
    elif failure == "unsupported" or (analysis and analysis.status == "not_configured"):
        state.update(status="unsupported", last_cycle_reason=failure or analysis.reason)
    elif request.user_confirmation == "rejected" and not failure:
        state.update(status="declined", last_cycle_reason="user_declined")
    elif failure == "screen_changed":
        state.update(status="waiting_for_stable_screen", last_cycle_reason="screen_changed")
    elif state.get("status") not in {"failed", "blocked", "unsupported", "declined", "waiting_for_stable_screen"}:
        reason = None
        if failure in {"capture_failed", "ocr_failed"}:
            reason = "recognition_failed"
        elif analysis and analysis.status in {"no_signal", "provider_error"}:
            reason = "recognition_failed"
        elif analysis and analysis.status == "used":
            reason = "ambiguous_target" if match.fallback_reason == "ambiguous_target" else "target_not_found"
        if reason and state.get("last_request_id") != request.request_id:
            state["failed_cycles"] += 1
            state.update(last_request_id=request.request_id, last_cycle_reason=reason)
        state["status"] = "failed" if state["failed_cycles"] >= MAX_FAILURES else "retrying"
    # These are separate facts: readable menus without a matching target do not
    # prove physical absence. AX can be empty independently of OCR quality.
    state["absence_confirmed"] = False
    transition = next((t for t in graph.transitions if t.transition_id == step), None)
    instruction = transition.guide_message if transition else "설정 첫 화면으로 이동한 뒤 다시 확인해 주세요."
    if state["status"] == "retrying":
        number = state["failed_cycles"] + 1
        command = vision_fallback_command(session_id=request.session_id,
            message=(f"화면에서 안내할 항목을 다시 찾을게요. ({number}/3회) " if number > 1 else "")
                + "현재 화면을 한 번 캡처해 기기에서 글자를 읽고, 글자와 위치를 서버에 보내도 될까요? 원본 이미지는 전송하지 않아요.")
        command.render_policy["vision_fallback"].update(
            cycle_number=number, strategy=STRATEGIES[number - 1], retry_delay_ms=600 if number > 1 else 250)
        # Crop only where AX supplies a real, visible menu container. Empty AX
        # falls back to overlapping tiles, never an invented target rectangle.
        containers = [node.bounds_in_screen for node in request.accessibility_tree
            if node.scrollable and node.visible_to_user and node.enabled and node.bounds_in_screen]
        if containers:
            command.render_policy["vision_fallback"]["focus_bounds"] = list(max(containers,
                key=lambda b: max(0, b[2] - b[0]) * max(0, b[3] - b[1])))
        return CycleDecision(state, command)
    if state["status"] in {"blocked", "unsupported"}:
        message = "개인정보를 안전하게 가리지 못해 화면 분석을 중단했어요." if state["status"] == "blocked" else "현재 화면 분석을 지원하지 않아요."
        return CycleDecision(state, GuideCommand("vision_stop", CommandType.BLOCKED,
            ActionType.OBSERVE_ONLY, message, SafetyLevel.BLOCKED, False,
            render_policy={"vision_cycle": state}))
    if state["status"] == "failed" and state.get("last_cycle_reason") == "target_not_found":
        state["llm_fallback"] = llm_handoff_placeholder(ax_empty=state["accessibility_tree_empty"])
    message = ("화면이 바뀌었어요. 화면을 멈춘 뒤 다시 확인해 주세요."
        if state["status"] == "waiting_for_stable_screen" else
        "화면 분석을 종료했어요. " + instruction + " 이동한 뒤 다시 확인해 주세요.")
    return CycleDecision(state, GuideCommand("vision_return", CommandType.HIGHLIGHT,
        ActionType.OBSERVE_ONLY, message, SafetyLevel.LOW_RISK, False,
        render_policy={"vision_cycle": state, "recovery": {
            "schema_version": "ai1-recovery.v1", "recoverable": True, "waiting_for": "user",
            "decision": "wait", "next_action": "manual_navigation", "reason_code": "VISION_" + state["status"].upper()}}))
