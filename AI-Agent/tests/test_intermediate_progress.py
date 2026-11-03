from dataclasses import replace
import json

import pytest

from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.matcher import match_next_ui
from ai_agent.runtime.semantic_state import resolve_state
from ai_agent.runtime.targeting import target_key
from ai_agent.runtime.types import SafetyLevel, UserActionType
from ai_agent.runtime.verification import navigation_reachable
from ai_agent.runtime.tasks import MVP_01_TASK_GRAPH as GRAPH
from test_settings_navigation import HOME, SCREEN, settings_screen
from test_runtime_reliability import Turns
from test_engine_flow import _font_size_control_tree


def test_descendant_title_owns_identity_even_when_row_description_names_another_menu():
    tree = tuple(replace(n, content_description='접근성') if n.node_id == 'display' else n for n in HOME)
    nodes = normalize_accessibility_tree(tree, screen_bounds=SCREEN)
    assert match_next_ui('S1_SETTINGS_HOME', nodes, GRAPH).target_node_id == 'display'
    original = normalize_accessibility_tree(HOME, screen_bounds=SCREEN)
    assert target_key(next(n for n in nodes if n.node_id == 'display')) == target_key(next(n for n in original if n.node_id == 'display'))


def test_label_cannot_be_inherited_from_another_window():
    tree = tuple(replace(n, window_id=2 if n.node_id == 'display-title' else 1) for n in HOME)
    nodes = normalize_accessibility_tree(tree, screen_bounds=SCREEN)
    assert match_next_ui('S1_SETTINGS_HOME', nodes, GRAPH).target_node_id != 'display'


@pytest.mark.parametrize('title', ['Display', '화면'])
def test_screen_and_target_use_the_same_graph_aliases(title):
    nodes = normalize_accessibility_tree(settings_screen(title, (('font', '글자 크기와 스타일', ''),)), screen_bounds=SCREEN)
    assert classify_screen_state(nodes, GRAPH) == 'S2_DISPLAY_MENU'


@pytest.mark.parametrize('with_action', [False, True])
def test_skipping_navigation_screens_resumes_at_observed_control_without_claiming_completion(with_action):
    turns = Turns()
    first = turns.send(at=1000)
    event = turns.event(first, at=1500) if with_action else None
    result = turns.send(_font_size_control_tree(2), at=2000, event=event)
    assert result.current_state_id == 'S4_FONT_SIZE_CONTROL'
    assert result.verification_result.evidence == 'observed_progress'
    assert not result.verification_result.count_attempt
    assert not turns.session.task_completed
    assert turns.session.total_failed_attempts == 0
    assert result.guide_command.command_type.value == 'confirmation'


def test_navigation_reachability_never_crosses_change_or_consent():
    assert not navigation_reachable(GRAPH, 'S4_FONT_SIZE_CONTROL', 'S5_FONT_SIZE_CHANGED')
    protected = replace(GRAPH, transitions=tuple(replace(t, safety_level=SafetyLevel.CONFIRM_REQUIRED)
        if t.transition_id == 'S3_TO_S4_FONT_SIZE_CONTROL' else t for t in GRAPH.transitions))
    assert not navigation_reachable(protected, 'S2_DISPLAY_MENU', 'S4_FONT_SIZE_CONTROL')


def test_empty_redraw_preserves_baseline_until_delayed_action_and_result_arrive():
    turns = Turns()
    turns.send(_font_size_control_tree(2), at=1000, applied_scale=1.0)
    guide = turns.send(_font_size_control_tree(2), at=1100, applied_scale=1.0, confirmation='accepted')
    baseline = dict(turns.session.pending_attempt)
    empty = turns.send((), at=1400)
    assert empty.guide_command.guide_command_id == guide.guide_command.guide_command_id
    assert turns.session.pending_attempt['baseline_value'] == baseline['baseline_value']
    event = turns.event(guide, at=1200, action=UserActionType.DRAG_COMPLETED)
    result = turns.send(_font_size_control_tree(3), at=2000, event=event, applied_scale=1.2)
    assert result.verification_result.task_completed
    assert turns.session.technical_outcome['baseline_font_scale'] == 1.0


class Resolver:
    def __init__(self, invalid=False):
        self.payload = None
        self.invalid = invalid

    def resolve(self, payload):
        self.payload = payload
        selected = next(c for c in payload['candidates'] if c['state_id'] == 'S2_DISPLAY_MENU')
        return {'state_id': selected['state_id'], 'evidence_ids': ['imaginary' if self.invalid else selected['target_id']]}


@pytest.mark.parametrize('invalid', [False, True])
def test_semantic_choice_requires_real_target_and_sends_only_public_vocabulary(invalid):
    tree = settings_screen('기기 보기', (('private@example.com', '글자 크기와 스타일', '홍길동 010-1234-5678'),))
    nodes = normalize_accessibility_tree(tree, screen_bounds=SCREEN)
    resolver = Resolver(invalid)
    state = resolve_state(nodes, GRAPH, resolver)
    assert state == (None if invalid else 'S2_DISPLAY_MENU')
    payload = json.dumps(resolver.payload, ensure_ascii=False)
    assert not any(word in payload for word in ('private@example.com', '홍길동', '010-1234-5678', '기기 보기'))


def test_semantic_exception_abstains_and_excluded_target_is_not_reintroduced():
    class Broken:
        def resolve(self, payload):
            raise TimeoutError()
    nodes = normalize_accessibility_tree(HOME, screen_bounds=SCREEN)
    assert resolve_state(nodes, GRAPH, Broken()) is None


def test_runtime_uses_grounded_semantic_choice_only_when_screen_is_unknown():
    resolver = Resolver()
    turns = Turns()
    turns.engine.state_resolver = resolver
    tree = settings_screen('기기 보기', (('font', '글자 크기와 스타일', ''),))
    result = turns.send(tree, at=1000)
    assert resolver.payload is not None
    assert result.current_state_id == 'S2_DISPLAY_MENU'
    assert result.guide_command.target_node_id == 'font'
    assert not turns.session.task_completed
    resolver.payload = None
    turns.send(HOME, at=2000)
    assert resolver.payload is None


def test_semantic_target_cannot_override_a_persisted_failure_exclusion():
    nodes = normalize_accessibility_tree(settings_screen('기기 보기', (('font', '글자 크기와 스타일', ''),)), screen_bounds=SCREEN)
    key = target_key(next(n for n in nodes if n.node_id == 'font'))
    excluded = {edge.transition_id: (key,) for edge in GRAPH.transitions}
    resolver = Resolver()
    assert resolve_state(nodes, GRAPH, resolver, excluded_target_keys=excluded) is None
    assert resolver.payload is None
