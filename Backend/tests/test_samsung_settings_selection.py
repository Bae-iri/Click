"""Physical Samsung snapshots must describe one menu and embedded slider states faithfully."""
import json
from dataclasses import replace
from pathlib import Path

import pytest

from ai_agent.runtime.normalizer import normalize_accessibility_tree
from ai_agent.runtime.screen_privacy import filter_nodes
from ai_agent.runtime.types import AccessibilityNode, TypedGoal
from app.services.screen_jev import JevScreenResolver
from app.task_graphs import approved_graphs
from tests.test_screen_jev import sender_for


@pytest.fixture(autouse=True)
def configuration(monkeypatch):
    monkeypatch.setenv('TYPESAFE_API_KEY', 'test-only-key')
    monkeypatch.setenv('JEV_MIN_CONFIDENCE', '0.8')
    monkeypatch.setenv('JEV_SCREEN_JUDGMENT_MODE', 'atomic')


def physical_scene(source, goal_type, change_title=None, graph=None):
    fixture = json.loads((Path(__file__).parent / 'fixtures/samsung-public-navigation-20261122.json').read_text())
    tree = next(case['tree'] for case in fixture['cases'] if case['task'] == source)
    graph = graph or approved_graphs()[goal_type]
    raw = tuple(AccessibilityNode(**{**node, 'bounds_in_screen': tuple(node['bounds_in_screen'])})
                for node in tree['nodes'])
    if change_title is not None:
        raw = tuple(change_title(node) if node.text in {'접근성', '연결', '디스플레이', '소리 및 진동'}
                    else node for node in raw)
    raw = filter_nodes(raw, graph.definition.required_slots['setting'])
    bounds = (0, 0, tree['screen_width'], tree['screen_height'])
    nodes = normalize_accessibility_tree(raw, screen_bounds=bounds, active_window_id=tree['active_window_id'])
    goal = TypedGoal(goal_type, graph.definition.target_app, graph.definition.task_type,
                     graph.definition.required_slots, '', 1, task_id=graph.definition.task_id,
                     is_supported=True, required_slots_filled=True)
    return graph, goal, raw, nodes, bounds


def resolve_scene(scene, transition_id):
    graph, goal, raw, nodes, bounds = scene
    edge = next(edge for edge in graph.transitions if edge.transition_id == transition_id)
    payloads = []

    def send(payload, **kwargs):
        payloads.append(payload)
        return sender_for(edge)(payload, **kwargs)

    resolver = JevScreenResolver(send)
    match = resolver.resolve(nodes=nodes, raw_nodes=raw, graph=graph, goal=goal, screen_bounds=bounds)
    assert len(payloads) == 1
    return match, payloads[0]


@pytest.mark.parametrize(('source', 'key'), [
    ('accessibility', 'visibility'), ('accessibility', 'hearing'),
    ('accessibility', 'talkback'), ('accessibility', 'interaction'),
    ('volume', 'ringtone'), ('volume', 'notification_sound'),
    ('wifi', 'wifi'), ('bluetooth', 'bluetooth'),
])
def test_independent_native_category_title_removes_the_duplicate_home_route(source, key):
    match, payload = resolve_scene(physical_scene(source, 'settings.navigation.' + key), 'OPEN_PAGE')
    candidates = [value for value in payload['questions']['screen']['criteria'].values() if isinstance(value, dict)]
    assert [candidate['transition'] for candidate in candidates] == ['OPEN_PAGE']
    assert match.transition_id == 'OPEN_PAGE'
    assert payload['state']['immediate_request']['scope'] == 'immediate_menu_opening'
    assert payload['state']['immediate_request']['open_menu'] == list(candidates[0]['target_names'])


def test_direct_menu_on_settings_home_keeps_the_home_route():
    scene = physical_scene('accessibility', 'settings.navigation.visibility',
                           change_title=lambda node: replace(node, text='설정'))
    match, payload = resolve_scene(scene, 'OPEN_PAGE_FROM_HOME')
    candidates = [value for value in payload['questions']['screen']['criteria'].values() if isinstance(value, dict)]
    assert [candidate['transition'] for candidate in candidates] == ['OPEN_PAGE_FROM_HOME']
    assert match.transition_id == 'OPEN_PAGE_FROM_HOME'


@pytest.mark.parametrize('title_change', [
    lambda node: replace(node, text=None),
    lambda node: replace(node, node_id='vision:heading'),
    lambda node: replace(node, parent_id='node_0_0_0_0_0_0_0_1_0_0_0_0_0_4'),
])
def test_missing_vision_or_menu_owned_title_does_not_resolve_duplicate_sources(title_change):
    scene = physical_scene('accessibility', 'settings.navigation.visibility', change_title=title_change)
    _, payload = resolve_scene(scene, 'OPEN_PAGE')
    candidates = [value for value in payload['questions']['screen']['criteria'].values() if isinstance(value, dict)]
    assert {candidate['transition'] for candidate in candidates} == {'OPEN_PAGE', 'OPEN_PAGE_FROM_HOME'}
    assert 'immediate_request' not in payload['state']


def test_ambiguous_authored_page_names_do_not_resolve_duplicate_sources():
    graph = approved_graphs()['settings.navigation.visibility']
    states = dict(graph.states)
    states['S1_SETTINGS_HOME'] = replace(states['S1_SETTINGS_HOME'], required_keyword_groups=(('접근성',),))
    scene = physical_scene('accessibility', graph.definition.goal_type, graph=replace(graph, states=states))
    _, payload = resolve_scene(scene, 'OPEN_PAGE')
    candidates = [value for value in payload['questions']['screen']['criteria'].values() if isinstance(value, dict)]
    assert len(candidates) == 2


@pytest.mark.parametrize('direction', ['increase', 'decrease'])
def test_brightness_control_state_describes_the_slider_embedded_on_display(direction):
    scene = physical_scene('font_size', 'settings.brightness.' + direction)
    match, payload = resolve_scene(scene, 'ADJUST_BRIGHTNESS')
    page = next(value for value in payload['questions']['page']['criteria'].values()
                if isinstance(value, dict) and value['state_id'] == 'S3_CONTROL')
    assert page['scope'] == 'visible_native_control'
    assert page['control_evidence'][0]['role'] == 'slider'
    assert '밝기' in page['control_evidence'][0]['target_names']
    assert 'literal page title' in payload['questions']['page']['instructions']
    assert match.transition_id == 'ADJUST_BRIGHTNESS'
    assert any(node['label'] == '디스플레이' and node['structure_role'] == 'screen_text'
               for node in payload['state']['nodes'])


@pytest.mark.parametrize('direction', ['increase', 'decrease'])
def test_media_control_scope_requires_the_named_media_slider(direction):
    tree = json.loads((Path(__file__).parent / 'fixtures/expanded_settings/volume.json').read_text())
    graph = approved_graphs()['settings.volume.' + direction]
    fields = AccessibilityNode.__dataclass_fields__
    raw = tuple(AccessibilityNode(**{key: tuple(value) if key == 'bounds_in_screen' else value
                                    for key, value in node.items() if key in fields}) for node in tree['nodes'])
    raw = filter_nodes(raw, 'volume')
    bounds = (0, 0, tree['screen_width'], tree['screen_height'])
    nodes = normalize_accessibility_tree(raw, screen_bounds=bounds, active_window_id=tree['active_window_id'])
    goal = TypedGoal(graph.definition.goal_type, graph.definition.target_app, graph.definition.task_type,
                     graph.definition.required_slots, '', 1, task_id=graph.definition.task_id,
                     is_supported=True, required_slots_filled=True)
    match, payload = resolve_scene((graph, goal, raw, nodes, bounds), 'ADJUST_VOLUME')
    candidate = next(value for value in payload['questions']['screen']['criteria'].values()
                     if isinstance(value, dict) and value['transition'] == 'ADJUST_VOLUME')
    assert candidate['current_page_scope'] == 'visible_native_control'
    control = next(node for node in payload['state']['nodes'] if node['id'] == candidate['node'])
    assert control['label'] == 'media volume'
    assert match.target_node_id == 'node_0_0_1_1_1_0'
