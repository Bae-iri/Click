from __future__ import annotations

import os

from ai_agent.runtime import (
    AccessibilityNode,
    AgentTurnRequest as RuntimeRequest,
    ClickAgentEngine,
    ContractViolationError,
    DeviceContext,
    RawGoalInput,
    TypedGoal,
    UserActionEvent,
    UserActionType,
    VisionElement as RuntimeVisionElement,
    VisionSnapshot as RuntimeVisionSnapshot,
)

from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse
from app.schemas.guidance import GuideCommandResponse
from app.repositories.sessions import SessionNotFoundError
from ai_agent.runtime.progress import graph_progress
from ai_agent.telemetry.execution_events import produce
from app.clients.screen_state_llm import LocalScreenStateLlm
from app.services.screen_jev import JevScreenResolver


class RuntimeAgentAdapter:
    """Translate the HTTP contract to the single ai_agent L3 runtime."""

    def __init__(self, session_store=None, task_graph=None) -> None:
        self.vision_consent_request_id = None
        graphs = None if task_graph is None else {
            task_graph.definition.task_id: task_graph,
            task_graph.definition.goal_type: task_graph,
        }
        provider = os.getenv("AI1_UI_SELECTOR_PROVIDER", "rules").strip().lower()
        if provider not in {"rules", "local", "jev"}:
            raise ValueError("AI1_UI_SELECTOR_PROVIDER must be rules or jev")
        # Jev owns step + target selection. Never also call the auxiliary selector
        # or the private screen-state LLM on this path.
        resolver = JevScreenResolver() if provider == "jev" else None
        if resolver:
            resolver.configuration()
        self.runtime = ClickAgentEngine(session_store=session_store, task_graphs=graphs,
            screen_resolver=resolver, ui_selector=None,
            state_resolver=None if resolver else LocalScreenStateLlm())

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        previous = self.runtime.session_store.get(request.session_id)
        raw_goal = getattr(request, "raw_goal", None)
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
        typed = getattr(request, "goal", None)
        device = getattr(request, "device_context", None)
        runtime_request = RuntimeRequest(
            request_id=request.request_id,
            session_id=request.session_id,
            screen_bounds=(0, 0, screen_width, screen_height)
            if screen_width and screen_height else None,
            raw_goal=RawGoalInput(
                source_text=raw_goal,
                locale="ko-KR",
                device_context=DeviceContext(
                    platform="android",
                    foreground_app=package_name,
                    app_package=package_name,
                    screen_bounds=(0, 0, screen_width, screen_height)
                    if screen_width and screen_height
                    else None,
                ),
            ) if raw_goal else None,
            typed_goal=TypedGoal(
                goal_type=typed.goal_type,
                target_app=typed.target_app,
                task_type=self.runtime.task_graphs[typed.goal_type].definition.task_type,
                slots=dict(typed.slots),
                source_text="",
                confidence=1.0,
                task_id=self.runtime.task_graphs[typed.goal_type].definition.task_id,
                typing_method="backend_accepted_goal",
                is_supported=True,
                required_slots_filled=True,
            ) if typed else None,
            device_context=DeviceContext(
                platform=device.platform,
                foreground_app=device.foreground_package,
                app_package=device.foreground_package,
                manufacturer=device.manufacturer,
                os_version=device.os_version,
                screen_bounds=(0, 0, screen_width, screen_height)
                if screen_width and screen_height else None,
            ) if device else None,
            accessibility_tree=tuple(
                AccessibilityNode(
                    node_id=node.node_id,
                    window_id=node.window_id,
                    parent_id=node.parent_id,
                    visible_to_user=node.visible_to_user,
                    package_name=node.package_name or package_name,
                    class_name=node.class_name,
                    role=node.role,
                    text=node.text,
                    content_description=node.content_description,
                    resource_id=node.resource_id,
                    bounds_in_screen=node.bounds_in_screen,
                    clickable=node.clickable,
                    enabled=node.enabled,
                    selected=node.selected,
                    scrollable=node.scrollable,
                    checkable=node.checkable,
                    extras={**node.extras, **{key: True for key in
                        ("password", "is_password", "isPassword", "editable", "isEditable")
                        if (node.model_extra or {}).get(key)}},
                ) for node in tree.nodes
            ) if tree else (),
            vision_snapshot=RuntimeVisionSnapshot(
                user_consented=vision_snapshot.user_consented,
                consent_request_id=self.vision_consent_request_id,
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
                observation_id=vision_snapshot.observation_id,
                active_window_id=vision_snapshot.active_window_id,
                processing_time_ms=vision_snapshot.processing_time_ms,
            ) if vision_snapshot else None,
            active_window_id=tree.active_window_id if tree else None,
            observation_id=tree.observation_id if tree else None,
            captured_at_ms=(tree.captured_at_ms if tree.captured_at_ms is not None else
                int(tree.captured_at.timestamp() * 1000) if tree.captured_at else None) if tree else None,
            user_confirmation=None if self.vision_consent_request_id and (vision_snapshot or getattr(request, "vision_failure", None)) else request.user_confirmation,
            vision_failure=getattr(request, "vision_failure", None) if self.vision_consent_request_id else None,
            user_action_event=self._action_event(request),
            episode_id=getattr(request, "episode_id", None),
            applied_settings=tree.applied_settings.model_dump() if tree and tree.applied_settings else {},
        )
        try:
            result = self.runtime.handle_turn(runtime_request)
        except ContractViolationError as error:
            if getattr(request, "raw_goal", None) is None and getattr(request, "goal", None) is None:
                raise SessionNotFoundError(request.session_id) from error
            raise ValueError(str(error)) from error
        guide = result.guide_command
        command_type = guide.command_type.value
        graph = next((g for g in self.runtime.task_graphs.values()
                      if g.definition.task_id == result.task_id), None)
        # Progress is presentation only; completion requires the runtime predicate.
        task_completed = guide.command_type.value == "done"
        status = {
            "confirmation": "WAITING_CONFIRMATION",
            "done": "COMPLETED",
            "blocked": "BLOCKED",
        }.get(command_type, "IN_PROGRESS")
        runtime_session = self.runtime.session_store.get(request.session_id)
        progress_state = result.current_state_id
        if progress_state == "S0_UNKNOWN" and runtime_session and runtime_session.observed_path:
            progress_state = runtime_session.observed_path[-1]
        step, total_steps = graph_progress(graph, progress_state, status == "COMPLETED")
        technical = dict(runtime_session.technical_outcome) if runtime_session else {}
        recovery = guide.render_policy.get("recovery")
        recovering = bool((recovery and recovery.get("recoverable")) or guide.render_policy.get("backend_handoff"))
        # Recoverable navigation failures keep the episode alive and preserve their instructions.
        legacy_handoff = recovering and command_type == "blocked"
        if legacy_handoff:
            command_type = "highlight"
            status = "IN_PROGRESS"
        result_check = runtime_session is not None and not recovering and command_type == "done"
        if result_check:
            status = "WAITING_RESULT"
        target = next((node for node in tree.nodes if result.match_result and node.node_id == result.match_result.target_node_id), None) if tree else None
        name = (target.class_name or "").lower() if target else ""
        role = "slider" if "seekbar" in name else "button" if "button" in name else "menu_item" if target and target.clickable else "other"
        self.execution_event = produce(result, previous, runtime_session, role,
            request.user_action_event.action_type if request.user_action_event else None)
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
            total_steps=total_steps,
            state_id=result.current_state_id,
            state_confidence=result.debug.get("state_score", 0.0),
            task_status=status,
            guide_command=GuideCommandResponse(
                guide_command_id=guide.guide_command_id,
                command_type="result_confirmation" if result_check else command_type,
                proposed_action=result.match_result.action_type.value if command_type == "confirmation" and "vision_fallback" not in guide.render_policy and result.match_result and guide.target_node_id is not None and guide.target_bounds is not None and result.match_result.action_type.value in {"tap", "drag", "scroll"} else None,
                action_type="observe_only" if result_check or legacy_handoff else guide.action_type.value,
                message=guide.message,
                target_node_id=None if legacy_handoff else guide.target_node_id,
                target_label=None if legacy_handoff else guide.target_label,
                target_bounds=None if legacy_handoff else guide.target_bounds,
                safety_level="LOW_RISK" if result_check or legacy_handoff else guide.safety_level.value,
                requires_user_action=False if result_check or legacy_handoff else guide.requires_user_action,
                render_policy={} if result_check else dict(guide.render_policy),
            ),
            metadata={
                "technical_outcome": technical,
                "checklist": result.debug.get("checklist", []),
                "goal_label": "글자를 작게 보기" if result.typed_goal and result.typed_goal.slots.get("direction") == "decrease" else "글자를 크게 보기",
                "runtime": "ai_agent.runtime",
                "runtime_decision": result.debug.get("replan_decision"),
                "recovery": recovery,
                "score_semantics": "heuristic_scores_not_probabilities",
                "typed_goal": result.typed_goal.to_dict() if result.typed_goal else None,
                "verification": {
                    "result": verification,
                    "slider_value_increased": bool(
                        verification and verification.get("status") == "SUCCESS"
                        and verification.get("evidence") == "target_value_increased"
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
            event_id=event.event_id,
            session_id=event.session_id,
            episode_id=event.episode_id,
        )
