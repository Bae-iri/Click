"""Session state storage for the agent runtime."""

from __future__ import annotations

from dataclasses import replace
from typing import Protocol

from click_agent.types import AgentSessionState, ScreenSnapshot, TypedGoal, UserActionEvent


class SessionStore(Protocol):
    def has(self, session_id: str) -> bool:
        ...

    def get(self, session_id: str) -> AgentSessionState | None:
        ...

    def start(
        self,
        *,
        session_id: str,
        task_id: str,
        typed_goal: TypedGoal,
        current_state_id: str,
        pending_transition_id: str | None = None,
        pending_confirmation_transition_id: str | None = None,
        last_guide_command_id: str | None = None,
        last_target_node_id: str | None = None,
        failed_node_ids: tuple[str, ...] = (),
        transition_attempts: dict[str, int] | None = None,
        last_snapshot: ScreenSnapshot | None = None,
        last_user_action_event: UserActionEvent | None = None,
    ) -> AgentSessionState:
        ...

    def update(
        self,
        session_id: str,
        **changes: object,
    ) -> AgentSessionState:
        ...


class InMemorySessionStore(SessionStore):
    def __init__(self) -> None:
        self._sessions: dict[str, AgentSessionState] = {}

    def has(self, session_id: str) -> bool:
        return session_id in self._sessions

    def get(self, session_id: str) -> AgentSessionState | None:
        return self._sessions.get(session_id)

    def start(
        self,
        *,
        session_id: str,
        task_id: str,
        typed_goal: TypedGoal,
        current_state_id: str,
        pending_transition_id: str | None = None,
        pending_confirmation_transition_id: str | None = None,
        last_guide_command_id: str | None = None,
        last_target_node_id: str | None = None,
        failed_node_ids: tuple[str, ...] = (),
        transition_attempts: dict[str, int] | None = None,
        last_snapshot: ScreenSnapshot | None = None,
        last_user_action_event: UserActionEvent | None = None,
    ) -> AgentSessionState:
        state = AgentSessionState(
            session_id=session_id,
            task_id=task_id,
            typed_goal=typed_goal,
            current_state_id=current_state_id,
            pending_transition_id=pending_transition_id,
            pending_confirmation_transition_id=pending_confirmation_transition_id,
            last_guide_command_id=last_guide_command_id,
            last_target_node_id=last_target_node_id,
            failed_node_ids=failed_node_ids,
            transition_attempts=transition_attempts or {},
            last_snapshot=last_snapshot,
            last_user_action_event=last_user_action_event,
        )
        self._sessions[session_id] = state
        return state

    def update(
        self,
        session_id: str,
        **changes: object,
    ) -> AgentSessionState:
        current = self._sessions[session_id]
        updated = replace(current, **changes)
        self._sessions[session_id] = updated
        return updated
