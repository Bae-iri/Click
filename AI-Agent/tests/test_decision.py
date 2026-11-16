"""Competing policies must resolve before binding an operation or saving a turn."""
from dataclasses import replace
from unittest.mock import Mock

import pytest

from ai_agent.runtime.session import InMemorySessionStore
from ai_agent.runtime.types import AgentTurnRequest, CommandType, SafetyLevel, UserActionType, VisionElement
from test_engine_flow import _font_size_control_tree, _raw_goal
from test_runtime_reliability import Turns
from test_vision_cycles import engine, turn


def control_at_limit():
    return tuple(replace(node, extras={**node.extras, 'min': 0, 'max': 7})
                 if node.node_id == 'font-slider' else node
                 for node in _font_size_control_tree(7))


@pytest.mark.parametrize('primary', [False, True])
@pytest.mark.parametrize('failure', ['masking_failed', 'unsupported'])
def test_vision_stop_wins_over_adjustment_limit_and_primary_wait(primary, failure):
    runtime = engine()
    if primary:
        runtime.screen_resolver = Mock(resolve=Mock(return_value=None), last_diagnostics={})
    result = runtime.handle_turn(replace(turn(1, failure=failure),
        accessibility_tree=control_at_limit(), active_window_id=None))
    assert result.guide_command.command_type == CommandType.BLOCKED
    assert result.guide_command.safety_level == SafetyLevel.BLOCKED
    assert result.guide_command.target_bounds is None
    assert runtime.session_store.get('session').pending_attempt is None


def test_user_rejection_wins_over_limit_message():
    turns = Turns()
    turns.send(_font_size_control_tree(2), at=1000)
    result = turns.send(control_at_limit(), at=2000, confirmation='rejected')
    assert result.guide_command.command_type == CommandType.CANCELLED
    assert result.debug['guide_policy'] == 'rejected'
    assert turns.session.pending_attempt is None


def test_primary_abstention_cannot_overwrite_final_ocr_handoff():
    runtime = engine()
    menus = (VisionElement('title', '설정', (20, 10, 500, 70), confidence=.95, actionable=False),
             VisionElement('other', '글자 크기와 스타일', (20, 300, 900, 400), confidence=.95, actionable=False))
    for number in (1, 2):
        runtime.handle_turn(turn(number, menus))
    runtime.screen_resolver = Mock(resolve=Mock(return_value=None), last_diagnostics={})
    result = runtime.handle_turn(turn(3, menus))
    assert result.debug['normalized_node_count'] > 0
    assert result.debug['guide_policy'] == 'vision_cycle'
    assert result.guide_command.render_policy['vision_cycle']['status'] == 'failed'
    assert result.guide_command.render_policy['recovery']['waiting_for'] == 'user'
    assert result.guide_command.target_bounds is None


class RecordingStore(InMemorySessionStore):
    def __init__(self):
        super().__init__()
        self.writes = []

    def start(self, **changes):
        result = super().start(**changes)
        self.writes.append(result)
        return result

    def update(self, session_id, **changes):
        result = super().update(session_id, **changes)
        self.writes.append(result)
        return result


def test_every_regular_turn_saves_guide_attempt_and_progress_together_once():
    runtime = engine()
    store = RecordingStore()
    runtime.session_store = store
    for number in (1, 2):
        result = runtime.handle_turn(AgentTurnRequest(
            request_id=f'r{number}', session_id='session',
            raw_goal=_raw_goal('글자 크기 키워줘') if number == 1 else None,
            accessibility_tree=_font_size_control_tree(2),
            observation_id=f'o{number}', captured_at_ms=number * 1000))
        assert len(store.writes) == number
        saved = store.writes[-1]
        assert saved.last_guide_command_id == result.guide_command.guide_command_id
        assert list(saved.progress_steps) == result.debug['checklist']
        assert saved.current_state_id == result.current_state_id


def test_navigation_interruption_also_commits_once():
    turns = Turns()
    store = RecordingStore()
    turns.engine.session_store = store
    guide = turns.send(at=1000)
    event = turns.event(guide, at=1200, action=UserActionType.BACK_NAVIGATED)
    result = turns.send(at=2000, event=event)
    assert len(store.writes) == 2
    assert store.writes[-1].last_guide_command_id == result.guide_command.guide_command_id
    assert not result.verification_result or not result.verification_result.count_attempt


def test_masking_failure_never_carries_recoverable_navigation_metadata():
    value = turn(1)
    value = replace(value, vision_snapshot=replace(value.vision_snapshot,
        privacy_status='masking_failed', redaction_applied=False))
    result = engine().handle_turn(value)
    assert result.guide_command.command_type == CommandType.BLOCKED
    assert 'recovery' not in result.guide_command.render_policy
    assert result.guide_command.render_policy['vision_cycle']['status'] == 'blocked'


def test_observed_control_limit_has_no_pending_operation():
    turns = Turns()
    result = turns.send(control_at_limit(), at=1000)
    assert result.debug['guide_policy'] == 'adjustment_limit'
    assert result.guide_command.render_policy['adjustment_limit']
    assert turns.session.pending_attempt is None
