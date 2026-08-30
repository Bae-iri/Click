from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any
from uuid import uuid4

from ai_agent.guide.command_builder import GuideCommandBuilder
from ai_agent.matching.ui_matcher import UITargetMatcher
from ai_agent.observation.accessibility_parser import AccessibilityTreeParser
from ai_agent.observation.ui_normalizer import UINormalizer
from ai_agent.safety.safety_gate import SafetyGate
from ai_agent.schemas import NormalizedNode, TaskDefinition, TransitionDefinition
from ai_agent.task_graph.graph import TaskGraph

from app.models.session import GuidanceSession, utc_now
from app.repositories.sessions import InMemorySessionRepository, SessionNotFoundError
from app.schemas.agent_turn import AgentTurnRequest, AgentTurnResponse
from app.schemas.guidance import AccessibilityNode, GuideCommandResponse, ScreenSnapshot


class UnsupportedGoalError(ValueError):
    pass


@dataclass(frozen=True)
class AgentDecision:
    state_id: str
    state_confidence: float
    next_state: str
    guide: GuideCommandResponse
    completed: bool = False


MESSAGES = {
    "S0_UNKNOWN": "설정 앱을 열어주세요.",
    "S1_SETTINGS_HOME": "디스플레이를 눌러주세요.",
    "S2_DISPLAY_MENU": "글자 크기와 스타일을 눌러주세요.",
    "S3_FONT_MENU": "글자 크기를 눌러주세요.",
    "S4_FONT_SIZE_CONTROL": "글자를 크게 하려면 슬라이더를 오른쪽으로 움직여주세요.",
    "S5_FONT_SIZE_CHANGED": "글자 크기가 더 크게 변경되었어요.",
}


class AgentClient:
    def __init__(self, repository_root: Path | None = None) -> None:
        root = repository_root or Path(__file__).resolve().parents[3]
        self.graph = self._load_graph(root / "AI-Agent" / "task_graphs" / "mvp_01_font_size_increase.json")
        self.parser = AccessibilityTreeParser()
        self.normalizer = UINormalizer()
        self.matcher = UITargetMatcher()
        self.safety_gate = SafetyGate()
        self.command_builder = GuideCommandBuilder()

    @staticmethod
    def _load_graph(path: Path) -> TaskGraph:
        payload = json.loads(path.read_text(encoding="utf-8"))
        task = payload["task"]
        definition = TaskDefinition(
            task_id=task["task_id"],
            goal_type=task["goal_type"],
            name=task["name"],
            target_app=task["target_app"],
            task_type=task["task_type"],
            start_states=tuple(task["start_states"]),
            done_state=task["done_state"],
            graph_version=task["graph_version"],
        )
        transitions = {
            item["transition_id"]: TransitionDefinition(
                transition_id=item["transition_id"],
                from_state=item["from"],
                to_state=item["to"],
                action_type=item["action_type"],
                target_keywords=tuple(item["target_keywords"]),
                target_role=item.get("target_role"),
                safety_level=item["safety_level"],
                allow_scroll=item.get("allow_scroll", False),
            )
            for item in payload["transitions"]
        }
        return TaskGraph(definition, payload["states"], transitions, payload["scroll_policy"])

    def supports_goal(self, goal: str) -> bool:
        normalized = goal.lower().replace(" ", "")
        font_terms = ("글자", "글씨", "폰트", "font", "textsize")
        increase_terms = ("크게", "키우", "확대", "increase", "big")
        return any(term in normalized for term in font_terms) and any(term in normalized for term in increase_terms)

    def normalize_snapshot(self, snapshot: ScreenSnapshot) -> list[NormalizedNode]:
        raw_nodes = []
        for node in snapshot.nodes:
            item = node.model_dump()
            raw_nodes.append(
                {
                    "nodeId": item["node_id"],
                    "text": item["text"],
                    "contentDescription": item["content_description"],
                    "resourceId": item["resource_id"],
                    "className": item["class_name"],
                    "role": item["role"],
                    "boundsInScreen": item["bounds"],
                    "clickable": item["clickable"],
                    "enabled": item["enabled"],
                    "scrollable": item["scrollable"],
                }
            )
        return self.normalizer.normalize(self.parser.parse({"nodes": raw_nodes}))

    def decide(self, snapshot: ScreenSnapshot, previous_state: str | None, confirmed: bool) -> AgentDecision:
        nodes = self.normalize_snapshot(snapshot)
        state_id, confidence = self._classify(snapshot.package_name, nodes, previous_state)

        if state_id == "S5_FONT_SIZE_CHANGED":
            return self._done(state_id, confidence)

        outgoing = self.graph.outgoing(state_id)
        if not outgoing:
            return self._unknown(state_id, confidence)

        transition = self._choose_transition(outgoing, nodes)
        safety = self.safety_gate.evaluate(transition)
        match = self.matcher.match(transition, nodes)

        if transition.action_type == "observe_only":
            return self._done(state_id, confidence)

        target = next((node for node in nodes if node.node_id == match.target_node_id), None)
        message = MESSAGES.get(state_id, "화면을 다시 확인해주세요.")

        if safety == "CONFIRM_REQUIRED" and not confirmed:
            guide = GuideCommandResponse(
                guide_command_id=f"guide_{uuid4().hex}",
                command_type="confirmation",
                action_type=transition.action_type,
                message="글자 크기 설정이 바뀔 수 있어요. 계속 크게 조정할까요?",
                target_node_id=target.node_id if target else None,
                target_label=target.label if target else None,
                target_bounds=target.bounds if target else None,
                safety_level=safety,
                requires_user_action=True,
                render_policy={"confirmation_required": True},
            )
            return AgentDecision(state_id, confidence, transition.to_state, guide)

        if transition.action_type == "drag":
            guide = GuideCommandResponse(
                guide_command_id=f"guide_{uuid4().hex}",
                command_type="gesture_hint",
                action_type="drag",
                message=message,
                target_node_id=target.node_id if target else None,
                target_label=target.label if target else None,
                target_bounds=target.bounds if target else None,
                safety_level=safety,
                requires_user_action=True,
                render_policy={"direction": "right", "avoid_covering_target": True},
            )
            return AgentDecision(state_id, confidence, transition.to_state, guide)

        if target and match.confidence >= 0.35:
            command = self.command_builder.build_tap_highlight(target, message, safety)
            guide_data = asdict(command)
            guide_data["target_label"] = target.label
            return AgentDecision(state_id, confidence, transition.to_state, GuideCommandResponse(**guide_data))

        if transition.allow_scroll and any(node.scrollable for node in nodes):
            command = self.command_builder.build_scroll_hint("down", f"아래쪽에서 {transition.target_keywords[0]} 메뉴를 찾아볼게요.", safety)
            guide_data = asdict(command)
            guide_data["render_policy"]["target_hint"] = {"keywords": list(transition.target_keywords)}
            return AgentDecision(state_id, confidence, transition.to_state, GuideCommandResponse(**guide_data))

        return self._unknown(state_id, confidence, message)

    def _classify(
        self, package_name: str, nodes: list[NormalizedNode], previous_state: str | None
    ) -> tuple[str, float]:
        if package_name not in {"com.android.settings", "com.samsung.android.settings"}:
            return "S0_UNKNOWN", 1.0

        labels = " ".join(node.label.lower() for node in nodes)
        has_slider = any("seekbar" in node.role.lower() or "slider" in node.role.lower() for node in nodes)
        if previous_state == "S5_FONT_SIZE_CHANGED" and has_slider:
            return "S5_FONT_SIZE_CHANGED", 0.9
        if has_slider and ("글자 크기" in labels or "font size" in labels):
            return "S4_FONT_SIZE_CONTROL", 0.95

        expected = {
            "S1_SETTINGS_HOME": "S2_DISPLAY_MENU",
            "S2_DISPLAY_MENU": "S3_FONT_MENU",
            "S3_FONT_MENU": "S4_FONT_SIZE_CONTROL",
            "S4_FONT_SIZE_CONTROL": "S5_FONT_SIZE_CHANGED",
        }.get(previous_state)
        candidates: list[tuple[float, str]] = []
        for state_id, state in self.graph.states.items():
            if state_id in {"S0_UNKNOWN", "S_DONE", "S5_FONT_SIZE_CHANGED"}:
                continue
            required = [item.lower() for item in state.get("required_keywords", [])]
            optional = [item.lower() for item in state.get("optional_keywords", [])]
            if required and not all(item in labels for item in required):
                continue
            score = 0.7 + min(sum(item in labels for item in optional) * 0.05, 0.2)
            if state_id == expected:
                score += 0.15
            candidates.append((min(score, 0.99), state_id))

        if candidates:
            score, state_id = max(candidates)
            return state_id, score
        return "S0_UNKNOWN", 0.0

    def _choose_transition(
        self, transitions: list[TransitionDefinition], nodes: list[NormalizedNode]
    ) -> TransitionDefinition:
        return max(transitions, key=lambda transition: self.matcher.match(transition, nodes).confidence)

    def _unknown(self, state_id: str, confidence: float, message: str | None = None) -> AgentDecision:
        guide = GuideCommandResponse(
            guide_command_id=f"guide_{uuid4().hex}",
            command_type="gesture_hint",
            action_type="observe_only",
            message=message or MESSAGES["S0_UNKNOWN"],
            safety_level="LOW_RISK",
            requires_user_action=True,
        )
        return AgentDecision(state_id, confidence, state_id, guide)

    def _done(self, state_id: str, confidence: float) -> AgentDecision:
        guide = GuideCommandResponse(
            guide_command_id=f"guide_{uuid4().hex}",
            command_type="done",
            action_type="observe_only",
            message="설정이 적용된 것 같아요. 화면 글자가 커졌는지 확인해보세요.",
            safety_level="LOW_RISK",
            requires_user_action=False,
        )
        return AgentDecision(state_id, confidence, "S_DONE", guide, completed=True)


class ClickAgentEngine(AgentClient):
    """Turn-based Agent entry point used by the backend API boundary."""

    def __init__(
        self,
        sessions: InMemorySessionRepository,
        repository_root: Path | None = None,
    ) -> None:
        super().__init__(repository_root)
        self.sessions = sessions

    def handle_turn(self, request: AgentTurnRequest) -> AgentTurnResponse:
        session = self.sessions.find(request.session_id)
        if session is None:
            if request.raw_goal is None:
                raise SessionNotFoundError(request.session_id)
            if not self.supports_goal(request.raw_goal):
                raise UnsupportedGoalError("The requested goal is not supported.")
            task = self.graph.task
            session = GuidanceSession(
                session_id=request.session_id,
                task_id=task.task_id,
                goal=request.raw_goal,
                goal_type=task.goal_type,
                graph_version=task.graph_version,
            )
            self.sessions.add(session)

        if request.raw_goal is not None and request.raw_goal != session.goal:
            raise ValueError("raw_goal cannot be changed after the first turn")

        snapshot = self._to_snapshot(request)
        current_slider_values = self._slider_values(request)
        slider_increased = any(
            node_id in current_slider_values
            and current_slider_values[node_id] > previous_value
            for node_id, previous_value in session.slider_values.items()
        )
        if request.user_confirmation == "rejected":
            decision = self._rejected(session.current_state or "S0_UNKNOWN")
        elif session.current_state == "S4_FONT_SIZE_CONTROL" and slider_increased:
            decision = self._done("S5_FONT_SIZE_CHANGED", 1.0)
        else:
            decision = self.decide(
                snapshot,
                session.current_state,
                request.user_confirmation == "accepted",
            )

        if decision.completed:
            session.status = "COMPLETED"
            session.current_state = "S_DONE"
        elif decision.guide.command_type == "blocked":
            session.status = "BLOCKED"
            session.current_state = decision.state_id
        elif decision.guide.command_type == "confirmation":
            session.status = "WAITING_CONFIRMATION"
            session.current_state = decision.state_id
        else:
            session.status = "IN_PROGRESS"
            # A drag command is only a visual cue. Keep the observed state until a
            # fresh snapshot proves that the slider value actually increased.
            session.current_state = (
                decision.state_id
                if decision.guide.action_type == "drag"
                else decision.next_state
                if request.user_confirmation == "accepted"
                else decision.state_id
            )

        if current_slider_values and not slider_increased:
            session.slider_values = current_slider_values

        session.step = self._step_for_state(decision.state_id)
        session.updated_at = utc_now()
        self.sessions.save(session)
        return AgentTurnResponse(
            request_id=request.request_id,
            session_id=session.session_id,
            task_id=session.task_id,
            step=session.step,
            total_steps=4,
            state_id=decision.state_id,
            state_confidence=decision.state_confidence,
            task_status=session.status,
            guide_command=decision.guide,
            metadata={
                "graph_version": session.graph_version,
                "verification": {
                    "slider_value_increased": slider_increased,
                    "slider_values": current_slider_values,
                },
            },
        )

    @staticmethod
    def _step_for_state(state_id: str) -> int:
        return {
            "S0_UNKNOWN": 0,
            "S1_SETTINGS_HOME": 1,
            "S2_DISPLAY_MENU": 2,
            "S3_FONT_MENU": 3,
            "S4_FONT_SIZE_CONTROL": 4,
            "S5_FONT_SIZE_CHANGED": 4,
            "S_DONE": 4,
        }.get(state_id, 0)

    @staticmethod
    def _to_snapshot(request: AgentTurnRequest) -> ScreenSnapshot:
        tree = request.accessibility_tree
        return ScreenSnapshot(
            package_name=tree.package_name or "",
            captured_at=tree.captured_at or utc_now(),
            screen_width=tree.screen_width,
            screen_height=tree.screen_height,
            nodes=[
                AccessibilityNode(
                    node_id=node.node_id,
                    text=node.text,
                    content_description=node.content_description,
                    resource_id=node.resource_id,
                    class_name=node.class_name,
                    role=node.role,
                    bounds=node.bounds_in_screen,
                    clickable=node.clickable,
                    enabled=node.enabled,
                    selected=node.selected,
                    scrollable=node.scrollable,
                    checkable=node.checkable,
                    extras=node.extras,
                )
                for node in tree.nodes
            ],
        )

    @staticmethod
    def _slider_values(request: AgentTurnRequest) -> dict[str, float]:
        values: dict[str, float] = {}
        keys = ("progress", "value", "current", "range_current", "percent")
        for node in request.accessibility_tree.nodes:
            for key in keys:
                raw_value = node.extras.get(key)
                if isinstance(raw_value, bool):
                    continue
                if isinstance(raw_value, (int, float)):
                    values[node.node_id] = float(raw_value)
                    break
                if isinstance(raw_value, str):
                    try:
                        values[node.node_id] = float(raw_value.strip().rstrip("%"))
                        break
                    except ValueError:
                        continue
        return values

    @staticmethod
    def _rejected(state_id: str) -> AgentDecision:
        guide = GuideCommandResponse(
            guide_command_id=f"guide_{uuid4().hex}",
            command_type="blocked",
            action_type="observe_only",
            message="User rejected the requested action.",
            safety_level="BLOCKED",
            requires_user_action=False,
            render_policy={"reason": "user_rejected"},
        )
        return AgentDecision(state_id, 1.0, state_id, guide)
