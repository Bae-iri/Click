from __future__ import annotations

from dataclasses import asdict
from uuid import uuid4

from app.clients.agent import AgentClient, UnsupportedGoalError
from app.models.session import GuidanceSession, utc_now
from app.repositories.sessions import InMemorySessionRepository
from app.schemas.guidance import (
    CreateSessionRequest,
    CreateSessionResponse,
    ObserveRequest,
    ObserveResponse,
    SessionResponse,
)


class GuidanceService:
    def __init__(self, sessions: InMemorySessionRepository, agent: AgentClient) -> None:
        self.sessions = sessions
        self.agent = agent

    def create_session(self, request: CreateSessionRequest) -> CreateSessionResponse:
        if not self.agent.supports_goal(request.goal):
            raise UnsupportedGoalError("현재는 Android 설정의 글자 크기 확대만 지원합니다.")

        task = self.agent.graph.task
        session = GuidanceSession(
            session_id=f"session_{uuid4().hex}",
            task_id=task.task_id,
            goal=request.goal,
            goal_type=task.goal_type,
            graph_version=task.graph_version,
        )
        self.sessions.add(session)
        return CreateSessionResponse(
            session_id=session.session_id,
            task_id=session.task_id,
            goal_type=session.goal_type,
            graph_version=session.graph_version,
            status="WAITING_FOR_SCREEN",
        )

    def observe(self, session_id: str, request: ObserveRequest) -> ObserveResponse:
        session = self.sessions.get(session_id)
        decision = self.agent.decide(request.snapshot, session.current_state, request.confirmed)

        if decision.completed:
            session.status = "COMPLETED"
            session.current_state = "S_DONE"
        elif decision.guide.command_type == "confirmation":
            session.status = "WAITING_CONFIRMATION"
            session.current_state = decision.state_id
        else:
            session.status = "IN_PROGRESS"
            session.current_state = decision.next_state if request.confirmed else decision.state_id

        session.step = self._step_for_state(decision.state_id)
        session.updated_at = utc_now()
        self.sessions.save(session)

        return ObserveResponse(
            session_id=session.session_id,
            task_id=session.task_id,
            step=session.step,
            total_steps=4,
            state_id=decision.state_id,
            state_confidence=decision.state_confidence,
            task_status=session.status,
            guide=decision.guide,
        )

    def get_session(self, session_id: str) -> SessionResponse:
        session = self.sessions.get(session_id)
        return SessionResponse(**asdict(session))

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

