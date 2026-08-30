from __future__ import annotations

from ai_agent.runtime import (
    AccessibilityNode,
    AgentTurnRequest as RuntimeRequest,
    ClickAgentEngine,
    ContractViolationError,
    DeviceContext,
    RawGoalInput,
    UserActionEvent,
    UserActionType,
    VisionElement as RuntimeVisionElement,
    VisionSnapshot as RuntimeVisionSnapshot,
)

from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse
from app.schemas.guidance import GuideCommandResponse
from app.repositories.sessions import SessionNotFoundError


class RuntimeAgentAdapter:
    """Translate the HTTP contract to the single ai_agent L3 runtime."""

    def __init__(self) -> None:
        self.runtime = ClickAgentEngine()

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        tree = request.accessibility_tree
        vision_snapshot = request.vision_snapshot
        package_name = (
            (tree.package_name if tree else None)
            or (vision_snapshot.package_name if vision_snapshot else None)
        )
        screen_width = (
            (tree.screen_width if tree else None)
            or (vision_snapshot.screen_width if vision_snapshot else None)
        )
        screen_height = (
            (tree.screen_height if tree else None)
            or (vision_snapshot.screen_height if vision_snapshot else None)
        )
        runtime_request = RuntimeRequest(
            request_id=request.request_id,
            session_id=request.session_id,
            raw_goal=RawGoalInput(
                source_text=request.raw_goal,
                locale="ko-KR",
                device_context=DeviceContext(
                    platform="android",
                    foreground_app=package_name,
                    app_package=package_name,
                    screen_bounds=(0, 0, screen_width, screen_height)
                    if screen_width and screen_height
                    else None,
                ),
            ) if request.raw_goal else None,
            accessibility_tree=tuple(
                AccessibilityNode(
                    node_id=node.node_id,
                    package_name=node.package_name or package_name,
                    class_name=node.class_name,
                    text=node.text,
                    content_description=node.content_description,
                    resource_id=node.resource_id,
                    bounds_in_screen=node.bounds_in_screen,
                    clickable=node.clickable,
                    enabled=node.enabled,
                    selected=node.selected,
                    scrollable=node.scrollable,
                    checkable=node.checkable,
                    extras=node.extras,
                ) for node in tree.nodes
            ) if tree else (),
            vision_snapshot=RuntimeVisionSnapshot(
                user_consented=vision_snapshot.user_consented,
                redaction_applied=vision_snapshot.redaction_applied,
                provider=vision_snapshot.provider,
                screenshot_base64=vision_snapshot.screenshot_base64,
                mime_type=vision_snapshot.mime_type,
                ocr_text=vision_snapshot.ocr_text,
                elements=tuple(
                    RuntimeVisionElement(
                        element_id=element.element_id,
                        text=element.text,
                        bounds_in_screen=element.bounds_in_screen,
                        role=element.role,
                        confidence=element.confidence,
                        actionable=element.actionable,
                    )
                    for element in vision_snapshot.elements
                ),
                package_name=vision_snapshot.package_name or package_name,
                screen_width=vision_snapshot.screen_width or screen_width,
                screen_height=vision_snapshot.screen_height or screen_height,
                captured_at_ms=vision_snapshot.captured_at_ms,
            ) if vision_snapshot else None,
            user_confirmation=request.user_confirmation,
            user_action_event=self._action_event(request),
        )
        try:
            result = self.runtime.handle_turn(runtime_request)
        except ContractViolationError as error:
            if request.raw_goal is None:
                raise SessionNotFoundError(request.session_id) from error
            raise ValueError(str(error)) from error
        guide = result.guide_command
        command_type = guide.command_type.value
        slider_completed = bool(
            result.verification_result
            and result.verification_result.message
            == "Slider value increased in the requested direction."
        )
        if slider_completed:
            command_type = "done"
        status = {
            "confirmation": "WAITING_CONFIRMATION",
            "done": "COMPLETED",
            "blocked": "BLOCKED",
        }.get(command_type, "IN_PROGRESS")
        step = {
            "S0_UNKNOWN": 0, "S1_SETTINGS_HOME": 1, "S2_DISPLAY_MENU": 2,
            "S3_FONT_MENU": 3, "S4_FONT_SIZE_CONTROL": 4,
            "S5_FONT_SIZE_CHANGED": 4, "S_DONE": 4,
        }.get(result.current_state_id, 0)
        runtime_session = self.runtime.session_store.get(request.session_id)
        slider_values = (
            runtime_session.last_snapshot.slider_values
            if runtime_session and runtime_session.last_snapshot else {}
        )
        verification = result.verification_result.to_dict() if result.verification_result else None
        return AgentTurnResponse(
            request_id=result.request_id,
            session_id=result.session_id,
            task_id=result.task_id or "unknown",
            step=step,
            total_steps=4,
            state_id=result.current_state_id,
            state_confidence=result.match_result.confidence if result.match_result else 0.0,
            task_status=status,
            guide_command=GuideCommandResponse(
                guide_command_id=guide.guide_command_id,
                command_type=command_type,
                action_type="observe_only" if slider_completed else guide.action_type.value,
                message=guide.message,
                target_node_id=guide.target_node_id,
                target_label=guide.target_label,
                target_bounds=guide.target_bounds,
                safety_level=guide.safety_level.value,
                requires_user_action=False if slider_completed else guide.requires_user_action,
                render_policy=dict(guide.render_policy),
            ),
            metadata={
                "runtime": "ai_agent.runtime",
                "typed_goal": result.typed_goal.to_dict() if result.typed_goal else None,
                "verification": {
                    "result": verification,
                    "slider_value_increased": bool(
                        verification and verification.get("status") == "SUCCESS"
                        and verification.get("message") == "Slider value increased in the requested direction."
                    ),
                    "slider_values": slider_values,
                },
                "match_result": result.match_result.to_dict() if result.match_result else None,
                "debug": result.debug,
                "trace_count": len(self.runtime.traces),
            },
        )

    @staticmethod
    def _action_event(request: AgentTurnRequest) -> UserActionEvent | None:
        event = request.user_action_event
        if event is None:
            return None
        return UserActionEvent(
            action_type=UserActionType(event.action_type),
            guide_command_id=event.guide_command_id,
            target_node_id=event.target_node_id,
            target_bounds=event.target_bounds,
            occurred_at_ms=event.occurred_at_ms,
        )
