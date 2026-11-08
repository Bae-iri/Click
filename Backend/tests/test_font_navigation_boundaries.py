"""A Display menu row must not identify the neighboring brightness slider as font size."""
from dataclasses import replace
import json
from pathlib import Path
import pytest

from ai_agent.runtime.classifier import classify_screen_state
from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.screen_privacy import filter_nodes
from ai_agent.runtime import ClickAgentEngine, UserActionEvent, UserActionType
from ai_agent.runtime.types import AccessibilityNode, AgentTurnRequest, CommandType, DeviceContext
from app.services.screen_jev import JevScreenResolver
from tests.test_screen_jev import scene, sender_for


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MODEL', 'jev-1.14.0')


def test_actual_samsung_display_capture_offers_only_the_visible_font_menu():
    screen = json.loads((Path(__file__).parent / 'fixtures/expanded_settings/samsung-display-font-menu.json').read_text())
    fields = AccessibilityNode.__dataclass_fields__
    raw = tuple(AccessibilityNode(**{key: tuple(value) if key == 'bounds_in_screen' else value
                                    for key, value in node.items() if key in fields}) for node in screen['nodes'])
    # The task-specific privacy filter intentionally removes brightness text.
    # Its unnamed slider must still not borrow the font menu's label.
    raw = filter_nodes(raw, 'font_size')
    nodes = normalize_accessibility_tree(raw, screen_bounds=(0, 0, screen['screen_width'], screen['screen_height']),
                                         active_window_id=screen['active_window_id'])
    graph, _, _, goal = scene('font_size')
    assert classify_screen_state(nodes, graph) == 'S2_DISPLAY_MENU'
    route = next(t for t in graph.transitions if t.from_state == 'S2_DISPLAY_MENU')

    def send(payload, **kwargs):
        choices = [value for value in payload['questions']['screen']['criteria'].values() if isinstance(value, dict)]
        assert len(choices) == 1
        assert choices[0]['transition'] == route.transition_id
        assert choices[0]['action'] == 'tap'
        return sender_for(route)(payload, **kwargs)

    result = JevScreenResolver(send).resolve(nodes=nodes, raw_nodes=raw, graph=graph, goal=goal)
    assert result.state_id == 'S2_DISPLAY_MENU'
    assert result.target_node_id == 'node_0_0_2_6'
    assert result.target_bounds == (0, 2002, 1080, 2160)


def display_screen(*, brightness_label=None, hierarchy=True):
    package = 'com.android.settings'
    return (
        AccessibilityNode('root', package_name=package, bounds_in_screen=(0, 0, 1080, 2400)),
        AccessibilityNode('title', package_name=package, text='디스플레이',
            class_name='android.widget.TextView', bounds_in_screen=(40, 40, 400, 100)),
        AccessibilityNode('brightness', package_name=package, text=brightness_label,
            class_name='android.widget.SeekBar', bounds_in_screen=(40, 200, 900, 260),
            extras={'progress': 58720256, 'min': 0, 'max': 67108864}),
        AccessibilityNode('font-row', package_name=package, clickable=True, parent_id='root',
            text=None if hierarchy else '글자 크기와 스타일',
            class_name='android.widget.LinearLayout', bounds_in_screen=(20, 500, 1000, 650)),
        AccessibilityNode('font-label', package_name=package, text='글자 크기와 스타일',
            parent_id='font-row' if hierarchy else None,
            class_name='android.widget.TextView', bounds_in_screen=(40, 540, 700, 600)),
    )


@pytest.mark.parametrize('hierarchy', [True, False])
@pytest.mark.parametrize('brightness_label', [None, '밝기'])
def test_display_title_and_font_menu_do_not_ground_brightness_as_font(hierarchy, brightness_label):
    graph, _, _, _ = scene('font_size')
    raw = filter_nodes(display_screen(brightness_label=brightness_label, hierarchy=hierarchy), 'font_size')
    evidence = {}
    assert classify_screen_state(normalize_accessibility_tree(raw), graph, evidence=evidence) == 'S2_DISPLAY_MENU'
    assert not any(c['control_match'] for c in evidence['candidates'])


@pytest.mark.parametrize('hierarchy', [True, False])
def test_jev_only_receives_navigation_before_native_font_page(hierarchy):
    graph, _, _, goal = scene('font_size')
    raw = filter_nodes(display_screen(brightness_label='밝기', hierarchy=hierarchy), 'font_size')
    route = next(t for t in graph.transitions if t.from_state == 'S2_DISPLAY_MENU')

    def send(payload, **kwargs):
        candidates = [v for v in payload['questions']['screen']['criteria'].values() if isinstance(v, dict)]
        assert candidates
        assert all(c['action'] != 'drag' for c in candidates)
        assert all(c['transition'] == route.transition_id for c in candidates)
        return sender_for(route)(payload, **kwargs)

    result = JevScreenResolver(send).resolve(nodes=normalize_accessibility_tree(raw), raw_nodes=raw,
                                            graph=graph, goal=goal)
    assert result.transition_id == route.transition_id
    assert result.target_node_id == 'font-row'


def test_display_navigation_precedes_font_consent_and_measured_completion():
    graph, adjustment, _, goal = scene('font_size')
    route = next(t for t in graph.transitions if t.from_state == 'S2_DISPLAY_MENU')
    calls = []

    def send(payload, **kwargs):
        calls.append(payload)
        candidates = [v for v in payload['questions']['screen']['criteria'].values() if isinstance(v, dict)]
        target = adjustment if any(c['action'] == 'drag' for c in candidates) else route
        return sender_for(target)(payload, **kwargs)

    engine = ClickAgentEngine(task_graphs={goal.task_id: graph}, screen_resolver=JevScreenResolver(send))
    start = AgentTurnRequest(request_id='r1', session_id='font-route', episode_id='episode',
        typed_goal=goal, raw_goal=None, device_context=DeviceContext(platform='android'),
        accessibility_tree=display_screen(), observation_id='o1', captured_at_ms=1000,
        applied_settings={'source': 'android.provider.Settings.System.FONT_SCALE', 'font_scale': 1.1})
    first = engine.handle_turn(start)
    assert first.current_state_id == 'S2_DISPLAY_MENU'
    assert first.guide_command.target_node_id == 'font-row'
    assert first.guide_command.action_type.value == 'tap'
    repeated = engine.handle_turn(replace(start, request_id='r2', observation_id='o2', captured_at_ms=2000))
    assert repeated.debug['ui_selection']['retained']
    assert len(calls) == 1
    assert not repeated.debug['task_completed']

    package = 'com.android.settings'
    font_page = (
        AccessibilityNode('font-title', package_name=package, text='글자 크기와 스타일',
            class_name='android.widget.TextView', bounds_in_screen=(40, 40, 800, 100)),
        AccessibilityNode('font-slider', package_name=package, class_name='android.widget.SeekBar',
            resource_id=package + ':id/seekBarForFontSize', bounds_in_screen=(100, 500, 900, 550),
            extras={'progress': 3, 'min': 0, 'max': 7}),
    )
    arrived = replace(start, request_id='r3', observation_id='o3', captured_at_ms=3000,
                      accessibility_tree=font_page)
    confirmation = engine.handle_turn(arrived)
    assert confirmation.guide_command.command_type == CommandType.CONFIRMATION
    assert confirmation.guide_command.target_node_id == 'font-slider'
    assert not confirmation.debug['task_completed']
    approved = engine.handle_turn(replace(arrived, request_id='r4', observation_id='o4',
                                          captured_at_ms=4000, user_confirmation='accepted'))
    guide = approved.guide_command
    assert guide.action_type.value == 'drag'
    # Several unchanged observations without an operation cannot complete the task.
    for i in (5, 6):
        unchanged = engine.handle_turn(replace(arrived, request_id=f'r{i}', observation_id=f'o{i}',
                                               captured_at_ms=i * 1000))
        assert not unchanged.debug['task_completed']
        assert unchanged.guide_command.command_type != CommandType.DONE
        guide = unchanged.guide_command
    event = UserActionEvent(UserActionType.DRAG_COMPLETED, guide.guide_command_id,
        guide.target_node_id, target_bounds=guide.target_bounds, episode_id='episode',
        event_id='physical-drag', occurred_at_ms=6500)
    changed_page = (font_page[0], replace(font_page[1], extras={'progress': 4, 'min': 0, 'max': 7}))
    done = engine.handle_turn(replace(arrived, request_id='r7', observation_id='o7', captured_at_ms=7500,
        accessibility_tree=changed_page, user_action_event=event,
        applied_settings={**start.applied_settings, 'font_scale': 1.3}))
    assert done.debug['task_completed']
    assert done.guide_command.command_type == CommandType.DONE
